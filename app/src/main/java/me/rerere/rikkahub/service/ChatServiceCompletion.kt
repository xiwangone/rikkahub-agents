
package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.core.Tool
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.abandonUnexecutedTools
import me.rerere.rikkahub.data.ai.INTERRUPTED_UNKNOWN_OUTCOME_REASON
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.common.android.Logging
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.FailureDiagnosis
import me.rerere.rikkahub.data.ai.diagnoseFailure
import me.rerere.rikkahub.data.ai.tools.InvalidMcpServerNamesException
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.ai.tools.ToolErrors
import me.rerere.rikkahub.data.ai.tools.ToolUsageTracker
import me.rerere.rikkahub.data.ai.tools.AppScene
import me.rerere.rikkahub.data.preferences.isWorkspaceToolName
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.generationPhase
import java.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "ChatServiceCompletion"


fun ChatService.handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
        scope: ChatService.ApprovalScope = ChatService.ApprovalScope.Once,
        toolName: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        val convMutex = mutexFor(conversationId)
        val priorGenerationJob = session.getJob()

        if (approved && toolName != null && scope != ChatService.ApprovalScope.Once) {
            persistToolApprovalScopeGrant(conversationId, toolName, scope)
        }

        val job =
            appScope.launch {
                try {
                    runCatching {
                        convMutex.withLock {
                            ensureHydrated(conversationId)
                            priorGenerationJob?.let { prev ->
                                runCatching { withTimeoutOrNull(PREV_JOB_JOIN_TIMEOUT_MS) { prev.join() } }
                            }
                            applyToolApprovalDecisionInLock(
                                conversationId = conversationId,
                                session = session,
                                toolCallId = toolCallId,
                                approved = approved,
                                reason = reason,
                                answer = answer,
                            )
                        }
                        if (!hasAnyPendingTools(session.state.value)) {
                            handleMessageComplete(conversationId)
                        }
                        _generationDoneFlow.emit(conversationId)
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                        if (e is Exception) {
                            addError(e, conversationId, title = str(R.string.error_title_tool_approval))
                        } else {
                            throw e
                        }
                    }
                } finally {
                    dispatchNextQueuedMessage(conversationId, fromJob = coroutineContext[Job])
                }
            }

        session.setJob(job, cancelPrevious = false)
    }

private fun ChatService.persistToolApprovalScopeGrant(
    conversationId: Uuid,
    toolName: String,
    scope: ChatService.ApprovalScope,
) {
    appScope.launch(NonCancellable) {
        runCatching {
            when (scope) {
                ChatService.ApprovalScope.ChatScope -> {
                    me.rerere.rikkahub.data.ai.tools
                        .ToolApprovalAllowList
                        .grantForChat(conversationId, toolName)
                }

                ChatService.ApprovalScope.Always -> {
                    grantAlwaysScope(conversationId, toolName)
                }

                ChatService.ApprovalScope.Once -> Unit
            }
        }.onFailure { AppLog.e(TAG, "approval grant write failed", it) }
    }
}

private suspend fun ChatService.applyToolApprovalDecisionInLock(
    conversationId: Uuid,
    session: ConversationSession,
    toolCallId: String,
    approved: Boolean,
    reason: String,
    answer: String?,
) {
    val conversation = session.state.value
    val newApprovalState =
        when {
            answer != null -> ToolApprovalState.Answered(answer)
            approved -> ToolApprovalState.Approved
            else -> ToolApprovalState.Denied(reason)
        }

    var foundActivePending = false
    var answeredToolName: String? = null
    val updatedNodes =
        conversation.messageNodes.map { node ->
            node.copy(
                messages =
                    node.messages.map { msg ->
                        msg.copy(
                            parts =
                                msg.parts.map { part ->
                                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId && part.isPending) {
                                        foundActivePending = true
                                        answeredToolName = part.toolName
                                        part.copy(approvalState = newApprovalState)
                                    } else {
                                        part
                                    }
                                },
                        )
                    },
            )
        }
    if (!foundActivePending) return
    val updatedConversation = conversation.copy(messageNodes = updatedNodes)
    saveConversation(conversationId, updatedConversation)

    val answeredName = answeredToolName
    if (answer != null && answeredName != null) {
        ToolUsageTracker.record(
            context = context,
            name = answeredName,
            durationMs = 0,
            failed = false,
        )
    }
}

private fun hasAnyPendingTools(conversation: Conversation): Boolean =
    conversation.messageNodes.any { node ->
        node.currentMessage.parts.any { part ->
            part is UIMessagePart.Tool && part.isPending
        }
    }

    // ---- 处理消息补全 ----

private data class ResolvedCompletionModel(
    val settings: me.rerere.rikkahub.data.datastore.Settings,
    val initialConversation: Conversation,
    val assistant: me.rerere.rikkahub.data.model.Assistant,
    val model: me.rerere.ai.provider.Model,
    val senderName: String,
)

private suspend fun ChatService.resolveCompletionModel(conversationId: Uuid): ResolvedCompletionModel {
    val settings = settingsStore.settingsFlow.first()
    val initialConversation = getConversationFlow(conversationId).value
    val assistant =
        settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
    val conversationModelId =
        initialConversation.chatModelId.takeIf { assistant.allowPerConversationModel }
    val model =
        settings.findModelById(
            conversationModelId ?: assistant.chatModelId ?: settings.chatModelId,
        )
            ?: throw IllegalStateException(
                "No chat model selected. Pick one in Settings → Default models, or send /model in Telegram.",
            )
    val resolvedProvider =
        model.findProvider(settings.providers)
            ?: throw IllegalStateException(
                "Selected model '${model.displayName.ifBlank { model.modelId }}' has no matching provider. " +
                    "Pick a different model in Settings or with /model.",
            )
    if (!resolvedProvider.enabled) {
        throw IllegalStateException(
            "Provider '${resolvedProvider.name}' is disabled — refusing to send. " +
                "Re-enable it in Settings → Providers, or pick a different model with /model.",
        )
    }
    val senderName =
        if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { str(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
    return ResolvedCompletionModel(
        settings = settings,
        initialConversation = initialConversation,
        assistant = assistant,
        model = model,
        senderName = senderName,
    )
}

internal suspend fun ChatService.handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
    ) {
        val resolved = resolveCompletionModel(conversationId)
        val settings = resolved.settings
        val assistant = resolved.assistant
        val model = resolved.model
        val senderName = resolved.senderName

        var retryDiagnosis: FailureDiagnosis? = null
        runCatching {
            updateConversation(conversationId, resolved.initialConversation.copy(chatSuggestions = emptyList()))
            warnIfModelLacksTools(conversationId, assistant, model)
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value
            val tools = buildToolsForCompletion(conversationId, settings, assistant, model, conversation) ?: return
            runGenerationStream(
                conversationId = conversationId,
                settings = settings,
                assistant = assistant,
                model = model,
                senderName = senderName,
                conversation = conversation,
                messageRange = messageRange,
                tools = tools,
                onRetryDiagnosed = { retryDiagnosis = it },
            )
        }.onFailure {
            handleMessageCompleteFailure(conversationId, it)
        }.onSuccess {
            handleMessageCompleteSuccess(conversationId, retryDiagnosis)
        }
    }

private suspend fun ChatService.warnIfModelLacksTools(
    conversationId: Uuid,
    assistant: me.rerere.rikkahub.data.model.Assistant,
    model: me.rerere.ai.provider.Model,
) {
    if (!model.abilities.contains(ModelAbility.TOOL)) {
        if (shouldUseExternalWebSearch(assistant.enableWebSearch, model) || mcpManager.getAllAvailableTools().isNotEmpty()) {
            addError(
                IllegalStateException(str(R.string.tools_warning)),
                conversationId,
                title = str(R.string.error_title_tool_unavailable),
            )
        }
    }
}

private suspend fun ChatService.buildToolsForCompletion(
    conversationId: Uuid,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    assistant: me.rerere.rikkahub.data.model.Assistant,
    model: me.rerere.ai.provider.Model,
    conversation: Conversation,
): List<Tool>? {
    val invocationCtx =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
            callerAssistantId = assistant.id.toString(),
            callerConversationId = conversationId.toString(),
            isHeadless =
                me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                    .isHeadless(conversationId),
            modelCanSeeImages = Modality.IMAGE in model.inputModalities,
        )
    return try {
        chatToolFactory.createTools(
            settings = settings,
            assistant = assistant,
            model = model,
            invocationCtx = invocationCtx,
            workspaceCwd = conversation.workspaceCwd,
            workspaceIdOverride = conversation.workspaceIdOverride?.toString(),
            toolScopeOverride = conversation.toolScopeOverride,
            uiScene = AppScene.current,
        )
    } catch (error: InvalidMcpServerNamesException) {
        addError(
            error = IllegalStateException(
                str(
                    R.string.error_mcp_invalid_server_name,
                    error.names.joinToString(", "),
                ),
            ),
            conversationId = conversationId,
        )
        null
    }
}

private suspend fun ChatService.isToolAutoApprovedForConversation(
    conversationId: Uuid,
    toolName: String,
): Boolean =
    if (toolName == "ask_user") {
        me.rerere.rikkahub.data.ai.tools.HeadlessConversations
            .shouldAutoApprove(conversationId)
    } else {
        toolApprovalPreferences.currentYolo() ||
            me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                .shouldAutoApprove(conversationId) ||
            me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList
                .isAllowedForChat(conversationId, toolName) ||
            (!isWorkspaceToolName(toolName) &&
                toolApprovalPreferences.current().contains(toolName))
    }

// 节流状态容器：字段虽多（时间戳 / 后台任务 / 完成标记）但由同一组节流逻辑一起读写，
// 拆成子类会引入无内聚的层级，故此处保持平铺。
@Suppress("LongParameterList")
private class StreamThrottleState(
    // 初始化为流启动时刻而非 0L，避免收到首个增量（刚吐出几个字）时立即触发同步落库阻塞流式读取
    var lastStreamPersistAtMs: Long = System.currentTimeMillis(),
    var lastUiCommitAtMs: Long = 0L,
    var lastNotificationAtMs: Long = 0L,
    @Volatile var pendingUiConversation: Conversation? = null,
    var floatingReplyMessageId: Uuid? = null,
    var trailingUiFlushJob: Job? = null,
    var periodicPersistJob: Job? = null,
    @Volatile var streamCompleted: Boolean = false,
)

private suspend fun ChatService.runGenerationStream(
    conversationId: Uuid,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    assistant: me.rerere.rikkahub.data.model.Assistant,
    model: me.rerere.ai.provider.Model,
    senderName: String,
    conversation: Conversation,
    messageRange: ClosedRange<Int>?,
    tools: List<Tool>,
    onRetryDiagnosed: (FailureDiagnosis) -> Unit,
) {
    val session = getOrCreateSession(conversationId)
    val streamState = StreamThrottleState()
    GenerationForegroundService.acquire(context, conversationId.toString())
    val memories =
        if (assistant.useGlobalMemory) {
            memoryRepository.getCoreMemoriesOfAssistant(me.rerere.rikkahub.data.repository.MemoryRepository.GLOBAL_MEMORY_ID)
        } else {
            memoryRepository.getCoreMemoriesOfAssistant(assistant.id.toString())
        }
    generationLoop
        .generateText(
            settings = settings,
            model = model,
            maxSteps =
                conversation.maxToolStepsOverride
                    ?: me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits.maxToolSteps,
            processingStatus = session.processingStatus,
            onRetryDiagnosed = onRetryDiagnosed,
            systemAddendum =
                me.rerere.rikkahub.data.ai.tools
                    .ConversationSystemAddendum
                    .get(conversationId),
            isToolAutoApproved = { toolName -> isToolAutoApprovedForConversation(conversationId, toolName) },
            // 生成期间用户又发了消息：当前 step 结束就收尾（turn-end 派发新一轮），
            // 不必等整轮 tool-call 循环跑完；只查不取，消费交给 dispatchNextQueuedMessage。
            hasQueuedMessages = { (messageQueues[conversationId]?.size ?: 0) > 0 },
            messages =
                conversation.currentMessages.let {
                    if (messageRange != null) it.subList(messageRange.start, messageRange.endInclusive + 1) else it
                },
            assistant = assistant,
            conversationSystemPrompt = conversation.customSystemPrompt,
            conversationId = conversationId,
            conversationModeInjectionIds = conversation.modeInjectionIds,
            conversationLorebookIds = conversation.lorebookIds,
            workspaceCwd = conversation.workspaceCwd,
            memories = memories,
            inputTransformers =
                buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
            outputTransformers = outputTransformers,
            tools = tools,
        ).onCompletion { cause ->
            onGenerationStreamCompletion(conversationId, settings, senderName, streamState, cause)
        }.collect { chunk ->
            onGenerationStreamChunk(conversationId, settings, senderName, streamState, chunk)
        }
}

private suspend fun ChatService.onGenerationStreamCompletion(
    conversationId: Uuid,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    senderName: String,
    streamState: StreamThrottleState,
    cause: Throwable?,
) {
    notificationHelper.cancelLiveUpdateNotification(conversationId)
    GenerationForegroundService.release(context, conversationId.toString())

    withContext(NonCancellable) {
        streamState.streamCompleted = true
        streamState.trailingUiFlushJob?.cancel()
        streamState.trailingUiFlushJob = null
        runCatching { streamState.periodicPersistJob?.join() }
        streamState.periodicPersistJob = null
        streamState.pendingUiConversation?.let { latest ->
            streamState.pendingUiConversation = null
            updateConversation(conversationId, latest)
        }
    }

    val updatedConversation =
        getConversationFlow(conversationId).value.copy(
            messageNodes =
                getConversationFlow(conversationId).value.messageNodes.map { node ->
                    node.copy(messages = node.messages.map { it.finishReasoning() })
                },
            updateAt = Instant.now(),
        )
    if (cause == null) {
        val lastAssistant =
            updatedConversation.messageNodes.lastOrNull()?.currentMessage
                ?.takeIf { it.role == MessageRole.ASSISTANT }
        if (lastAssistant != null && lastAssistant.finishedAt != null && lastAssistant.finishReason == null) {
            AppLog.w(TAG, "turn ended without finishReason on the final assistant message (possible remote abort/truncation)")
        }
    }
    updateConversation(conversationId, updatedConversation)
    dispatchCompletionNotification(conversationId, settings, senderName, updatedConversation, streamState.floatingReplyMessageId, cause)
}

private fun ChatService.dispatchCompletionNotification(
    conversationId: Uuid,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    senderName: String,
    updatedConversation: Conversation,
    floatingReplyMessageId: Uuid?,
    cause: Throwable?,
) {
    if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
        val reply =
            floatingReplyMessageId?.let { wanted ->
                updatedConversation.messageNodes.asSequence()
                    .flatMap { it.messages.asSequence() }
                    .firstOrNull { it.id == wanted && it.role == MessageRole.ASSISTANT }
            }
        val phase = reply?.generationPhase() ?: me.rerere.rikkahub.data.model.GenerationPhase.Waiting
        when (
            me.rerere.rikkahub.data.model.completionNotification(
                completedNormally = cause == null,
                hasCurrentTurnReply = floatingReplyMessageId != null && reply != null,
                phase = phase,
            )
        ) {
            me.rerere.rikkahub.data.model.CompletionNotification.COMPLETED ->
                notificationHelper.sendGenerationDoneNotification(conversationId, senderName, floatingReplyMessageId!!)
            me.rerere.rikkahub.data.model.CompletionNotification.NEEDS_APPROVAL ->
                notificationHelper.sendNeedsApprovalNotification(conversationId, senderName, floatingReplyMessageId!!)
            me.rerere.rikkahub.data.model.CompletionNotification.NEEDS_ANSWER ->
                notificationHelper.sendNeedsAnswerNotification(conversationId, senderName, floatingReplyMessageId!!)
            me.rerere.rikkahub.data.model.CompletionNotification.NONE -> Unit
        }
    } else if (isForeground.value) {
        notificationHelper.cancelLiveUpdateNotification(conversationId)
        notificationHelper.cancelDoneNotification(conversationId)
    }
}

private suspend fun ChatService.onGenerationStreamChunk(
    conversationId: Uuid,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    senderName: String,
    streamState: StreamThrottleState,
    chunk: GenerationChunk,
) {
    when (chunk) {
        is GenerationChunk.UsageIncurred -> {
            runCatching {
                settingsStore.accumulateConvUsage(conversationId.toString(), chunk.usage)
            }
        }

        is GenerationChunk.Messages -> {
            chunk.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.let {
                streamState.floatingReplyMessageId = it.id
                getOrCreateSession(conversationId).recordFloatingReply(it.id)
            }
            val currentConversation = getConversationFlow(conversationId).value
            val nowUiMs = System.currentTimeMillis()
            val timeDueForUi = nowUiMs - streamState.lastUiCommitAtMs >= STREAM_UI_INTERVAL_MS
            val completedToolOutput =
                if (timeDueForUi) {
                    false
                } else {
                    hasCompletedToolOutput(currentConversation.currentMessages, chunk.messages)
                }
            val updatedConversation = currentConversation.updateCurrentMessages(chunk.messages)
            streamState.pendingUiConversation = updatedConversation
            if (shouldCommitStreamUiUpdate(
                    nowMs = nowUiMs,
                    lastCommitAtMs = streamState.lastUiCommitAtMs,
                    intervalMs = STREAM_UI_INTERVAL_MS,
                    completedToolOutput = completedToolOutput,
                )
            ) {
                streamState.trailingUiFlushJob?.cancel()
                streamState.trailingUiFlushJob = null
                streamState.lastUiCommitAtMs = nowUiMs
                streamState.pendingUiConversation = null
                updateConversation(conversationId, updatedConversation)
            } else if (streamState.trailingUiFlushJob?.isActive != true) {
                val waitMs = (STREAM_UI_INTERVAL_MS - (nowUiMs - streamState.lastUiCommitAtMs)).coerceAtLeast(1L)
                streamState.trailingUiFlushJob = appScope.launch {
                    kotlinx.coroutines.delay(waitMs)
                    if (!streamState.streamCompleted) {
                        streamState.pendingUiConversation?.let { pending ->
                            streamState.pendingUiConversation = null
                            streamState.lastUiCommitAtMs = System.currentTimeMillis()
                            updateConversation(conversationId, pending)
                        }
                    }
                }
            }

            val needsImmediatePersist = hasToolTransitionNeedingImmediatePersist(chunk.messages.lastOrNull())
            val nowMs = System.currentTimeMillis()
            val dueForPeriodicPersist = nowMs - streamState.lastStreamPersistAtMs >= STREAM_PERSIST_INTERVAL_MS
            if (needsImmediatePersist) {
                streamState.lastStreamPersistAtMs = nowMs
                // 工具审批状态跃迁需立即同步落盘，确保挂起等待用户审批前状态已持久化
                saveConversation(conversationId, updatedConversation, syncFts = false)
            } else if (dueForPeriodicPersist && streamState.periodicPersistJob?.isActive != true) {
                streamState.lastStreamPersistAtMs = nowMs
                // 周期性防杀落盘放到独立后台任务中执行，避免 SQLite I/O 阻塞 SSE 流式吐字
                streamState.periodicPersistJob = appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (!streamState.streamCompleted) {
                        runCatching {
                            saveConversation(conversationId, updatedConversation, syncFts = false)
                        }
                    }
                }
            }

            if (!isForeground.value &&
                settings.displaySetting.enableNotificationOnMessageGeneration &&
                settings.displaySetting.enableLiveUpdateNotification
            ) {
                val nowNotifyMs = System.currentTimeMillis()
                if (nowNotifyMs - streamState.lastNotificationAtMs >= STREAM_NOTIFICATION_INTERVAL_MS) {
                    streamState.lastNotificationAtMs = nowNotifyMs
                    notificationHelper.sendLiveUpdateNotification(conversationId, chunk.messages, senderName)
                }
            }
        }
    }
}

private fun hasToolTransitionNeedingImmediatePersist(lastMessage: UIMessage?): Boolean =
    lastMessage?.parts?.any { p ->
        p is UIMessagePart.Tool &&
            p.output.isEmpty() &&
            (
                (
                    p.executionStartedAt != null &&
                        (p.approvalState is ToolApprovalState.Approved || p.approvalState is ToolApprovalState.Auto)
                    ) ||
                    p.approvalState is ToolApprovalState.Pending
                )
    } ?: false

private suspend fun ChatService.handleMessageCompleteFailure(
    conversationId: Uuid,
    error: Throwable,
) {
    if (error !is kotlinx.coroutines.CancellationException) {
        messageQueues[conversationId]?.pause()
    }
    notificationHelper.cancelLiveUpdateNotification(conversationId)
    notificationHelper.cancelDoneNotification(conversationId)

    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        runCatching {
            val final = getConversationFlow(conversationId).value
            if (error !is kotlinx.coroutines.CancellationException) {
                val diag = diagnoseFailure(context, error)
                val errText =
                    str(
                        me.rerere.rikkahub.R.string.error_context_injected,
                        diag.label,
                        diag.raw,
                    )
                val withErr =
                    final.copy(
                        messageNodes =
                            final.messageNodes +
                                UIMessage.system(errText).toMessageNode(),
                    )
                updateConversation(conversationId, withErr)
                saveConversation(conversationId, withErr)
            } else {
                saveConversation(conversationId, final)
            }
        }.onFailure { saveErr ->
            AppLog.e(TAG, "handleMessageComplete: failure-path save failed", saveErr)
        }

        AppLog.e(TAG, "handleMessageComplete: generation failed", error)
        addError(error, conversationId, title = str(R.string.error_title_generation))
        Logging.log(TAG, "handleMessageComplete: $error")
        Logging.log(TAG, error.stackTraceToString())
    }
}

private suspend fun ChatService.handleMessageCompleteSuccess(
    conversationId: Uuid,
    lastFailure: FailureDiagnosis?,
) {
    if (lastFailure != null) {
        val retryText =
            str(
                me.rerere.rikkahub.R.string.error_context_retry_succeeded,
                lastFailure.label,
                lastFailure.raw,
            )
        val withRetry =
            getConversationFlow(conversationId).value.copy(
                messageNodes =
                    getConversationFlow(conversationId).value.messageNodes +
                        UIMessage.system(retryText).toMessageNode(),
            )
        updateConversation(conversationId, withRetry)
        saveConversation(conversationId, withRetry)
    }
    val finalConversation = getConversationFlow(conversationId).value
    saveConversation(conversationId, finalConversation)

    launchAuxJob(conversationId, AuxJobKind.TITLE) {
        generateTitle(conversationId, finalConversation)
    }
    launchAuxJob(conversationId, AuxJobKind.SUGGESTION) {
        generateSuggestion(conversationId, finalConversation)
    }
}

internal suspend fun ChatService.grantAlwaysScope(conversationId: Uuid, toolName: String) {
        if (isWorkspaceToolName(toolName)) {
            val conversation = conversationRepo.getConversationById(conversationId)
            val assistant =
                conversation?.let {
                    settingsStore.settingsFlow.first().getAssistantById(it.assistantId)
                }
            val workspaceId = assistant?.workspaceId?.toString()
            val granted =
                workspaceId != null &&
                    workspaceRepository.setToolApproval(workspaceId, toolName, needsApproval = false)
            if (!granted) {
                // 工作区行缺失（删除/授权竞争）或无法解析——回退会话级授权，避免 Always 点击静默失效
                if (workspaceId != null) {
                    AppLog.w(
                        TAG,
                        "setToolApproval found no workspace row for '$workspaceId', falling back to chat-scoped grant for '$toolName'",
                    )
                }
                me.rerere.rikkahub.data.ai.tools
                    .ToolApprovalAllowList.grantForChat(conversationId, toolName)
            }
        } else {
            toolApprovalPreferences.grantAlways(toolName)
        }
    }


    // ---- 检查无效消息 ----

internal fun ChatService.checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes
        // 诊断用：记录本轮被定案为「未执行(中断)」的消息。正常轮次为空 → 不产生日志。
        val abandonedMessageIds = mutableListOf<Uuid>()

        // 生成开始前把「悬而未决的工具」定案（未执行的 Tool）。
        messagesNodes =
            messagesNodes.mapIndexed { _, node ->
                // Check for Tool type with non-executed tools
                val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }
                if (!hasPendingTools) {
                    return@mapIndexed node
                }

                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool =
                    node.currentMessage.getTools().any {
                        !it.isExecuted && it.approvalState.canResumeToolExecution()
                    }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // 到这里只剩「未执行且不可续跑」的工具：Pending（等审批却没被处理）或 Auto
                // （自动批准后在执行前被打断）。**不再整条删除该消息**——那会连带丢掉本轮已生成的
                // AI 文本与工具调用记录，表现为「用户两条消息之间的 AI 回复凭空消失」，且模型从此
                // 不知道发生过什么（可能重复执行同一副作用）。改为补一个合成的结果后保留消息，
                // 使其成为合法终态（isExecuted=true）并可安全进入后续上下文。
                //
                // 理由按「执行是否开始过」分叉：executionStartedAt 有值 = 派发过、副作用可能
                // 已发生（shell 跑完才被杀、结果落库前进程被回收），只能告知「状态未知、先核实」；
                // 只有从未开始的才告知「未执行」。混用会让模型重跑同一条 shell/git 命令，形成
                // 重复提交/重复发送。本通道先于 GenerationLoop 的 replay 安全通道执行，若不在此
                // 分叉，replay 的 interrupted_unknown_outcome 永远轮不到（它要求 output 为空，
                // 而此处已合成 output）。
                val abandoned =
                    node.currentMessage.abandonUnexecutedTools(
                        "interrupted_before_execution: " +
                            str(R.string.chat_tool_interrupted_before_execution_detail),
                        INTERRUPTED_UNKNOWN_OUTCOME_REASON,
                    )
                if (abandoned !== node.currentMessage) {
                    abandonedMessageIds += abandoned.id
                }
                node.copy(
                    messages = node.messages.map { if (it.id == abandoned.id) abandoned else it },
                )
            }

        // 更新index
        messagesNodes =
            messagesNodes.map { node ->
                if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                    node.copy(selectIndex = 0)
                } else {
                    node
                }
            }

        // 移除无效消息（兜底：正常路径已不会因中断清空 node；一旦触发即为异常丢消息，需留痕）
        val nodeCountBeforeDrop = messagesNodes.size
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }
        if (messagesNodes.size != nodeCountBeforeDrop) {
            AppLog.w(
                TAG,
                "checkInvalidMessages: dropped ${nodeCountBeforeDrop - messagesNodes.size} empty message node(s)",
            )
        }

        if (abandonedMessageIds.isNotEmpty()) {
            AppLog.i(
                TAG,
                "checkInvalidMessages: kept ${abandonedMessageIds.size} message(s) with unexecuted tools, " +
                    "marked as interrupted/denied: $abandonedMessageIds",
            )
        }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

internal fun ChatService.cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool =
        tool.copy(
            output =
                ToolErrors.partsFor(
                    error = "user_cancelled",
                    message = "Generation cancelled by user before tool execution completed.",
                ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user"),
        )

internal suspend fun ChatService.finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation =
            currentConversation.copy(
                messageNodes =
                    currentConversation.messageNodes.dropLast(1) +
                        lastNode.copy(
                            messages =
                                lastNode.messages.map { message ->
                                    if (message.id == lastMessage.id) updatedMessage else message
                                },
                        ),
            )
        saveConversation(conversationId, updatedConversation)
    }

