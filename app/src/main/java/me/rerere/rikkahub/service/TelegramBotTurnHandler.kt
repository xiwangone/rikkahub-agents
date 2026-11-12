
package me.rerere.rikkahub.service

import kotlin.uuid.Uuid

import me.rerere.rikkahub.data.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.telegram.AttachmentKind
import me.rerere.rikkahub.data.telegram.TelegramHtmlRenderer
import me.rerere.rikkahub.data.telegram.TelegramIncomingMessage

/**
 * Foreground service that long-polls Telegram for incoming messages and routes them into the
 * existing chat pipeline so the LLM can reply, with full tool-calling.
 *
 * Outbound: TelegramTool (LLM-callable) + this service's outbound helpers exposed via the
 * shared singleton client.
 *
 * Lifecycle: started by TelegramTool's enable_telegram_bot, stopped by disable_telegram_bot,
 * also re-started after device boot via CronBootReceiver if config.enabled was true.
 */
/**
 * Telegram 机器人后台长连接服务：消息轮询/Webhook 接收、指令分发与会话代理。
 *
 * 本文件很长（2300+ 行）。阅读与维护时请按下面的段落名检索符号，不要依赖行号：
 *  - 服务生命周期：[onCreate] / [onStartCommand] / [onDestroy]
 *  - 轮询与网络通信：长轮询连接、心跳与异常断线退避重试
 *  - 消息解析与分发：文本指令、图片输入、语音识别桥接
 *  - 会话执行与回调：调用 [me.rerere.rikkahub.service.ChatService] 发起推理与流式回复
 *
 * 相关文件：后台会话服务 [me.rerere.rikkahub.service.ChatService]。
 */
import me.rerere.rikkahub.service.TelegramBotService.Companion.ACTIVE_INCOMING_LINGER_MS
import me.rerere.rikkahub.service.TelegramBotService.Companion.MAX_CHARS
import me.rerere.rikkahub.service.TelegramBotService.Companion.PARSE_MODE_HTML
import me.rerere.rikkahub.service.TelegramBotService.Companion.STREAM_EDIT_BURST_THRESHOLD_CHARS
import me.rerere.rikkahub.service.TelegramBotService.Companion.STREAM_EDIT_INTERVAL_MS
import me.rerere.rikkahub.service.TelegramBotService.Companion.STREAM_EDIT_MIN_GAP_MS
import me.rerere.rikkahub.service.TelegramBotService.Companion.STREAM_PLACEHOLDER
import me.rerere.rikkahub.service.TelegramBotService.Companion.TAG

internal suspend fun TelegramBotService.handleIncoming(
        cfg: me.rerere.rikkahub.data.telegram.TelegramBotConfig,
        m: TelegramIncomingMessage,
    ) {
        val sender =
            m.senderId ?: run {
                AppLog.w(TAG, "handleIncoming: dropping — no sender id")
                return
            }
        // Strict whitelist: nobody is allowed unless their sender_id (or the chat_id, for
        // group routing) appears in cfg.whitelist. An empty whitelist drops everything —
        // that matches the UI's "Empty = nobody" promise. Without this, a freshly-enabled
        // bot whose owner forgot to fill in the whitelist would happily accept messages
        // from any random Telegram user who knows the bot's @username.
        if (sender !in cfg.whitelist && m.chatId !in cfg.whitelist) {
            AppLog.w(
                TAG,
                "handleIncoming: dropping — sender=$sender chat=${m.chatId} not in whitelist=${cfg.whitelist}",
            )
            // Stash the rejected sender so the foreground notification + UI can surface it.
            // First-time setup needs SOME way to discover the user's own chat_id, since
            // BotFather doesn't give it to you and the strict-whitelist policy means you
            // can't "just send a message and check the logs" anymore.
            TelegramBotRegistries.RejectedSenderLog.record(sender, m.chatId)
            foregroundHelper.updateNotification()
            return
        }
        // Built-in slash commands. These are handled entirely on-device — they never reach
        // the LLM, never spend tokens, and resolve in single-digit milliseconds. Critically,
        // they are NOT serialized behind any in-flight LLM call: that's what makes /stop
        // and /new actually responsive while a long generation is running.
        if (m.text.startsWith("/")) {
            if (handleBuiltInCommand(cfg, m)) return
        }

        // A pending interactive ask_user question awaiting a free-form reply consumes this
        // message as its answer (feeding it back to the parked generation) instead of starting
        // a new turn.
        if (tryResolveClarifyText(m.chatId, m.text)) return
        // Otherwise the message starts something new, so any earlier unanswered clarify for this
        // chat is abandoned — drop it so a stale entry doesn't linger.
        clearClarifyForChat(m.chatId)

        // Non-built-in messages run the LLM and must be serialized per-chat so two model
        // turns from the same chat don't interleave. Built-ins above ran without this lock
        // by design — that's the whole point of the fix.
        //
        // tryLock-with-timeout (vs the earlier blocking withLock) is what keeps the user
        // informed when a previous turn is paused on tool approval. Without this, sending
        // a follow-up message to a chat with an outstanding approval prompt sits silently
        // in the queue forever — the user has no idea their second message wasn't dropped
        // and not received.
        val mutex = mutexFor(m.chatId)
        var acquired = mutex.tryLock()
        if (!acquired) {
            // The prior turn is parked. Two cases where a fresh user message is the
            // implicit "abandon that, do this instead" and we should auto-stop:
            //   (1) Pending approval keyboard sitting unanswered.
            //   (2) Tool already approved + currently RUNNING — typically a tool that
            //       backgrounded the app (take_photo to the camera, launch_app, the 6
            //       system intents) and is now waiting on an activity result. The user
            //       came back to the chat and started typing instead of finishing the
            //       activity → they changed their mind. Without this case the new
            //       message bounces with "previous turn still generating", which is
            //       confusing because no tokens are actually being generated.
            val stuckOnApproval = TelegramBotRegistries.ApprovalPromptRegistry.snapshotForChat(m.chatId).isNotEmpty()
            val stuckOnRunningTool = !stuckOnApproval && hasInFlightApprovedTool(m.chatId)
            if (stuckOnApproval || stuckOnRunningTool) {
                autoCancelStuckTurn(m.chatId)
                acquired = mutex.tryLock()
            }
            if (!acquired) {
                try {
                    client.sendMessage(
                        chatId = m.chatId,
                        text = "⏳ A previous turn is still generating. Send /stop to cancel it.",
                        replyToMessageId = m.messageId,
                    )
                } catch (ignored: Throwable) {
                    // 「上一轮还在生成」的提示发不出去时记一条 w，否则用户收不到
                    // 提示、又不知道为什么没反应，时间线里也查不到。
                    AppLog.w(TAG, "handleIncoming: turn-busy notice send failed chat=${m.chatId}", ignored)
                }
                return
            }
        }
        // Register THIS coroutine as the chat's active turn so /stop and /new can cancel
        // it. Capture the parent Job from the running coroutine context. On exit (normal,
        // throw, or cancellation) the finally clears the registry and releases the mutex.
        val turnJob = currentCoroutineContext()[Job]
        if (turnJob != null) turnJobs[m.chatId] = turnJob
        try {
            handleLlmTurn(cfg, m)
        } finally {
            // remove ONLY the entry that's still us, in case /new replaced it concurrently
            turnJob?.let { turnJobs.remove(m.chatId, it) }
            mutex.unlock()
        }
    }

    /**
     * Body of an LLM-bound turn. Extracted from handleIncoming so the per-chat mutex wraps
     * exactly the section that owns the conversation/generation state. While this runs the
     * poll loop is still free to receive new messages and dispatch /stop into its own
     * coroutine, which calls chatService.stopGeneration(convId) and unblocks us.
     */
internal suspend fun TelegramBotService.handleLlmTurn(
        cfg: me.rerere.rikkahub.data.telegram.TelegramBotConfig,
        m: TelegramIncomingMessage,
    ) {
        val (convId, wasCreated) = lookupOrCreateConversation(cfg, m.chatId)
        AppLog.i(
            TAG,
            "handleIncoming: routing to conv=$convId wasCreated=$wasCreated text='${m.text.take(80)}' photos=${m.photoFileIds.size}",
        )
        runCatching {
            client.sendChatAction(m.chatId, "typing")
        }.onFailure { t ->
            if (t is CancellationException) throw t
            AppLog.w(TAG, "sendChatAction(typing) failed", t)
        }
        chatService.initializeConversation(convId)
        HeadlessConversations.markBrowserHeadless(convId)
        val baselineMessageCount = prepareAndSendTelegramTurnInput(cfg, m, convId, wasCreated)

        val ledgerId =
            agentRunRepo.open(
                kind = me.rerere.rikkahub.data.agentrun.AgentRunKind.Telegram,
                domainId = convId.toString(),
                metadata =
                    buildJsonObject {
                        put("chat_id", m.chatId.toString())
                        put("conversation_created", wasCreated)
                    },
            )

        me.rerere.rikkahub.data.telegram.TelegramStreamSignal
            .reset(convId.toString())

        val placeholderId: Long? =
            runCatching {
                val res =
                    client.sendMessage(
                        chatId = m.chatId,
                        text = STREAM_PLACEHOLDER,
                        replyToMessageId = m.messageId,
                    )
                res["message_id"]?.jsonPrimitive?.longOrNull
            }.onFailure { e ->
                if (e is CancellationException) throw e
                AppLog.w(TAG, "handleIncoming: placeholder send failed", e)
            }.getOrNull()

        chatService.addConversationReference(convId)
        pendingActiveIncomingRemovals.remove(convId)?.cancel()
        activeHandleIncomingConvs.add(convId)
        var generationError: Throwable? = null
        var turnCancelled = false
        try {
            val outcome =
                runCatching {
                    awaitTelegramTurnCompletionLoop(m.chatId, convId, placeholderId, baselineMessageCount)
                }
            val err = outcome.exceptionOrNull()
            if (err is kotlinx.coroutines.CancellationException) {
                AppLog.i(TAG, "handleIncoming: cancelled (CancellationException) — not sending final reply")
                turnCancelled = true
                throw err
            } else if (err != null) {
                AppLog.w(TAG, "handleIncoming: generation flow ended with error", err)
                generationError = err
            }
        } finally {
            cleanupTelegramTurnSession(convId, ledgerId, turnCancelled, generationError)
        }

        finalizeTelegramTurnReply(m, convId, placeholderId, baselineMessageCount, generationError)
        chatRepo.touch(m.chatId, System.currentTimeMillis())
    }

private suspend fun TelegramBotService.prepareAndSendTelegramTurnInput(
    cfg: me.rerere.rikkahub.data.telegram.TelegramBotConfig,
    m: TelegramIncomingMessage,
    convId: Uuid,
    wasCreated: Boolean,
): Int {
    val hasAudioAttachment =
        m.attachments.any { att ->
            att.kind == AttachmentKind.VOICE ||
                att.kind == AttachmentKind.AUDIO ||
                att.kind == AttachmentKind.VIDEO_NOTE
        }
    val hasPhotoAttachment = m.photoFileIds.isNotEmpty()
    me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum.set(
        convId,
        buildAgentContextPreamble(cfg, m.chatId, wasCreated, hasAudioAttachment, hasPhotoAttachment),
    )
    val (imageParts, photoPaths) = downloadInboundPhotos(client, m.chatId, m.photoFileIds)
    val photoNote = buildPhotoNote(photoPaths)
    val downloadedAttachments = downloadInboundAttachments(client, m.chatId, m.attachments)
    val attachmentNote = buildAttachmentNote(downloadedAttachments)

    val parts =
        buildList<UIMessagePart> {
            addAll(imageParts)
            val combinedText =
                listOf(m.text, photoNote, attachmentNote)
                    .filter { it.isNotEmpty() }
                    .joinToString("\n\n")
            if (combinedText.isNotEmpty()) {
                add(UIMessagePart.Text(combinedText))
            } else if (imageParts.isNotEmpty()) {
                add(UIMessagePart.Text("(photo)"))
            }
        }
    val baselineMessageCount =
        conversationRepo
            .getConversationById(convId)
            ?.currentMessages
            ?.size ?: 0

    chatService.sendMessage(convId, parts)
    return baselineMessageCount
}

private suspend fun TelegramBotService.awaitTelegramTurnCompletionLoop(
    chatId: Long,
    convId: Uuid,
    placeholderId: Long?,
    baselineMessageCount: Int,
) {
    val promptedToolCallIds = mutableSetOf<String>()
    var iteration = 0
    while (true) {
        val firstActive =
            if (iteration == 0) {
                kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    chatService.getGenerationJobStateFlow(convId).first { it != null }
                }
            } else {
                chatService.getGenerationJobStateFlow(convId).first { it != null }
            }
        if (firstActive == null) break

        awaitSingleGenerationCycle(chatId, convId, placeholderId, baselineMessageCount)

        val pendingTools =
            chatService
                .getConversationFlow(convId)
                .value
                .currentMessages
                .drop(baselineMessageCount)
                .flatMap { it.parts.filterIsInstance<UIMessagePart.Tool>() }
                .filter { it.isPending }
        if (pendingTools.isEmpty()) break

        dispatchPendingToolsForTelegram(chatId, convId, baselineMessageCount, pendingTools, promptedToolCallIds)
        iteration++
    }
}

private suspend fun TelegramBotService.awaitSingleGenerationCycle(
    chatId: Long,
    convId: Uuid,
    placeholderId: Long?,
    baselineMessageCount: Int,
) {
    val typingJob =
        scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true) {
                runCatching { client.sendChatAction(chatId, "typing") }
                    .onFailure { if (it is CancellationException) throw it }
                delay(4_000)
            }
        }
    val editJob =
        if (placeholderId != null) {
            startTelegramStreamingEditJob(chatId, convId, placeholderId, baselineMessageCount)
        } else {
            null
        }
    try {
        chatService.getGenerationJobStateFlow(convId).first { it == null }
    } finally {
        runCatching { typingJob.cancelAndJoin() }
        runCatching { editJob?.cancelAndJoin() }
    }
}

private fun TelegramBotService.startTelegramStreamingEditJob(
    chatId: Long,
    convId: Uuid,
    placeholderId: Long,
    baselineMessageCount: Int,
): Job =
    scope.launch {
        var lastSent = ""
        var lastEditAtMs = 0L
        while (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true) {
            waitForNextStreamEditSlice(convId, baselineMessageCount, lastSent.length, lastEditAtMs)
            val rendered = renderAssistantStream(convId, finalizing = false, baselineMessageCount)
            if (rendered.isBlank() || rendered == lastSent) continue
            val capped = TelegramHtmlRenderer.render(truncateForLiveEdit(rendered, MAX_CHARS))
            val ok = tryLiveEditHtmlOrPlain(chatId, placeholderId, capped)
            if (ok) {
                lastSent = rendered
                lastEditAtMs = System.currentTimeMillis()
            }
        }
    }

private suspend fun TelegramBotService.waitForNextStreamEditSlice(
    convId: Uuid,
    baselineMessageCount: Int,
    lastSentLength: Int,
    lastEditAtMs: Long,
) {
    val pollSliceMs = 80L
    var sliceWaitedMs = 0L
    while (sliceWaitedMs < STREAM_EDIT_INTERVAL_MS) {
        delay(pollSliceMs)
        sliceWaitedMs += pollSliceMs
        val peek = renderAssistantStream(convId, finalizing = false, baselineMessageCount)
        if (peek.length - lastSentLength >= STREAM_EDIT_BURST_THRESHOLD_CHARS &&
            System.currentTimeMillis() - lastEditAtMs >= STREAM_EDIT_MIN_GAP_MS
        ) {
            break
        }
    }
}

private suspend fun TelegramBotService.tryLiveEditHtmlOrPlain(
    chatId: Long,
    placeholderId: Long,
    cappedHtml: String,
): Boolean =
    runCatching {
        client.editMessageText(chatId, placeholderId, cappedHtml, parseMode = PARSE_MODE_HTML) != null
    }.getOrElse {
        runCatching {
            client.editMessageText(
                chatId,
                placeholderId,
                TelegramHtmlRenderer.stripHtml(cappedHtml),
                parseMode = null,
            ) != null
        }.getOrDefault(false)
    }

private fun TelegramBotService.dispatchPendingToolsForTelegram(
    chatId: Long,
    convId: Uuid,
    baselineMessageCount: Int,
    pendingTools: List<UIMessagePart.Tool>,
    promptedToolCallIds: MutableSet<String>,
) {
    for (tool in pendingTools) {
        if (!promptedToolCallIds.add(tool.toolCallId)) continue
        if (tool.toolName == "ask_user") {
            scope.launch { startClarify(chatId, convId, tool) }
            continue
        }
        val recentFailures = recentFailedRunsOf(convId, tool.toolName, baselineMessageCount)
        if (recentFailures >= 3) {
            scope.launch {
                autoDenyRepeatedFailedTool(chatId, convId, tool, recentFailures)
            }
            continue
        }
        scope.launch { sendApprovalPrompt(chatId, tool) }
    }
}

private suspend fun TelegramBotService.autoDenyRepeatedFailedTool(
    chatId: Long,
    convId: Uuid,
    tool: UIMessagePart.Tool,
    recentFailures: Int,
) {
    chatService.handleToolApproval(
        conversationId = convId,
        toolCallId = tool.toolCallId,
        approved = false,
        reason = "aborted_repeated_failure: ${tool.toolName} returned an error $recentFailures times in this turn — stop retrying and report to the user.",
        toolName = tool.toolName,
    )
    runCatching {
        client.sendMessage(
            chatId,
            "⛔ Auto-denied: <code>${TelegramHtmlRenderer.escape(tool.toolName)}</code> already failed $recentFailures× this turn. Telling the model to stop retrying.",
            parseMode = PARSE_MODE_HTML,
        )
    }.onFailure { t ->
        if (t is CancellationException) throw t
        AppLog.w(TAG, "tool-failure notice send failed", t)
    }
}

private suspend fun TelegramBotService.cleanupTelegramTurnSession(
    convId: Uuid,
    ledgerId: String,
    turnCancelled: Boolean,
    generationError: Throwable?,
) {
    chatService.removeConversationReference(convId)
    val removalJob =
        scope.launch {
            delay(ACTIVE_INCOMING_LINGER_MS)
            activeHandleIncomingConvs.remove(convId)
            pendingActiveIncomingRemovals.remove(convId)
        }
    pendingActiveIncomingRemovals.compute(convId) { _, existing ->
        existing?.cancel()
        removalJob
    }
    HeadlessConversations.unmark(convId)
    val ledgerStatus =
        when {
            turnCancelled -> me.rerere.rikkahub.data.agentrun.AgentRunStatus.cancelled
            generationError != null -> me.rerere.rikkahub.data.agentrun.AgentRunStatus.failed
            else -> me.rerere.rikkahub.data.agentrun.AgentRunStatus.succeeded
        }
    agentRunRepo.markTerminal(
        id = ledgerId,
        status = ledgerStatus,
        lastError = generationError?.let { "${it::class.simpleName}: ${it.message.orEmpty()}" },
    )
}

private suspend fun TelegramBotService.finalizeTelegramTurnReply(
    m: TelegramIncomingMessage,
    convId: Uuid,
    placeholderId: Long?,
    baselineMessageCount: Int,
    generationError: Throwable?,
) {
    val finalReply = renderAssistantStream(convId, finalizing = true, baselineMessageCount)
    AppLog.i(TAG, "handleIncoming: finalizing ${finalReply.length} chars to chat=${m.chatId}")

    when {
        finalReply.isBlank() -> {
            handleBlankTelegramFinalReply(m.chatId, convId, placeholderId, baselineMessageCount, generationError)
        }

        placeholderId != null && finalReply.length <= MAX_CHARS &&
            me.rerere.rikkahub.data.telegram.TelegramStreamSignal
                .count(convId.toString()) == 0 -> {
            editPlaceholderHtmlWithFallback(m.chatId, placeholderId, finalReply)
        }

        else -> {
            if (placeholderId != null) {
                runCatching { client.deleteMessage(m.chatId, placeholderId) }
            }
            sendChunked(m.chatId, finalReply, replyTo = m.messageId)
        }
    }
}

private suspend fun TelegramBotService.handleBlankTelegramFinalReply(
    chatId: Long,
    convId: Uuid,
    placeholderId: Long?,
    baselineMessageCount: Int,
    generationError: Throwable?,
) {
    AppLog.i(
        TAG,
        "handleIncoming: empty final reply (model finished with no text after tool calls) chat=$chatId",
    )
    val rescued = tryRescueImageFromTurn(convId, baselineMessageCount, chatId)
    if (!rescued) {
        val fallback =
            generationError?.let { e ->
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "unknown error"
                val capped = if (msg.length <= 600) msg else msg.take(600) + "…"
                "⚠️ Generation failed: $capped"
            }
                ?: "(model called tools but didn't reply — try saying \"continue\", switch model with /model, or reset with /new)"
        if (placeholderId != null) {
            runCatching { client.editMessageText(chatId, placeholderId, fallback) }
        } else {
            runCatching { client.sendMessage(chatId, fallback) }
        }
    } else if (placeholderId != null) {
        runCatching { client.deleteMessage(chatId, placeholderId) }
    }
}

    /**
     * Send an inline-keyboard approval prompt for [tool]. The prompt is its OWN Telegram
     * message (not an edit of the streaming placeholder) so the user can scroll between
     * them when the model queues up multiple Pending tools at once.
     *
     * No timeout / watchdog: the user is allowed to take as long as they need to respond
     * (they might be away from the phone for hours). The prompt stays valid until they
     * tap, /stop is sent, the conversation is reset, or the app process restarts.
     */
    // ---- Interactive ask_user (clarify) over Telegram --------------------------------
    //
    // ask_user has no executable body — in-app it's answered via a tappable question card.
    // Over Telegram we mirror the approval flow: render each question's options as an inline
    // keyboard (plus a "type your own answer" button), capture taps via the clr: callback and
