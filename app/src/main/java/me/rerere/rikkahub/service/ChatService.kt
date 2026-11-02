
package me.rerere.rikkahub.service

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.FailureKind
import me.rerere.rikkahub.data.ai.classifyFailureKind
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.MediaSourceNormalizeTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.ToolPairingRepairTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

/**
 * 生成过程中把内存快照写入数据库的最小间隔（毫秒）。
 *
 * 生成期间文本/推理/工具增量只在内存累积（[updateConversation] 不落盘），进程若在生成
 * 中途被系统回收（内存压力下 LMK 的常见行为），这一轮已生成的内容会整体丢失。
 * 按此间隔落盘后，最多只丢最后一个间隔内的增量。
 */
internal const val STREAM_PERSIST_INTERVAL_MS = 8_000L

/**
 * 流式界面提交节流：数据块到达频率远高于屏幕刷新率，逐块提交会让消息列表反复重组造成卡顿。
 * 内存态按此间隔提交；每个数据块携带全量快照，跳过的中间块由下一次提交自然补上，
 * 生成结束与兜底路径仍会提交最终状态。
 */
internal const val STREAM_UI_INTERVAL_MS = 120L

/**
 * 进度通知的更新节流：更新通知要走系统跨进程调用并重绘通知栏，逐块更新在长回复下既拖慢
 * 通知栏，也容易被系统合并丢弃而停在旧内容上。按此间隔更新，内容足够接近最新。
 */
internal const val STREAM_NOTIFICATION_INTERVAL_MS = 1_000L

// 审批续跑时等待前一个生成任务结束的上限（不取消前一个，避免打断其审批写入）。
internal const val PREV_JOB_JOIN_TIMEOUT_MS = 10_000L

internal fun backgroundTextGenerationParams(
    model: Model,
    conversationId: Uuid,
    reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
): TextGenerationParams =
    TextGenerationParams(
        model = model,
        reasoningLevel = reasoningLevel,
        customHeaders = model.customHeaders,
        customBody = model.customBodies,
        sessionId = conversationId.toString(),
    )

private val forkTitleSuffixRegex = Regex("""\((\d+)\)$""")

internal fun forkConversationTitle(sourceTitle: String, existingTitles: Set<String>): String {
    val suffix = forkTitleSuffixRegex.find(sourceTitle)
    val baseTitle = suffix?.let { sourceTitle.removeRange(it.range) } ?: sourceTitle
    val start = suffix?.groupValues?.get(1)?.toIntOrNull()?.let { it + 1 } ?: 1
    return generateSequence(start) { it + 1 }.map { "$baseTitle($it)" }.first { it !in existingTitles }
}

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
    existingTitles: Set<String> = emptySet(),
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    title = forkConversationTitle(source.title, existingTitles),
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
    /**
     * 错误分类（复用生成侧同一判据 [classifyFailureKind]），供错误卡片给出**可操作建议**；
     * 分类不到（UNKNOWN）时为 null → 保持“原文兜底”，不硬塞提示。
     */
    val kind: FailureKind? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}


internal val inputTransformers by lazy {
    listOf(
        // 结构修复排最前：先把历史里未执行的工具补成合法终态，再做语义类转换
        ToolPairingRepairTransformer,
        // 媒体来源规范化：content:// 读成 data URL、http(s) 图片下载后编码，失败给明确占位（见 P103/P104/P105）
        MediaSourceNormalizeTransformer,
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        OcrTransformer,
        DocumentAsPromptTransformer,
    )
}

internal val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

/** Aux-generation kind tracked by [AuxJobRegistry]. */
internal enum class AuxJobKind { TITLE, SUGGESTION }

/**
 * Tracks the in-flight title/suggestion job per (conversation, kind) so a burst of aux
 * generations can't pile up against the same local server slot and evict the main
 * conversation's cached prompt prefix. [launch] cancels-and-replaces any previous job
 * registered for the same key; [cancelAll] cancels every job for one conversation without
 * touching another conversation's. Jobs deregister themselves on completion (success,
 * failure, or cancellation) so the map never accumulates entries for finished work.
 * Extracted as a small dependency-free class so this is unit-testable without an Android
 * runtime or a constructed [ChatService].
 */
internal class AuxJobRegistry {
    private val trackedJobs = ConcurrentHashMap<Pair<Uuid, AuxJobKind>, Job>()

    fun launch(
        scope: CoroutineScope,
        conversationId: Uuid,
        kind: AuxJobKind,
        block: suspend () -> Unit,
    ): Job {
        val key = conversationId to kind
        trackedJobs[key]?.cancel()
        // LAZY-start + register-before-start (mirrors ConversationSession.setJob) so the
        // completion handler below can never fire before the job is in the map.
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        job.invokeOnCompletion { trackedJobs.remove(key, job) }
        trackedJobs[key] = job
        job.start()
        return job
    }

    fun cancelAll(conversationId: Uuid) {
        trackedJobs.keys
            .filter { it.first == conversationId }
            .forEach { key -> trackedJobs[key]?.cancel() }
    }

    /** True while a job is registered for this key. Test-observability hook for the
     *  deregister-on-completion guarantee [launch] documents. */
    internal fun isTracked(conversationId: Uuid, kind: AuxJobKind): Boolean =
        trackedJobs.containsKey(conversationId to kind)
}

/**
 * 会话与生成的中枢：会话状态装配、消息发送与排队、生成调度、工具审批与清理。
 *
 * 本文件很长（2500+ 行）。**阅读时请按下面的段落名检索符号，不要依赖行号**（行号会随改动漂移）：
 *  - 会话与状态：[getOrCreateSession] / [ensureHydrated] / [persistSnapshotBeforeEvict] / [removeSession] / [dropSession]
 *  - 错误提示：[addError] / [dismissError] / [clearAllErrors]
 *  - 发送与排队：[sendMessage]（忙则入队）/ [sendMessageNow]（真正发起）/ [dispatchNextQueuedMessage]（本轮结束派发队首）/ [messageQueueState]
 *  - 生成装配：generate 系列 + 工具面装配（调用 [me.rerere.rikkahub.data.ai.tools.ChatToolFactory]）
 *  - 审批与停止：工具批准流程 / stopGeneration / 取消与重放安全
 *  - 引用计数与清理：[addConversationReference] / [removeConversationReference] / [launchAuxJob] / [cleanup]
 *
 * 相关文件：工具装配 `data/ai/tools/ChatToolFactory.kt`；生成循环 `data/ai/GenerationLoop.kt`。
 */
class ChatService(
    internal val context: Application,
    internal val appScope: AppScope,
    internal val appEventBus: AppEventBus,
    internal val settingsStore: SettingsStore,
    internal val conversationRepo: ConversationRepository,
    internal val memoryRepository: MemoryRepository,
    internal val generationLoop: GenerationLoop,
    internal val chatToolFactory: ChatToolFactory,
    internal val templateTransformer: TemplateTransformer,
    internal val providerManager: ProviderManager,
    internal val localTools: LocalTools,
    val mcpManager: McpManager,
    internal val filesManager: FilesManager,
    internal val skillManager: SkillManager,
    internal val toolApprovalPreferences: me.rerere.rikkahub.data.preferences.ToolApprovalPreferences,
    internal val workspaceRepository: WorkspaceRepository,
    internal val folderRepository: FolderRepository,
    internal val compressedArchiveDao: me.rerere.rikkahub.data.db.dao.CompressedArchiveDao,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    internal val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    internal val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // Title/suggestion aux-generation jobs, keyed per conversation - see AuxJobRegistry.
    internal val auxJobs = AuxJobRegistry()
    internal val titleFailureLock = Any()
    internal val titleFailureReportedAtMs = mutableMapOf<Uuid, Long>()

    /**
     * Per-conversation mutex serialising state-mutating operations: handleToolApproval,
     * stopGeneration, the chunk-handling save path, and explicit DB writes. Without this
     * the audit reports identified multiple write races where a fresh approval mutation
     * gets clobbered by a concurrent write from a stale snapshot. Generation chunks
     * themselves are NOT held under this mutex — only the persist boundaries.
     */
    private val sessionMutexes = ConcurrentHashMap<Uuid, Mutex>()

    internal fun str(id: Int, vararg args: Any): String = if (args.isEmpty()) context.resources.getString(id) else context.resources.getString(id, *args)

    internal fun mutexFor(conversationId: Uuid): Mutex = sessionMutexes.getOrPut(conversationId) { Mutex() }

    /**
     * Hydrate the in-memory session for [conversationId] from disk if it's currently
     * blank. Used by entry points (callback handlers, approval handlers) that may be hit
     * after a process restart with an empty session map — without this they read an
     * empty Conversation, mutate it, and `saveConversation` then OVERWRITES the persisted
     * state with empty content (silent data loss). Idempotent and cheap when the session
     * is already populated.
     */
    suspend fun ensureHydrated(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.state.value.messageNodes
                .isEmpty()
        ) {
            val fromDb = conversationRepo.getConversationById(conversationId) ?: return
            if (fromDb.messageNodes.isNotEmpty()) {
                session.state.value = fromDb
            }
        }
    }

    // 错误状态
    private val errorHelper = ChatErrorHelper()
    val errors: StateFlow<List<ChatError>> = errorHelper.errors

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) = errorHelper.addError(error, conversationId, title, solution)

    fun dismissError(id: Uuid) = errorHelper.dismissError(id)

    fun clearAllErrors() = errorHelper.clearAllErrors()

    // 每会话待发送队列：生成中再次发送时消息排队，当前生成结束后按序自动发出
    // （详见 MessageQueue 的语义说明）。
    internal val messageQueues = ConcurrentHashMap<Uuid, MessageQueue>()

    /** 供 UI 观察某会话的待发送队列。 */
    fun messageQueueState(conversationId: Uuid): StateFlow<MessageQueueState> =
        messageQueues.getOrPut(conversationId) { MessageQueue() }.state

    /**
     * 语音模式：投递一条语音消息到聊天队列，返回「本轮助手回复文本」的等待句柄。
     *
     * 与普通发送的两点不同：① 即使当前空闲也先入队，保证语音的提交顺序与朗读顺序一致；
     * ② 本轮结束后把回复文本交给调用方（由语音去朗读），不再发普通自动播报事件，避免重复。
     */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val reply = CompletableDeferred<String?>()
        messageQueues.getOrPut(conversationId) { MessageQueue() }
            .enqueue(listOf(UIMessagePart.Text(text)), answer = true, reply = reply)
        dispatchNextQueuedMessage(conversationId)
        return reply
    }

    /** 取最近一条助手回复文本（语音朗读用）；无回复时返回 null。 */
    internal fun collectAssistantReply(conversationId: Uuid): String? =
        getConversationFlow(conversationId).value.currentMessages
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.toText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** 撤销一条待发送消息，并清理它独占的附件。 */
    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        messageQueues[conversationId]?.remove(messageId)?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    /** 把一条待发送消息取回输入框编辑（编辑期间该条目不派发）。 */
    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        messageQueues[conversationId]?.beginEdit(messageId)

    /** 结束编辑：[parts] 为 null 表示放弃编辑（仅释放占位）。 */
    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null,
    ) {
        messageQueues[conversationId]?.finishEdit(messageId, parts)?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    /** 恢复被暂停的队列（生成失败/主动停止后由用户确认继续发送）。 */
    fun resumeMessageQueue(conversationId: Uuid) {
        messageQueues[conversationId]?.resume()
        dispatchNextQueuedMessage(conversationId)
    }

    /**
     * 撤销或改写排队消息后，清理它独占的本地附件。
     *
     * 三重确认后才删：① 内存中的会话与队列都不再引用；② 数据库里任何会话都查不到引用
     * （未打开的会话、未选中的分支也可能引用同一附件）；③ 查询挂起期间重新读一次内存。
     * 任何一步无法确认都保留文件 —— 宁可留垃圾，也不误删用户数据。
     */
    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                val persistedReferences =
                    candidates.filter { conversationRepo.hasFileReference(it) }.toSet()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = sessions.values.map { it.state.value },
                    pendingMessages = messageQueues.values.flatMap { it.state.value.messages },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "cleanupQueuedAttachments failed, keep files", e)
            }
        }
    }

    // 生成完成流
    internal val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // 后台期间正在刷新的进度通知所属会话；回到前台时统一清掉，避免通知栏残留与页面对不上的旧内容
    private val liveNotificationConversations = ConcurrentHashMap.newKeySet<Uuid>()

    internal val notificationHelper =
        ChatNotificationHelper(
            context = context,
            getConversation = { getConversationFlow(it).value },
            liveConversations = liveNotificationConversations,
        )

    // 前台状态管理
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver =
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    _isForeground.value = true
                    // 用户已回到界面：清掉后台期间的进度通知，否则它会带着停止更新时的旧内容
                    // 一直留在通知栏，与页面上的最新内容不一致。
                    notificationHelper.cancelAllLiveNotifications()
                }

                Lifecycle.Event.ON_STOP -> {
                    _isForeground.value = false
                }

                else -> {}
            }
        }

    private val floatingOverlayController by lazy(LazyThreadSafetyMode.NONE) {
        ChatFloatingOverlayController(context, appScope, settingsStore, this)
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        floatingOverlayController.start()
    }

    fun cleanup() =
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
            floatingOverlayController.close()
            sessions.values.forEach { it.cleanup() }
            sessions.clear()
            sessionMutexes.clear()
            // Only the in-memory view is dropped here; the persisted unlocked-tool set is kept
            // on purpose so a process restart cannot regress a cold tool back to an empty schema
            // (that would invalidate the provider's prefix cache for the whole conversation).
            me.rerere.rikkahub.data.ai.tools.ToolSurfaceSession.clearAllInMemory()
        }.onFailure {
            // Don't let a teardown hiccup escape, but don't swallow it silently either —
            // a failure here can leave the lifecycle observer registered (slow leak).
            AppLog.w(TAG, "cleanup failed", it)
        }

    // ---- Session 管理 ----

    internal fun getOrCreateSession(conversationId: Uuid): ConversationSession =
        sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial =
                    Conversation.ofId(
                        id = id,
                        assistantId = settings.getCurrentAssistant().id,
                    ),
                scope = appScope,
                onIdle = { removeSession(it) },
            ).also {
                _sessionsVersion.value++
                AppLog.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }

    /**
     * 会话被回收前，把内存中的最新快照落盘。
     *
     * 生成期间的落盘是 8 秒节流的（见 STREAM_PERSIST_INTERVAL_MS）；若生成刚结束就发生
     * idle 回收（切走会话 → 引用归零 → 5 秒宽限 → 回收），最后一次节流之后的内容只存在于
     * 内存会话里，会随回收一起消失（表现为「切走再回来内容变少」）。这里在移除前补一次
     * NonCancellable 落盘，保证内存与磁盘一致。
     *
     * 三条纪律（P2-lite）：
     * 1. **互斥在启动协程之前取**：`removeSession` 紧接着会 `sessionMutexes.remove(id)`，
     *    若等协程真跑起来再 `mutexFor()`，拿到的是另一个新 Mutex 实例，与并发写入者互不串行
     *    （旧快照仍能盖掉新写）；
     * 2. **拒绝旧写**：快照是「回收那一刻」抓的，可能已落后于刚落库的版本。写库前比
     *    `Conversation.updateAt`（每轮收尾才刷新一次），落库版本更新就跳过，不覆盖；
     * 3. **不把会话建回来**：`saveConversation` 默认会同步内存（`updateConversation` →
     *    `getOrCreateSession`），而回收路径此刻刚把会话移出 map，同步会把它重建成一个空会话
     *    （随后又触发一次 idle 回收），故这里传 `syncMemory = false`。
     */
    private fun persistSnapshotBeforeEvict(conversationId: Uuid, session: ConversationSession) {
        val snapshot = session.state.value
        // 必须在 removeSession / dropSession 清掉 map 条目之前拿到互斥量（纪律 1）
        val mutex = mutexFor(conversationId)
        appScope.launch {
            withContext(NonCancellable) {
                runCatching {
                    mutex.withLock {
                        val stored = conversationRepo.getConversationById(conversationId)
                        if (stored != null && stored.updateAt > snapshot.updateAt) {
                            AppLog.i(
                                TAG,
                                "persist before evict skipped (stored is newer): $conversationId " +
                                    "stored=${stored.updateAt} snapshot=${snapshot.updateAt}",
                            )
                            return@withLock
                        }
                        saveConversation(conversationId, snapshot, syncMemory = false)
                    }
                }.onFailure { AppLog.w(TAG, "persist before evict failed: $conversationId", it) }
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            AppLog.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            persistSnapshotBeforeEvict(conversationId, session)
            session.cleanup()
            // Evict the per-conversation mutex so it doesn't accumulate forever.
            // dropSession() already removes it; removeSession() (idle eviction path)
            // was previously missing this cleanup, causing a slow leak on heavy-use
            // sessions where many conversations cycle in and out of memory.
            sessionMutexes.remove(conversationId)
            // Deliberately NOT clearing ToolSurfaceSession here: idle eviction is not a reset.
            // Dropping the unlocked-tool set would shrink cold tools' schemas on the next visit,
            // breaking the provider prefix cache and re-pricing the entire history.
            _sessionsVersion.value++
            AppLog.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    /**
     * Force-drop the in-memory session for [conversationId] regardless of refcount /
     * generation status. Used by /new in TelegramBotService to make sure a straggler
     * coroutine writing back to the session can't resurrect the conversation after the
     * user reset it. Safe to call when no session exists — no-op.
     */
    fun dropSession(conversationId: Uuid) {
        val session = sessions.remove(conversationId) ?: return
        persistSnapshotBeforeEvict(conversationId, session)
        session.cleanup()
        sessionMutexes.remove(conversationId)
        // Unlike idle eviction (removeSession), /new genuinely resets the conversation content,
        // so the cached prefix is rebuilt anyway — clearing the unlocked-tool set here only
        // frees memory and cannot cause a cache regression.
        me.rerere.rikkahub.data.ai.tools.ToolSurfaceSession.clear(conversationId.toString())
        _sessionsVersion.value++
        AppLog.i(TAG, "dropSession: $conversationId (remaining: ${sessions.size})")
    }

    /**
     * 删除会话的统一入口：先停生成（含通知接入服务端取消）并丢弃内存会话态，再删库。
     *
     * 顺序不可颠倒：若先删库、而流仍在推，后续 [saveConversation] 会因「会话不存在且非空」
     * 把会话重新插回去（用户看到的是「删了又自己回来」）。接入路径尤其必须停远端会话，
     * 否则服务端会继续跑并把内容写回。
     */
    suspend fun deleteConversation(conversationId: Uuid) {
        runCatching { stopGeneration(conversationId) }
            .onFailure { AppLog.w(TAG, "deleteConversation: stopGeneration failed for $conversationId", it) }
        dropSession(conversationId)
        conversationRepo.getConversationById(conversationId)?.let { conversationRepo.deleteConversation(it) }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    /**
     * Launches a title/suggestion (aux) job through [auxJobs]: launching the same [kind] for
     * [conversationId] again cancels-and-replaces the previous one, and a new main generation
     * can cancel every aux job for that conversation via [AuxJobRegistry.cancelAll]. Keeps the
     * same conversation-reference accounting the other launched jobs use.
     */
    internal fun launchAuxJob(
        conversationId: Uuid,
        kind: AuxJobKind,
        block: suspend () -> Unit,
    ): Job = auxJobs.launch(appScope, conversationId, kind) {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> = getOrCreateSession(conversationId).state

    /** Reacquired overlay sessions may have been evicted while permission was absent. */
    internal suspend fun hydrateFloatingConversation(conversationId: Uuid) {
        ensureHydrated(conversationId)
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getFloatingReplyMessageIdFlow(conversationId: Uuid): StateFlow<Uuid?> =
        getOrCreateSession(conversationId).floatingReplyMessageId

    fun getFloatingGenerationStartedAtFlow(conversationId: Uuid): StateFlow<Long?> =
        getOrCreateSession(conversationId).floatingGenerationStartedAt

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> =
        _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    currentSessions.map { s ->
                        s.generationJob.map { job -> s.id to job }
                    },
                ) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation =
                Conversation
                    .ofId(
                        id = conversationId,
                        assistantId = assistant.id,
                        newConversation = true,
                    ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
    ) {
        if (content.isEmptyInputMessage()) return

        // 生成中再次发送：入队而不是打断当前生成，待本轮结束后按序发出。
        // 这样「发送键在生成中依然可用」，用户无需等待或手动停止。
        val session = getOrCreateSession(conversationId)
        // 已取消的 job 不再算“正在生成”：取消后 job 仍会跑一小段收尾（isActive 仍为 true），
        // 若把它算作忙，用户“打断后立刻重发”就会被送进队列而不是直接发出。
        val activeGeneration = session.getJob()?.takeIf { it.isActive && !it.isCancelled }
        val hasPendingTools = session.state.value.messageNodes.any { node ->
            node.currentMessage.getTools().any { it.isPending }
        }
        if (shouldQueueChatMessage(
                isGenerating = activeGeneration != null,
                hasPendingTools = hasPendingTools,
            )
        ) {
            val queue = messageQueues.getOrPut(conversationId) { MessageQueue() }
            queue.enqueue(content, answer)
            AppLog.i(
                TAG,
                "msg-queued conv=$conversationId pending=${queue.size}",
            )
            // The finishing job may have checked an empty queue just before
            // enqueue(). Retry dispatch after that job fully completes so this
            // message cannot be stranded in the completion race.
            if (activeGeneration != null) {
                scheduleQueueDispatchAfter(activeGeneration, appScope) {
                    dispatchNextQueuedMessage(conversationId)
                }
            }
            return
        }
        sendMessageNow(conversationId, content, answer)
    }

    internal fun dispatchNextQueuedMessage(
        conversationId: Uuid,
        fromJob: Job? = null,
    ) = dispatchNextQueuedMessageImpl(conversationId, fromJob)

    internal fun sendMessageNow(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        reply: CompletableDeferred<String?>? = null,
        requeueOnFailure: QueuedMessage? = null,
    ) = sendMessageNowImpl(conversationId, content, answer, reply, requeueOnFailure)

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
    ) = regenerateAtMessageImpl(conversationId, message, regenerateAssistantMsg)


    /** Scope of an "approve" decision. Once = this single tool call only. ChatScope =
     *  every future call of the same tool name in this conversation (until /new). Always =
     *  every future call of this tool name across the whole app, persisted to disk. */
    enum class ApprovalScope { Once, ChatScope, Always }

    /**
     * T12 / B5: 保留区消息里工具**输出**与**入参**超过 toolOutputMaxChars 时截断为预览。
     *
     * 入参同样是 token 大头（长文件内容、批量编辑、整段脚本常以入参形式留在历史里），
     * 因此与输出用同一上限分别截断。只在“已被压缩、进入保留区”的历史上生效，
     * 不影响当轮实时调用，也不改动缓存可见的请求前缀之外的内容。
     */
    /**
     * 压缩后保留消息的工具输出截断。
     *
     * [compactTools] 为助手级「紧凑输出」名单：命中者用 [compactMaxChars]，其余用 [maxChars] ——
     * 与生成期 `maybeTruncateToolOutput` 的口径保持一致。
     */
    // ---- 对话状态更新 ----

    internal fun updateConversation(
        conversationId: Uuid,
        conversation: Conversation,
    ) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
    ) {
        // Atomic compare-and-set via StateFlow.update so two concurrent writers can't
        // race on read-modify-write (each reading the SAME pre-state and overwriting
        // each other). Also routes through checkFilesDelete so attached files keep
        // being garbage-collected when removed from the conversation.
        val session = getOrCreateSession(conversationId)
        session.state.update { current ->
            val next = update(current)
            if (next.id != conversationId) {
                current
            } else {
                checkFilesDelete(next, current)
                next
            }
        }
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(
        conversationId: Uuid,
        folderId: Uuid?,
    ) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean =
        sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    suspend fun saveConversation(
        conversationId: Uuid,
        conversation: Conversation,
        syncMemory: Boolean = true,
        syncFts: Boolean = true,
    ) = saveConversationImpl(conversationId, conversation, syncMemory, syncFts)

    suspend fun stopGeneration(conversationId: Uuid) = stopGenerationImpl(conversationId)
}
