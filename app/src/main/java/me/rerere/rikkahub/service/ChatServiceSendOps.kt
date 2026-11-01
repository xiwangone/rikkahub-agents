
package me.rerere.rikkahub.service

import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishPendingTools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationRunTracker
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import kotlin.uuid.Uuid


private const val TAG = "ChatService"

    /**
     * 生成结束后派发队首消息。由 [sendMessageNow] 的收尾统一调用，
     * 保证「一轮结束 → 自动发出下一条排队消息」形成闭环。
     */
internal fun ChatService.dispatchNextQueuedMessageImpl(
        conversationId: Uuid,
        fromJob: Job? = null,
    ) {
        val queue = messageQueues[conversationId] ?: return
        val session = getOrCreateSession(conversationId)
        // 仍在生成中（例如停止后又被 resume）或存在待审批工具时，等下一次时机。
        // 注意：从本轮协程内部调用时 session 里存的可能就是该协程自身的 job，
        // 必须排除，否则会误判为「他人正在生成」而永不放行队列。
        val active = session.getJob()
        // 协程被取消后 isActive 会转为 false，此守卫随之放行 —— 即「停止生成」仍会派发待发队列的
        // 下一条。这是产品语义（取消 = 跳过当前这条、队列继续），勿当 bug 修。
        if (active != null && active.isActive && active !== fromJob) {
            AppLog.i(TAG, "msg-dequeue-skip conv=$conversationId reason=another-job-active")
            // 不要放弃：挂到那个活跃 job 的完成回调上再派一次。
            // 否则「入队 → 试图出队 → 因 another-job-active 跳过 → 再也没人触发」会把消息永久卡在队列里
            // （现象：入队后正文被取消、而排队消息也没发出去）。
            scheduleQueueDispatchAfter(active, appScope) { dispatchNextQueuedMessage(conversationId) }
            return
        }
        val pendingToolExists =
            session.state.value.currentMessages.lastOrNull()?.getTools()?.any { !it.isExecuted } == true
        if (pendingToolExists) {
            if (active != null && active.isActive) {
                AppLog.i(TAG, "msg-dequeue-skip conv=$conversationId reason=pending-tool (job active)")
                scheduleQueueDispatchAfter(active, appScope) { dispatchNextQueuedMessage(conversationId) }
                return
            }
            // 本轮已无活跃生成者：悬挂工具不会再有执行者，先定案再重判。
            // 否则队列会永久卡住（用户侧现象：「发了没反应」）。
            AppLog.i(TAG, "msg-dequeue-skip conv=$conversationId reason=pending-tool (finalizing)")
            appScope.launch {
                runCatching { finishInterruptedPendingTools(conversationId) }
                    .onFailure { AppLog.w(TAG, "msg-dequeue: finalize pending tools failed", it) }
                val stillPending =
                    session.state.value.currentMessages.lastOrNull()?.getTools()?.any { !it.isExecuted } == true
                if (stillPending) {
                    AppLog.w(TAG, "msg-dequeue-skip conv=$conversationId reason=pending-tool (unresolved)")
                } else {
                    dispatchNextQueuedMessage(conversationId)
                }
            }
            return
        }
        val next = queue.takeNext() ?: return
        AppLog.i(TAG, "msg-dequeue conv=$conversationId remaining=${queue.size}")
        // 带上传出的条目：本次发起失败时由 sendMessageNow 的失败分支放回队首，避免「已出队、没发出」。
        sendMessageNow(conversationId, next.parts, next.answer, next.reply, requeueOnFailure = next)
    }

    // catch 内要先统一做 completeExceptionally 与日志，再按「是否取消 / 是否 headless」分流放行 rethrow；
    // 多个 catch 会重复这两步且拿不到同一个 e，故保留类型判断。
    @Suppress("InstanceOfCheckForException")
internal fun ChatService.sendMessageNowImpl(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        reply: CompletableDeferred<String?>? = null,
        requeueOnFailure: QueuedMessage? = null,
    ) {

        // 消息事件日志（只记事件 + 会话 id，不记文本，隐私边界）
        val isUserTurn = content.any { it is UIMessagePart.Text && it.text.isNotBlank() }
        val textCount = content.count { it is UIMessagePart.Text }
        AppLog.i(
            TAG,
            "msg-recv conv=$conversationId parts=${content.size} text=$textCount userTurn=$isUserTurn answer=$answer",
        )

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        if (previousJob?.isActive == true) {
            AppLog.i(
                TAG,
                "msg-cancel prev conv=$conversationId nodes=${session.state.value.messageNodes.size}",
            )
        }
        // 运行归因：被新消息打断也属「用户主动结束」，语义与点停止一致。
        GenerationRunTracker.markCancelled(conversationId.toString(), "superseded_by_new_message")
        previousJob?.cancel()

        val job =
            appScope.launch {
                try {
                    runCatching { previousJob?.join() }
                    // Do not let a final chunk from the replaced job overwrite this turn's binding.
                    session.beginFloatingTurn()
                    finishInterruptedPendingTools(conversationId)
                    // 被「新消息」打断的上一轮不走 stopGeneration，此前从不落库：
                    // 内容只存在内存，会话一旦重建/刷新就整轮消失（工具还会被重放改成「拒绝」）。
                    // 这里补一次落库，使「发新消息打断」与「点停止」语义一致。
                    withContext(NonCancellable) { saveConversation(conversationId, session.state.value) }

                    val currentConversation = session.state.value
                    // Resolve the assistant from the conversation's own assistantId, not the
                    // global current-assistant pointer — otherwise switching assistants mid-
                    // generation makes one conversation preprocess input with another's config.
                    val settings = settingsStore.settingsFlow.first()
                    val assistant =
                        settings.getAssistantById(currentConversation.assistantId)
                            ?: settings.getCurrentAssistant()
                    val processedContent = preprocessUserInputParts(content, assistant)

                    // 添加消息到列表
                    val withUser =
                        currentConversation.copy(
                            messageNodes =
                                currentConversation.messageNodes +
                                    UIMessage(
                                        role = MessageRole.USER,
                                        parts = processedContent,
                                    ).toMessageNode(),
                        )
                    // 用户消息入库必须不被取消：stop 若正好落在入库事务窗口内，
                    // 可取消的写会被回滚，而内存里已经有这条 → 重进会话时消息丢失。
                    withContext(NonCancellable) { saveConversation(conversationId, withUser) }

                    // Phase 16 — fast-path router. If the assistant has it enabled and the user's
                    // message matches a deterministic intent, run the matching tool and inject the
                    // result as a synthetic assistant message — skipping the LLM entirely.
                    // Conservative: any match failure (tool throws, no result) falls back to the
                    // normal LLM path. Headless conversations and non-text messages are skipped.
                    val routedHandled =
                        if (answer) {
                            tryFastPathRoute(conversationId, processedContent, withUser, assistant)
                        } else {
                            false
                        }

                    // 开始补全 — only if router didn't handle the turn
                    if (answer && !routedHandled) {
                        handleMessageComplete(conversationId)
                    }

                    AppLog.i(TAG, "msg-done conv=$conversationId routed=$routedHandled")
                    if (reply != null) {
                        // 语音模式：把本轮助手回复交给等待者；有等待者时不再发普通自动播报事件
                        reply.completeWith(runCatching { collectAssistantReply(conversationId) })
                    } else {
                        _generationDoneFlow.emit(conversationId)
                    }
                } catch (e: Exception) {
                    reply?.completeExceptionally(e)
                    if (e is CancellationException) {
                        // 协程取消：新消息打断/会话切换，不算失败
                        AppLog.i(TAG, "msg-cancel conv=$conversationId") // 已在上方 previousJob 取消处记录，此处仅兜底
                    } else {
                        AppLog.w(TAG, "msg-fail conv=$conversationId err=${e.message}", e)
                    }
                    e.printStackTrace()
                    // 修复：headless 会话（子代理/cron/工作流/外部自动化）的调用方靠「协程
                    // 正常结束」判定成功——异常被吞 → SubAgentEngine.executeRun 的
                    // first { it == null } 立即满足 → 误判 SUCCEEDED 空转。对 headless 且
                    // 非取消的异常放行 rethrow，让调用方 catch 走 FAILED 路径；交互会话
                    // 保持现状（addError 提示用户，不崩 UI）。
                    // 注：sendMessageNow 的 conversationId 签名即非空 Uuid，无需 null 判据；
                    // HeadlessConversations.isHeadless 查内存 ConcurrentHashMap 集合
                    // （SharedPreferences 只在启动 init() 时读一次），catch 内调用无 I/O。
                    if (e !is kotlinx.coroutines.CancellationException &&
                        me.rerere.rikkahub.data.ai.tools.HeadlessConversations.isHeadless(conversationId)
                    ) {
                        throw e
                    }
                    // 来自队列的条目发送失败时放回队首（并暂停派发）：不丢消息，也不因反复重派而空转。
                    // 取消（用户停止/新消息打断）不归队 —— 那是产品语义「跳过当前这条，队列继续」。
                    if (e !is kotlinx.coroutines.CancellationException && requeueOnFailure != null) {
                        messageQueues.getOrPut(conversationId) { MessageQueue() }
                            .requeueFront(requeueOnFailure)
                        AppLog.w(
                            TAG,
                            "msg-requeue conv=$conversationId reason=${e.message}",
                        )
                    }
                    addError(e, conversationId, title = str(R.string.error_title_send_message))
                } finally {
                    // 无论正常结束、取消还是失败，都尝试派发队首排队消息，
                    // 否则«用户停止生成»会让队列永久卡住。
                    dispatchNextQueuedMessage(conversationId, fromJob = coroutineContext[Job])
                }
            }
        // A fresh main generation makes any in-flight title/suggestion request for this
        // conversation redundant, and letting it keep running alongside the new request just
        // adds a second concurrent connection to the same server (see AuxJobRegistry kdoc).
        auxJobs.cancelAll(conversationId)
        session.setJob(job)
    }

    /**
     * Phase 16 — fast-path router entry. Returns `true` if the router successfully handled
     * the turn (synthesised an assistant message and stored it) so the caller knows to skip
     * the normal LLM dispatch. Returns `false` to fall through.
     */
internal suspend fun ChatService.tryFastPathRoute(
        conversationId: Uuid,
        userParts: List<UIMessagePart>,
        afterUserSave: me.rerere.rikkahub.data.model.Conversation,
        assistant: Assistant,
    ): Boolean {
        // Headless paths (cron / sub-agent / external-automation / workflow) must always go
        // through the LLM — the fast-path is a per-user-turn optimisation, not a system-flow.
        if (me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                .isHeadless(conversationId)
        ) {
            return false
        }

        // assistant is resolved from the conversation's own assistantId by the caller — do NOT
        // re-read the global getCurrentAssistant() here or a mid-turn assistant switch makes the
        // router read fastPathRouterEnabled / localTools off the wrong assistant.
        if (!assistant.fastPathRouterEnabled) return false

        val userText = userParts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }.trim()
        if (userText.isBlank()) return false

        val match =
            me.rerere.rikkahub.skills.FastPathRouter
                .route(userText) ?: return false

        // Tool list construction is non-trivial on assistants with many enabled categories
        // (allocates a fresh List<Tool> each call). Defer until AFTER a router match so the
        // common no-match path stays at a single regex scan + an early return.
        // Fast-path is gated on !isHeadless above; pass the caller context so any tools the
        // router fires inherit the right assistant id (workflows / sub-agents / etc).
        val tools =
            localTools.getTools(
                assistant.localTools,
                me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                    callerAssistantId = assistant.id.toString(),
                    callerConversationId = conversationId.toString(),
                    isHeadless = false, // gated above
                ),
            )
        val tool =
            tools.firstOrNull { it.name == match.toolName } ?: run {
                AppLog.d(
                    "FastPathRouter",
                    "matched intent=${match.intent} but tool=${match.toolName} not registered for assistant; falling through",
                )
                return false
            }

        // Defence-in-depth — even though v1's intent set is read-only, run HARDLINE here so
        // that adding a side-effecting intent later (e.g. "set brightness 50%") can't bypass
        // the floor by routing around the LLM-tool-call path that normally enforces it.
        val hardlineReason =
            me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
                .checkTool(match.toolName, match.args.toString())
        if (hardlineReason != null) {
            AppLog.w(
                "FastPathRouter",
                "hardline-blocked intent=${match.intent} tool=${match.toolName}: $hardlineReason; falling through to LLM",
            )
            return false
        }

        val rendered: String =
            try {
                val out = tool.execute(match.args)
                val rawText = out.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                val parsed =
                    runCatching {
                        kotlinx.serialization.json.Json
                            .parseToJsonElement(rawText)
                            .jsonObject
                    }.getOrNull()
                val formatted =
                    if (match.format != null && parsed != null) {
                        runCatching { match.format.invoke(parsed) }
                            .onFailure {
                                AppLog.w(
                                    "FastPathRouter",
                                    "formatter for intent=${match.intent} threw; falling back to raw text",
                                    it,
                                )
                            }.getOrNull()
                    } else {
                        null
                    }
                // Fall back to raw text if formatter throws or produces nothing.
                formatted?.takeIf { it.isNotBlank() } ?: rawText
            } catch (t: Throwable) {
                AppLog.w("FastPathRouter", "tool ${match.toolName} threw, falling back to LLM", t)
                me.rerere.rikkahub.skills.FastPathRouterLog.record(
                    me.rerere.rikkahub.skills.FastPathRouterLog.Entry(
                        whenMs = System.currentTimeMillis(),
                        intent = match.intent,
                        toolName = match.toolName,
                        userText = userText.take(120),
                        resultPreview = "tool threw: ${t.message?.take(80)}",
                        skippedLlm = false,
                    ),
                )
                return false
            }

        // Inject synthetic assistant message into the conversation.
        val fastReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(rendered)),
        )
        val withAssistant =
            afterUserSave.copy(
                messageNodes =
                    afterUserSave.messageNodes +
                        fastReply.toMessageNode(),
            )
        getOrCreateSession(conversationId).recordFloatingReply(fastReply.id)
        saveConversation(conversationId, withAssistant)
        me.rerere.rikkahub.skills.FastPathRouterLog.record(
            me.rerere.rikkahub.skills.FastPathRouterLog.Entry(
                whenMs = System.currentTimeMillis(),
                intent = match.intent,
                toolName = match.toolName,
                userText = userText.take(120),
                resultPreview = rendered.take(200),
                skippedLlm = true,
            ),
        )
        return true
    }

internal fun ChatService.preprocessUserInputParts(
        parts: List<UIMessagePart>,
        assistant: Assistant,
    ): List<UIMessagePart> =
        parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text =
                            part.text.replaceRegexes(
                                assistant = assistant,
                                scope = AssistantAffectScope.USER,
                                visual = false,
                            ),
                    )
                }

                else -> {
                    part
                }
            }
        }

    // ---- 重新生成消息 ----

internal fun ChatService.regenerateAtMessageImpl(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
    ) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job =
            appScope.launch {
                try {
                    previousJob?.join()
                    session.beginFloatingTurn()
                    val conversation = session.state.value

                    // Locate the message's node up front. indexOf returns -1 when the node is no
                    // longer in the conversation (e.g. it was edited or removed between the tap and
                    // here). Both branches index off this: the USER branch would subList(0, 0) and
                    // silently wipe the conversation, and the regenerate branch builds `0..<-1`,
                    // whose endInclusive is -2, which handleMessageComplete turns into
                    // subList(0, -1) and crashes ("fromIndex(0) > toIndex(-1)"). Bail on not-found.
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    if (indexAt < 0) {
                        AppLog.w(
                            TAG,
                            "regenerateAtMessage: node for message ${message.id} not in conversation; skipping",
                        )
                        return@launch
                    }
                    if (message.role == MessageRole.USER) {
                        // 如果是用户消息，则截止到当前消息
                        val newConversation =
                            conversation.copy(
                                messageNodes = conversation.messageNodes.subList(0, indexAt + 1),
                            )
                        saveConversation(conversationId, newConversation)
                        handleMessageComplete(conversationId)
                    } else {
                        if (regenerateAssistantMsg) {
                            handleMessageComplete(conversationId, messageRange = 0..<indexAt)
                        } else {
                            saveConversation(conversationId, conversation)
                        }
                    }

                    _generationDoneFlow.emit(conversationId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    addError(e, conversationId, title = str(R.string.error_title_regenerate_message))
                } finally {
                    dispatchNextQueuedMessage(conversationId, fromJob = coroutineContext[Job])
                }
            }

        // Same reasoning as sending: a regenerate is a fresh main generation, so drop any
        // in-flight title/suggestion request for this conversation instead of racing it
        // against the regenerate.
        auxJobs.cancelAll(conversationId)
        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

internal fun ChatService.checkFilesDelete(
        newConversation: Conversation,
        oldConversation: Conversation,
    ) {
        // 仍在队列里、还没写进历史的附件同样算「在用」，否则保存会话时会连带把它们的本地
        // 文件删掉（等消息真正发出时附件已经找不到了）。
        val queuedFiles =
            messageQueues[newConversation.id]?.state?.value?.messages.orEmpty()
                .flatMap { it.parts.localFileUrls() }
                .map { it.toUri() }
        val newFiles = newConversation.files + queuedFiles
        val oldFiles = oldConversation.files
        val deletedFiles =
            oldFiles.filter { file ->
                newFiles.none { it == file }
            }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            AppLog.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    /**
     * 把会话写入数据库。
     *
     * @param syncMemory 是否把这份快照同步回内存 flow。默认真——正常写入必须保证内存与磁盘一致；
     *   会话回收路径（[persistSnapshotBeforeEvict]）必须传 false：那时会话刚被移出 map，
     *   同步会经 `getOrCreateSession` 把它**重建**回来（并再次触发 idle 回收）。
     */
internal suspend fun ChatService.saveConversationImpl(
        conversationId: Uuid,
        conversation: Conversation,
        syncMemory: Boolean = true,
    ) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        // 内存先行：新空会话不落库，但内存态必须更新（如会话级模型切换），否则切换被静默丢弃
        if (syncMemory) updateConversation(conversationId, conversation.copy())
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不落库（内存已更新）
        }
        // 落库埋点：消息数与最后一条的 role/部件数 —— 排查丢消息时可直接对齐「内存有什么 / 写了什么」。
        AppLog.d(
            TAG,
            "save conv=$conversationId nodes=${conversation.messageNodes.size} last=" +
                (conversation.messageNodes.lastOrNull()?.currentMessage?.let { "${it.role}/${it.parts.size}" } ?: "none"),
        )
        // Refuse to overwrite a non-empty stored row with an empty in-memory snapshot.
        // This is the silent-data-loss guard: handleToolApproval / stopGeneration / etc.
        // could be called against an unhydrated session (post-restart), build an empty
        // updatedConversation, and call saveConversation. Without this guard we'd wipe
        // the Pending tool the user was trying to approve.
        if (exists && conversation.messageNodes.isEmpty()) {
            val storedHasContent =
                runCatching {
                    conversationRepo.getConversationById(conversation.id)?.messageNodes?.isNotEmpty() == true
                }.getOrDefault(false)
            if (storedHasContent) {
                AppLog.w(
                    TAG,
                    "saveConversation: refusing to overwrite non-empty $conversationId with empty snapshot — likely an unhydrated session",
                )
                return
            }
        }

        val updatedConversation = conversation.copy()

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // 停止当前会话生成任务（不清理会话缓存）
internal suspend fun ChatService.stopGenerationImpl(conversationId: Uuid) {
        val convMutex = mutexFor(conversationId)
        // 用户主动停止不暂停队列：队列里还有消息就让它照常作为下一轮自动发出（这就是排队语义），
        // 队列为空时用户的下一条消息直接发出 —— 停止只结束当前这一轮，不再拦住后续发送。
        // 运行归因：标记这次取消是「用户主动停」，生成收尾时据此区分「用户停」与「超时 / 进程被杀」。
        GenerationRunTracker.markCancelled(conversationId.toString(), "user_stop")
        // cancelAndJoin BEFORE the mutex so the cancelled coroutine can drain its own
        // writes (which may try to acquire the same mutex via their save path).
        sessions[conversationId]?.let { session ->
            // 接续语义下可能同时存在多个任务（审批接续链），停止时须全部取消再等待收尾。
            val jobs = runCatching { session.cancelJobs() }.getOrNull() ?: emptyList()
            jobs.forEach { runCatching { it.join() } }
        }

        // 后端连接路径：本地协程取消不会让服务端停下，必须显式通知取消，
        // 否则服务端 turn 会一直挂着并阻塞后续提交（表现为「停止无效、再发无反应」）。
        runCatching { cancelBackendGenerationIfNeeded(conversationId) }

        convMutex.withLock {
            // Hydrate from disk so we mark Pending tools cancelled even when the user
            // hits /stop after a process restart (sessions map is empty post-restart;
            // the old code returned early on the !sessions[id]?.getJob() check, leaving
            // the persisted Pending tool stranded forever).
            ensureHydrated(conversationId)

            val currentConversation = getConversationFlow(conversationId).value
            // Walk EVERY node, not just the last — Pending tools can appear on a non-last
            // node after branching / regenerate. finishPendingTools is now scoped to
            // tools that are NOT already in a terminal state, so a hardline-blocked
            // Denied tool keeps its original reason rather than being relabeled as
            // "cancelled by user".
            var finalizedMessages = 0
            val updatedNodes =
                currentConversation.messageNodes.map { node ->
                    node.copy(
                        messages =
                            node.messages.map { msg ->
                                val next = msg.finishPendingTools(::cancelToolByUser)
                                if (next !== msg) finalizedMessages++
                                next
                            },
                    )
                }
            if (finalizedMessages > 0) {
                AppLog.i(
                    TAG,
                    "stopGeneration: finalized $finalizedMessages message(s) with pending tools " +
                        "-> Denied(cancelled by user)",
                )
            }
            // 无条件落库一次：打断可能正好落在「消息已进内存、入库事务尚未提交」的窗口
            // （发送/注入那两处入库是可取消调用，会被 stop 回滚），
            // 这里用 NonCancellable 把内存态固化，避免重进会话时少一条、顺序跳跃。
            val updatedConversation = currentConversation.copy(messageNodes = updatedNodes)
            withContext(NonCancellable) { saveConversation(conversationId, updatedConversation) }
        }

        // 停止生成后给队列一次机会：常规情况下队列已暂停（见上），这里主要让「未暂停但被
        // 待审批工具挡住」的队列在 Pending 工具收尾后能立即派发，不必等下一次交互。
        // 放在锁外：dispatch 内部重新读取会话与待审批状态，此刻 Pending 工具已全部收尾。
        appScope.launch { dispatchNextQueuedMessage(conversationId) }
    }

    /**
     * 通知后端服务取消当前生成。
     *
     * 目标后端有两个来源，**都要看**（任一命中即取消）：
     * ① 全局「执行后端」指向的 provider；
     * ② 本对话实际使用的模型所属 provider（接入 provider 可以在提供商页配好、
     *    直接在对话里选模型使用，这条路径不会写 executionBackend）。
     * 只认①会让②的“停止不影响远端”。
     */
internal suspend fun ChatService.cancelBackendGenerationIfNeeded(conversationId: Uuid) {
        val settings = settingsStore.settingsFlow.value
        // 快速短路（不影响原生路径）：没有任何后端 provider 时直接返回；
        // 有也要先确认「本对话确实跑在后端上」才继续，避免每次原生停止都多算一次。
        val backends =
            settings.providers.filterIsInstance<me.rerere.ai.provider.ProviderSetting.Backend>()
        if (backends.isEmpty()) return
        // ① 执行后端指向的接入 provider
        val executionTargetId = settings.executionBackend
        val byExecution =
            backends.firstOrNull { it.id.toString() == executionTargetId }
                ?.takeIf { it.backendType == "backend" }
        // ② 本对话实际使用的模型所属 provider（提供商页配好、对话里直接选模型这条路径）
        val byModel =
            if (byExecution != null) {
                null
            } else {
                val conversation = runCatching { getConversationFlow(conversationId).value }.getOrNull()
                val assistant =
                    conversation?.let { settings.getAssistantById(it.assistantId) }
                        ?: settings.getCurrentAssistant()
                // 与界面同口径解析当前模型：会话指定（若助手允许）→ 助手指定 → 全局默认
                val model =
                    settings.findModelById(
                        conversation?.chatModelId?.takeIf { assistant.allowPerConversationModel }
                            ?: assistant.chatModelId ?: settings.chatModelId,
                    )
                // findProvider 返回父类型 ProviderSetting；这里只要 Backend 子类型
                model?.findProvider(settings.providers) as? me.rerere.ai.provider.ProviderSetting.Backend
            }
        val target = byExecution ?: byModel ?: return
        runCatching {
            me.rerere.ai.provider.providers.backend.BackendApi(
                baseUrl = target.baseUrl,
                username = target.username,
                password = target.password,
                token = target.token,
            ).cancel()
        }
    }
