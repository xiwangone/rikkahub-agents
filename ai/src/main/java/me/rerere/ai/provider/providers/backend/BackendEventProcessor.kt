package me.rerere.ai.provider.providers.backend

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.AskOption
import me.rerere.ai.ui.AskQuestion
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.util.json

/**
 * Backend SSE 事件循环的会话上下文。
 *
 * 把 BackendEventProcessor 构造器的 4 个会话相关参数收拢，避免 LongParameterList。
 */
internal data class BackendSessionContext(
    val sessionKey: String?,
    val sessionPaths: ConcurrentHashMap<String, String>,
    val sessionPathStore: SessionPathStore,
    val onSessionPath: (String) -> Unit,
)

/**
 * Backend SSE 事件循环处理器。
 *
 * 从 [BackendProvider.streamText] 抽出：backend serve 的 /events 是长连接（keep-alive），
 * 多 turn 任务在同一热流里连续推送事件，本类负责消费事件流并转为 [StreamChunk]。
 *
 * 状态说明：
 * - turn_done 后超过静默窗口无内容事件 → 任务完成 → 结束 flow
 * - 服务端等待用户应答（审批/提问）时进入 awaitingInteraction 态，放宽超时窗口
 */
internal class BackendEventProcessor(
    private val providerSetting: ProviderSetting.Backend,
    private val events: Flow<SseEvent>,
    private val interactionHandler: BackendInteractionHandler,
    private val sessionContext: BackendSessionContext,
) {
    private var usage: TokenUsage? = null
    private var textStarted = false
    private var reasoningStarted = false

    // 每个 turn 用独立的 part id：固定 id 在多 turn 下会被反复复用
    private var turnSeq = 0
    private val reasoningId: () -> String = { "reasoning-$turnSeq" }
    private val textId: () -> String = { "text-$turnSeq" }

    private var turnDone = false
    private var awaitingInteraction = false

    suspend fun FlowCollector<StreamChunk>.process() {
        while (true) {
            val event =
                withTimeoutOrNull(
                    when {
                        awaitingInteraction -> INTERACTION_WAIT_TIMEOUT_MS
                        turnDone -> TURN_DONE_IDLE_TIMEOUT_MS
                        else -> FIRST_CONTENT_TIMEOUT_MS
                    }
                ) {
                    // 事件异常兜底：流异常时返回 null → 由外层 break 优雅收尾
                    runCatching { events.first() }.getOrNull()
                } ?: break

            captureSessionPath(event)
            updateInteractionState(event)
            handleEvent(event)
        }

        // 流结束未补收尾，视作最后一个 turn 完成
        if (textStarted) {
            emit(StreamChunk.TextEnd(id = textId()))
            textStarted = false
        }
        if (reasoningStarted) {
            emit(StreamChunk.ReasoningEnd(id = reasoningId()))
            reasoningStarted = false
        }
        if (!turnDone) {
            emit(StreamChunk.Finish(finishReason = "stop"))
        }
    }

    /** 捕获 serve 会话路径（每条事件都带），用于后续 resume 复用。 */
    private suspend fun captureSessionPath(event: SseEvent) {
        sessionContext.sessionKey?.let { key ->
            event.sessionPath?.takeIf { it.isNotBlank() }?.let { path ->
                sessionContext.sessionPaths[key] = path
                sessionContext.onSessionPath(path)
                runCatching { sessionContext.sessionPathStore.put(key, path) }
            }
        }
    }

    /** 根据事件类型更新 turnDone / awaitingInteraction 状态。 */
    private fun updateInteractionState(event: SseEvent) {
        val isContent =
            event.kind in
                setOf(
                    "text", "reasoning", "tool_dispatch", "tool_result", "usage",
                    "message", "turn_started",
                )
        if (isContent) {
            turnDone = false
            awaitingInteraction = false
        }
        // 服务端发起交互请求（审批/提问）：进入「等待用户应答」态
        if (event.kind == "approval_request" || event.kind == "ask_request") {
            awaitingInteraction = true
            turnDone = false
        }
    }

    /**
     * 按事件类型分派；各族的处理在下方 handle* 方法里。
     *
     * 拆分说明：原实现是单个 ~150 行、环复杂度 45 的方法（detekt 阻断）。
     * 这里只搬位置、不改行为：每个分支的代码逐字保留。
     */
    private suspend fun FlowCollector<StreamChunk>.handleEvent(event: SseEvent) {
        when (event.kind) {
            "text", "reasoning" -> handleContentEvent(event)
            "tool_dispatch", "tool_result" -> handleToolEvent(event)
            "tool_progress" -> handleToolProgress(event)
            "usage" -> handleUsageEvent(event)
            "turn_done", "turn_started", "phase", "turn_phase", "notice", "message" ->
                handleLifecycleEvent(event)
            "approval_request", "ask_request" -> handleInteractionEvent(event)
            "compaction_started" -> emit(StreamChunk.CompactionStarted(event.compaction?.trigger))
            "compaction_done" -> emit(StreamChunk.CompactionDone(event.compaction?.trigger))
            else -> Unit // 其他未识别的非内容事件：忽略
        }
    }

    /** 内容流：文本与思考增量。 */
    private suspend fun FlowCollector<StreamChunk>.handleContentEvent(event: SseEvent) {
        when (event.kind) {
            "text" -> {
                val t = event.text ?: return
                if (!textStarted) {
                    emit(StreamChunk.TextStart(id = textId()))
                    textStarted = true
                }
                emit(StreamChunk.TextDelta(id = textId(), text = t))
            }

            "reasoning" -> {
                // serve 的 reasoning 帧把思考正文放在 text 字段；只读 reasoning 会丢掉思考
                val r = event.reasoning ?: event.text ?: return
                if (!reasoningStarted) {
                    emit(StreamChunk.ReasoningStart(id = reasoningId()))
                    reasoningStarted = true
                }
                emit(StreamChunk.ReasoningDelta(id = reasoningId(), text = r))
            }
        }
    }

    /** 服务端工具的开始与结果。 */
    private suspend fun FlowCollector<StreamChunk>.handleToolEvent(event: SseEvent) {
        when (event.kind) {
            "tool_dispatch" -> {
                val tool = event.tool
                if (tool != null) {
                    emit(
                        StreamChunk.ServerToolStart(
                            id = tool.id,
                            toolName = tool.name,
                            input = parseJsonOrNull(tool.args ?: tool.arguments),
                            metadata = toolMetadata(tool),
                        )
                    )
                }
            }

            "tool_result" -> {
                val tool = event.tool
                if (tool != null) {
                    val output = tool.output ?: tool.err ?: ""
                    emit(
                        StreamChunk.ServerToolEnd(
                            id = tool.id,
                            input = parseJsonOrNull(tool.args ?: tool.arguments),
                            output = parseJsonOrText(output),
                            status =
                                if (tool.err.isNullOrBlank()) {
                                    ServerToolStatus.COMPLETED
                                } else {
                                    ServerToolStatus.FAILED
                                },
                            metadata = toolMetadata(tool),
                        )
                    )
                }
            }
        }
    }

    /** 工具执行进度。 */
    private suspend fun FlowCollector<StreamChunk>.handleToolProgress(event: SseEvent) {
        when (event.kind) {
            "tool_progress" -> {
                val tool = event.tool
                if (tool != null) {
                    val text = tool.output ?: tool.err ?: ""
                    if (text.isNotBlank()) emit(StreamChunk.ToolProgress(tool.id, text))
                }
            }
        }
    }

    /** token 用量。 */
    private suspend fun FlowCollector<StreamChunk>.handleUsageEvent(event: SseEvent) {
        when (event.kind) {
            "usage" -> {
                val u = event.usage
                if (u != null) {
                    val tokenUsage =
                        TokenUsage(
                            promptTokens = u.promptTokens.toInt(),
                            completionTokens = u.completionTokens.toInt(),
                            cachedTokens = u.cacheHitTokens.toInt(),
                            totalTokens = u.totalTokens.toInt(),
                            cost = u.costUsd,
                        )
                    usage = tokenUsage
                    emit(StreamChunk.Usage(tokenUsage))
                }
            }
        }
    }

    /** 轮次与阶段等生命周期事件。 */
    private suspend fun FlowCollector<StreamChunk>.handleLifecycleEvent(event: SseEvent) {
        when (event.kind) {
            "turn_done" -> {
                turnDone = true
                if (textStarted) {
                    emit(StreamChunk.TextEnd(id = textId()))
                    textStarted = false
                }
                if (reasoningStarted) {
                    emit(StreamChunk.ReasoningEnd(id = reasoningId()))
                    reasoningStarted = false
                }
                emit(StreamChunk.Finish(finishReason = "stop"))
                // 不结束：多 turn 任务可能马上开始下一轮
            }

            "turn_started" -> {
                turnSeq++
                emit(StreamChunk.TurnStarted())
            }

            "phase", "turn_phase" -> {
                val label = event.detail ?: event.code ?: event.text ?: ""
                if (label.isNotBlank()) emit(StreamChunk.Phase(label))
            }

            "notice" -> {
                val text = event.text ?: event.detail ?: ""
                if (text.isNotBlank()) emit(StreamChunk.Notice(text, event.level))
            }

            // "message" 是 serve 的消息回显事件，不能再当作 Notice 发出

            "message" -> Unit
        }
    }

    /** 审批请求与提问请求。 */
    private suspend fun FlowCollector<StreamChunk>.handleInteractionEvent(event: SseEvent) {
        when (event.kind) {
            "approval_request" -> {
                val a = event.approval
                if (a != null) {
                    interactionHandler.onApprovalRequest(providerSetting, a.id, a.tool, a.subject)
                    emit(StreamChunk.ApprovalRequest(a.id, a.tool, a.subject))
                }
            }

            "ask_request" -> {
                val q = event.ask
                if (q != null) {
                    val questions =
                        q.questions.map { question ->
                            AskQuestion(
                                id = question.id,
                                prompt = question.prompt,
                                multi = question.multi,
                                options =
                                    question.options.map { opt ->
                                        AskOption(opt.label, opt.description)
                                    },
                            )
                        }
                    interactionHandler.onAskRequest(providerSetting, q.id, questions)
                    emit(
                        StreamChunk.AskRequest(
                            id = q.id,
                            questions = questions,
                        )
                    )
                }
            }
        }
    }

    /** 把工具参数（JSON 字符串）解析为结构化 JsonElement；解析失败返回 null。 */
    private fun parseJsonOrNull(s: String?): JsonElement? =
        s?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.parseToJsonElement(it) }.getOrNull()
        }

    /** 工具输出优先解析为 JSON；自由文本回退为 JsonPrimitive。 */
    private fun parseJsonOrText(s: String): JsonElement =
        runCatching { json.parseToJsonElement(s) }.getOrElse { JsonPrimitive(s) }

    /** 把工具的执行细节包装进 metadata；全部默认值时返回 null。 */
    private fun toolMetadata(tool: ToolPayload): JsonObject? {
        val hasDetail =
            tool.readOnly || tool.truncated || !tool.subject.isNullOrBlank() || tool.durationMs > 0
        if (!hasDetail) return null
        return buildJsonObject {
            put("readOnly", JsonPrimitive(tool.readOnly))
            put("truncated", JsonPrimitive(tool.truncated))
            tool.subject?.takeIf { it.isNotBlank() }?.let { put("subject", JsonPrimitive(it)) }
            if (tool.durationMs > 0) put("durationMs", JsonPrimitive(tool.durationMs))
        }
    }
}

// turn_done 之后判定「任务真正完成」的静默窗口。
// 取值不宜过短：多轮任务在 turn 之间可能有短暂的准备期，过短会误判为完成而提前收尾（表现为「没反应」）。
// 取值也不宜过长：serve 在任务结束后不再推送事件，窗口过长会让 UI 收尾与用量持久化明显滞后。
private const val TURN_DONE_IDLE_TIMEOUT_MS = 15_000L
// 非 turn_done 阶段的整体兜底超时：正常 SSE 流式下事件持续推送，此值仅用于
// 防止异常场景（连接挂起但无任何事件）无限转圈。补充 runCatching 异常兜底。
private const val FIRST_CONTENT_TIMEOUT_MS = 300_000L
// 服务端等待用户应答（审批/提问）时的静默上限：这类挂起是「等人」而非「已结束」，
// 给足思考与操作时间（与 FIRST_CONTENT_TIMEOUT_MS 同量级），避免卡片被提前收尾。
private const val INTERACTION_WAIT_TIMEOUT_MS = 300_000L
