package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage

import me.rerere.rikkahub.R
import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.data.ai.ContextBudgetPlanner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ai.AutoTaskConfig
import me.rerere.rikkahub.ui.components.ai.MAX_AUTO_TASK_IDLE_SECONDS
import me.rerere.rikkahub.ui.components.ai.MIN_AUTO_TASK_IDLE_SECONDS
import me.rerere.rikkahub.ui.components.ai.resolveAutoTaskMessage
import me.rerere.rikkahub.ui.components.ai.writeAutoTaskConfig
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Calendar
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)

    // 会话级 token 累计（当前分支所有消息 usage 之和），供聊天底部统计条展示
    val sessionTotals: StateFlow<TokenBudgetTracker.Totals> =
        conversation
            .map { TokenBudgetTracker.aggregate(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, TokenBudgetTracker.aggregate(conversation.value))
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    // 自动任务调度 Job
    private var autoTaskJob: Job? = null
    private val _autoTaskActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    val autoTaskActive: kotlinx.coroutines.flow.StateFlow<Boolean> = _autoTaskActive.asStateFlow()

    /** 用户最近活跃时间（用户发送消息时更新），自动任务活跃监测用 */
    private var userActivityAtMs = 0L

    private companion object {
        /** 活跃监测窗口：此时间段内有用户操作（或正在生成回复）视为活跃，跳过自动触发 */
        const val USER_ACTIVE_SKIP_MS = 60_000L
    }

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs =
        chatService
            .getConversationJobs()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())

        // 联动 Agent 工作悬浮窗：token 统计变化 → 更新悬浮窗展开卡片
        viewModelScope.launch {
            sessionTotals.collect { totals ->
                me.rerere.rikkahub.service.AgentOverlay.updateTokenStats(totals)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelAutoTask()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索(每个助手独立)
    val enableWebSearch =
        settings
            .map {
                it.getCurrentAssistant().enableWebSearch
            }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel =
        settings
            .map { settings ->
                settings.getCurrentChatModel()
            }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings): Job =
        viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(
        oldSettings: Settings,
        newSettings: Settings,
    ) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    fun setChatModel(
        assistant: Assistant,
        model: Model,
    ) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants =
                        settings.assistants.map {
                            if (it.id == assistant.id) {
                                it.copy(
                                    chatModelId = model.id,
                                )
                            } else {
                                it
                            }
                        },
                )
            }
        }
    }

    // Update checker
    val updateState =
        updateChecker.checkUpdate().stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(
        content: List<UIMessagePart>,
        answer: Boolean = true,
        fromAutoTask: Boolean = false,
    ) {
        if (content.isEmptyInputMessage()) return

        // 用户主动发送消息 → 更新活跃时间（自动任务触发不算用户活跃）
        if (!fromAutoTask) {
            userActivityAtMs = System.currentTimeMillis()
        }

        // 自动压缩检查：开关开启时，达到触发点（当前模式阈值）即弹窗询问
        if (answer) {
            val s = settings.value
            if (s.autoCompressEnabled) {
                viewModelScope.launch { maybeAutoCompress(s) }
            }
        }

        chatService.sendMessage(_conversationId, content, answer)
    }

    /**
     * 会话级自动压缩触发点（绝对 token 数），null 表示尚未初始化（首次检查时懒初始化）。
     * 触发后由用户决定：确认压缩 → 回到阈值；取消 → 延后到「当前累计值 + 阈值」。
     */
    var autoCompressNextTriggerAt by mutableStateOf<Long?>(null)

    /** 自动压缩触发确认弹窗：达到触发点时置 true，由 ChatPage 弹出「是否确认压缩？」 */
    var pendingAutoCompressConfirm by mutableStateOf(false)

    /** 百分比模式基准未设置时沿用的默认 context 估算（实测 DeepSeek V4 可承载 439.6K，按 512K 估算） */
    private val defaultAutoCompressBase: Long = 512 * 1024

    /** 当前模式的触发阈值（绝对 token 数）：模式A = 基准×百分比/100，模式B = token 消耗上限 */
    private fun autoCompressTriggerPoint(s: Settings): Long =
        when (s.autoCompressMode) {
            1 -> {
                s.autoCompressTokenLimit
            }

            else -> {
                val base = if (s.autoCompressTokenBase > 0) s.autoCompressTokenBase else defaultAutoCompressBase
                base * s.autoCompressThreshold.coerceIn(50, 95) / 100
            }
        }

    /** 当前模式的累计检测值：模式A = ContextBudgetPlanner 精确估算（usage 优先 + ASCII 3:1/中文 1:1 + 媒体/工具计入），模式B = 会话累计 totalTokens */
    private fun autoCompressCurrentValue(): Long =
        when (settings.value.autoCompressMode) {
            1 -> {
                sessionTotals.value.totalTokens
            }

            else -> {
                ContextBudgetPlanner
                    .estimateContextTokens(conversation.value.currentMessages)
                    .toLong()
            }
        }

    /**
     * 自动压缩检查：当前检测值超过会话级触发点（nextTriggerAt）时，
     * 不直接压缩，而是置 [pendingAutoCompressConfirm] 由 ChatPage 弹窗询问用户。
     * 如果当前正在生成（AI 流式回复中），则等待生成结束后再弹窗，
     * 避免确认弹窗盖在回复流上。
     */
    private suspend fun maybeAutoCompress(s: Settings) {
        // 模式B：未设置累计上限（0 = 不启用）时不触发
        if (s.autoCompressMode == 1 && s.autoCompressTokenLimit <= 0) return
        // 首次检查：触发点 = 当前模式阈值
        if (autoCompressNextTriggerAt == null) {
            autoCompressNextTriggerAt = autoCompressTriggerPoint(s)
        }
        val current = autoCompressCurrentValue()
        if (current > autoCompressNextTriggerAt!!) {
            // 生成中不弹窗，延后到生成结束再弹（最多等 5 分钟防死锁）
            withTimeoutOrNull(300_000L) {
                conversationJob.first { it == null || !it.isActive }
            }
            pendingAutoCompressConfirm = true
        }
    }

    /** 确认压缩：压缩对话，累计随上下文归零（聚合值自然回落），触发点回到阈值 */
    fun confirmAutoCompress() {
        pendingAutoCompressConfirm = false
        viewModelScope.launch {
            chatService
                .compressConversation(
                    _conversationId,
                    conversation.value,
                    additionalPrompt = "",
                    targetTokens = 2000,
                    keepRecentMessages = 32,
                ).onSuccess {
                    // 压缩会重写历史，缓存前缀必然失效，提示用户下一条消息缓存将重置
                    compressCacheResetNotice = true
                }.onFailure {
                    chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
                }
            autoCompressNextTriggerAt = autoCompressTriggerPoint(settings.value)
        }
    }

    /** 一次性通知：压缩完成 → 缓存前缀已重置（由 ChatPage 消费后清空） */
    var compressCacheResetNotice by mutableStateOf(false)
        private set

    fun consumeCompressCacheResetNotice() {
        compressCacheResetNotice = false
    }

    /** 取消压缩：延后触发点 = 当前累计值 + 阈值（累计统计不清零） */
    fun cancelAutoCompress() {
        autoCompressNextTriggerAt = autoCompressCurrentValue() + autoCompressTriggerPoint(settings.value)
        pendingAutoCompressConfirm = false
    }

    /** 用户重设阈值/模式：触发点 = 当前累计值 + 新阈值 */
    fun resetAutoCompressTriggerPoint(newSettings: Settings) {
        autoCompressNextTriggerAt = autoCompressCurrentValue() + autoCompressTriggerPoint(newSettings)
    }

    fun handleMessageEdit(
        parts: List<UIMessagePart>,
        messageId: Uuid,
    ) {
        if (parts.isEmptyInputMessage()) return

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
    ): Job =
        viewModelScope.launch {
            chatService
                .compressConversation(
                    _conversationId,
                    conversation.value,
                    additionalPrompt,
                    targetTokens,
                    keepRecentMessages,
                ).onFailure {
                    chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
                }
        }

    suspend fun forkMessage(message: UIMessage): Conversation =
        chatService.forkConversationAtMessage(_conversationId, message.id)

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException(context.getString(R.string.chat_stop_generation_before_delete)),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation),
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
    ) {
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        scope: me.rerere.rikkahub.service.ChatService.ApprovalScope =
            me.rerere.rikkahub.service.ChatService.ApprovalScope.Once,
        toolName: String? = null,
    ) {
        chatService.handleToolApproval(
            conversationId = _conversationId,
            toolCallId = toolCallId,
            approved = approved,
            reason = reason,
            scope = scope,
            toolName = toolName,
        )
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(
        conversation: Conversation,
        targetAssistantId: Uuid,
    ) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            // Folders are per-assistant groupings; after switching assistant the old folder is
            // not visible under the new one, so clear the assignment to avoid losing the chat.
            val updatedConversation =
                conversationFull.copy(
                    assistantId = targetAssistantId,
                    folderId = null,
                )
            // Drop any "Allow for this chat" grants the user gave the previous assistant.
            // The grants apply to a tool surface the new assistant may use very differently
            // (different prompt, different tool list), and the user authorised them under
            // the old persona's behaviour, not this one's. Persistent "Always Allow" grants
            // stay (they were granted globally) but ChatScope is reset.
            me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList
                .clearChat(conversation.id)
            if (conversation.id == _conversationId) {
                chatService.saveConversation(_conversationId, updatedConversation)
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        }
    }

    fun translateMessage(
        message: UIMessage,
        targetLanguage: Locale,
    ) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(
        conversation: Conversation,
        force: Boolean = false,
    ) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node,
                    ),
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes =
                        currentConversation.messageNodes.map { existingNode ->
                            if (existingNode.id == node.id) {
                                existingNode.copy(isFavorite = !currentlyFavorited)
                            } else {
                                existingNode
                            }
                        },
                )
            }
        }
    }

    /**
     * 调度自动任务：触发模式可多选组合。
     *  - 空闲触发（[AutoTaskConfig.modeIdle]）：会话空闲 [AutoTaskConfig.intervalSeconds] 秒后到点触发
     *  - 每日定时（[AutoTaskConfig.modeDaily]）：在 [AutoTaskConfig.dailyTimes] 每个 HH:mm 时间点触发
     *  - 任务列表（[AutoTaskConfig.useTaskList]）：每次触发按序推进任务列表，列表跑完自动停止
     * 执行内容：固定消息（[AutoTaskConfig.useFixedMessage]）与任务列表可同时勾选，每次触发发送组合内容。
     * 次数：0 = 无限，默认 100，上限 100；任务列表跑完或到达次数即自动停止。
     * 活跃监测：会话正在生成回复，或用户 60 秒内有操作时，跳过本次触发。
     */
    fun scheduleAutoTask(config: AutoTaskConfig) {
        me.rerere.rikkahub.data.log.AppLog.d(
            "AutoTask",
            "调度: idle=${config.modeIdle} daily=${config.modeDaily} taskList=${config.useTaskList} fixed=${config.useFixedMessage} " +
                "count=${config.triggerCount} interval=${config.intervalSeconds} times=${config.dailyTimes} tasks=${config.tasks.size}",
        )
        cancelAutoTask()

        val hasTriggerMode = config.modeIdle || config.modeDaily || config.useTaskList
        val hasExecContent = config.useFixedMessage || config.useTaskList
        val valid = hasTriggerMode && hasExecContent && (!config.useTaskList || config.tasks.isNotEmpty())
        if (!valid) {
            _autoTaskActive.value = false
            return
        }

        _autoTaskActive.value = true
        autoTaskJob =
            viewModelScope.launch {
                val triggerChannel = Channel<Unit>(Channel.UNLIMITED)
                val idleJob = if (config.modeIdle) launch { idleTriggerProducer(config, triggerChannel) } else null
                val dailyJob = if (config.modeDaily) launch { dailyTriggerProducer(config, triggerChannel) } else null
                try {
                    var triggered = 0
                    var taskIdx = config.taskIndex.coerceIn(0, (config.tasks.size - 1).coerceAtLeast(0))
                    var done = config.useTaskList && config.tasks.isNotEmpty() && taskIdx >= config.tasks.size
                    while (isActive && !done) {
                        triggerChannel.receive()
                        if (!isActive) break

                        // 活跃监测：会话正在生成回复，或用户 60 秒内有操作 → 跳过本次触发
                        val job = conversationJob.value
                        if (job != null && job.isActive) continue
                        if (System.currentTimeMillis() - userActivityAtMs < USER_ACTIVE_SKIP_MS) continue

                        triggered++
                        val msg = resolveAutoTaskMessage(config, taskIdx)
                        if (config.useTaskList && config.tasks.isNotEmpty()) {
                            taskIdx++
                        }
                        me.rerere.rikkahub.data.log.AppLog.d("AutoTask", "触发 #$triggered: ${msg.take(48)}")
                        handleMessageSend(
                            listOf(UIMessagePart.Text(msg)),
                            answer = true,
                            fromAutoTask = true,
                        )
                        writeAutoTaskConfig(context, config.copy(taskIndex = taskIdx))

                        // 停止条件：任务列表跑完，或达到设定次数（0 = 无限）
                        if (config.useTaskList && config.tasks.isNotEmpty() && taskIdx >= config.tasks.size) done = true
                        if (config.triggerCount > 0 && triggered >= config.triggerCount) done = true
                    }
                } finally {
                    idleJob?.cancel()
                    dailyJob?.cancel()
                    writeAutoTaskConfig(context, AutoTaskConfig())
                    _autoTaskActive.value = false
                }
            }
    }

    /** 空闲触发生产者：会话连续空闲 intervalSeconds 秒后发送一次触发事件（随后继续监听下一轮空闲） */
    private suspend fun CoroutineScope.idleTriggerProducer(
        config: AutoTaskConfig,
        triggerChannel: Channel<Unit>,
    ) {
        val interval = config.intervalSeconds.coerceIn(MIN_AUTO_TASK_IDLE_SECONDS, MAX_AUTO_TASK_IDLE_SECONDS)
        while (isActive) {
            // 等待当前生成中的回复完成
            val job = conversationJob.value
            if (job != null && job.isActive) {
                try {
                    withTimeoutOrNull(300_000L) {
                        conversationJob.first { it == null || !it.isActive }
                    }
                } catch (_: Exception) {
                }
            }
            // 会话空闲计时：空闲达设定秒数后触发
            var idleSeconds = 0
            while (idleSeconds < interval && isActive) {
                delay(1_000L)
                val currentJob = conversationJob.value
                if (currentJob != null && currentJob.isActive) {
                    idleSeconds = 0
                    continue
                }
                idleSeconds++
            }
            if (!isActive) break
            triggerChannel.send(Unit)
        }
    }

    /** 每日定时生产者：在每个 HH:mm 时间点发送一次触发事件（跨天循环） */
    private suspend fun CoroutineScope.dailyTriggerProducer(
        config: AutoTaskConfig,
        triggerChannel: Channel<Unit>,
    ) {
        val times = config.dailyTimes.mapNotNull { parseDailyTimeMinutes(it) }.distinct().sorted()
        if (times.isEmpty()) return
        while (isActive) {
            val delayMs = nextDailyTriggerDelayMs(times, System.currentTimeMillis())
            if (delayMs > 0) delay(delayMs)
            if (isActive) triggerChannel.send(Unit)
        }
    }

    /** 解析 HH:mm → 当天第几分钟；格式非法返回 null */
    private fun parseDailyTimeMinutes(hhmm: String): Int? {
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** 距下一个 HH:mm 触发点的延迟毫秒数：今天未到 → 今天；已过 → 明天 */
    private fun nextDailyTriggerDelayMs(
        timesMinutes: List<Int>,
        nowMs: Long,
    ): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        val nowMinutesOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val nowMsOfDay = nowMinutesOfDay * 60_000L + cal.get(Calendar.SECOND) * 1000L + cal.get(Calendar.MILLISECOND)
        val dayMs = 24 * 60 * 60 * 1000L
        val todayTriggerMs = timesMinutes.map { it * 60_000L }.firstOrNull { it > nowMsOfDay }
        val nextMs = todayTriggerMs ?: (timesMinutes.first() * 60_000L + dayMs)
        return nextMs - nowMsOfDay
    }

    /**
     * 取消当前调度的自动任务。
     */
    fun cancelAutoTask() {
        autoTaskJob?.cancel()
        autoTaskJob = null
        _autoTaskActive.value = false
    }
}
