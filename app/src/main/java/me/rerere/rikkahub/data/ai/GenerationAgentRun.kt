package me.rerere.rikkahub.data.ai

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.agent.FailureTrackingCollector
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.StepContext
import me.rerere.ai.agent.StepDecision
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.ToolCallOutcome
import me.rerere.ai.agent.ToolCallScope
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.ai.ui.mergeApprovalProgress
import me.rerere.rikkahub.R
import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.ai.tools.ToolErrors
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputTransformCache
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransformsIncremental
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.perf.RenderProfile
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.vault.AuditContext
import me.rerere.ai.util.redactSecrets
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private const val TAG = "GenerationAgentRun"

/** 收尾窗口下限：至少给模型 30 秒写总结。 */
private const val MIN_WRAP_UP_GRACE_MS = 30_000L

/** 收尾窗口上限：最多 5 分钟，避免长预算时把整轮拖太久。 */
private const val MAX_WRAP_UP_GRACE_MS = 5 * 60_000L

/**
 * 已启动工具的最小完成窗口。
 *
 * [beforeToolCall] 把工具超时定为 `remaining + grace`：预算耗尽时正在跑的工具会在中途被杀，
 * 工具结果里只剩一条超时包络（界面上是 `exit ?`），模型拿不到任何输出，也就无从收尾。
 * 预算只应决定「还能不能启动新工具」（拦截分支），不应成为已启动工具的死刑。
 */
private const val MIN_TOOL_COMPLETION_MS = 90_000L

/**
 * 收尾轮（预算到点后那次无工具的纯文本请求）的单次流上限。
 *
 * 这一段唯一的产出是一段总结，按 [MAX_STREAM_DURATION_MS] 等满既没有意义，
 * 也会让界面在提醒之后继续转圈很久。
 */
private const val WRAP_UP_STREAM_CAP_MS = 90_000L

/**
 * 收尾窗口 = 轮次预算的 20%，夹在 [MIN_WRAP_UP_GRACE_MS, MAX_WRAP_UP_GRACE_MS] 之间。
 * 20 分钟预算 → 4 分钟；1 分钟预算 → 30 秒（下限）。
 */
internal fun wrapUpGraceMsForBudget(turnBudgetMs: Long): Long =
    (turnBudgetMs / 5).coerceIn(MIN_WRAP_UP_GRACE_MS, MAX_WRAP_UP_GRACE_MS)

/**
 * 流墙钟上限是否已撞上。
 *
 * 收尾轮从首个 chunk 到达起算，不含 prefill/TTFT：长上下文的首包晚到不应直接吃掉
 * 整个总结窗口；首包未到（firstChunkAtMs == 0）时返回 false，首包阶段只由空闲看门狗兜底。
 * 普通轮仍从流开始计时。
 */
internal fun isStreamCapExceeded(
    wrapUpTerminalStep: Boolean,
    nowMs: Long,
    streamStartedAtMs: Long,
    firstChunkAtMs: Long,
): Boolean {
    if (wrapUpTerminalStep && firstChunkAtMs == 0L) return false
    val baselineMs = if (wrapUpTerminalStep) firstChunkAtMs else streamStartedAtMs
    val capMs = if (wrapUpTerminalStep) WRAP_UP_STREAM_CAP_MS else MAX_STREAM_DURATION_MS
    return nowMs - baselineMs > capMs
}

/** 收尾总结是否产出了可见文本：只有非空 Text 段才算，纯思考不算。 */
internal fun wrapUpSummaryHasText(parts: List<UIMessagePart>): Boolean =
    parts.any { it is UIMessagePart.Text && it.text.isNotBlank() }

/** 收尾提醒的 Append 必须带 terminalSummary=true，否则总结请求越不过 maxSteps。 */
internal fun terminalSummaryAppend(notice: UIMessage): StepDecision.Append =
    StepDecision.Append(notice, terminalSummary = true)

/**
 * 生成失败时把一条消息里仍 Auto/Pending 的工具落成确定性终态。
 *
 * 系统失败（5xx / 解析失败，与用户无关）不再借用 [ToolApprovalState.Denied] 装——
 * 那会把一次与用户无关的失败持久化成「用户拒绝」，续跑时模型还会据此调整后续行为。
 * 改为保留原审批态、补一个 `generation_failed` 信封：工具根本没跑，失败在生成环节，
 * 模型可在下一轮自行决定是否重发同一调用。已有输出的工具不动（它已经执行完了，
 * 再翻成失败是把成功误标成失败）。
 */
internal fun settleGenerationFailedTools(message: UIMessage, cause: Throwable): UIMessage {
    val newParts = message.parts.map { part ->
        if (part is UIMessagePart.Tool && !part.isExecuted) {
            when (part.approvalState) {
                ToolApprovalState.Auto, ToolApprovalState.Pending -> part.copy(
                    output = ToolErrors.partsFor(
                        ToolErrors.GENERATION_FAILED,
                        "Generation failed before this tool could run " +
                            "(${cause.javaClass.simpleName}: ${cause.message.orEmpty()}); " +
                            "the tool was not executed.",
                        hint = "The tool itself did not fail; retry the call in a later turn if still needed.",
                    ),
                )
                else -> part
            }
        } else {
            part
        }
    }
    return if (newParts == message.parts) message else message.copy(parts = newParts)
}

/**
 * 拒绝输出的分流：只有硬线拦截（安全底线）才返回信封，其余拒绝返回 null 走默认
 * `permission_denied`。
 *
 * 硬线 Denied 的理由里带 UI 用的内部标记（`hardline-ui:v1:<code>|…`）：直接发给模型
 * 会泄漏内部标记、还会被说成「用户拒绝」。此处先过
 * [HardlineCommandGuard.modelFacingReason] 去标记，再以 `hardline_blocked` / abort
 * 成信封，并把稳定分类码放进 `data` 供 UI 识别；无有效标记时原样返回 null。
 */
internal fun deniedToolOutputFor(reason: String): List<UIMessagePart>? {
    val modelReason = HardlineCommandGuard.modelFacingReason(reason)
    if (modelReason == reason) return null
    val code = HardlineCommandGuard.uiReasonCodeFromEncoded(reason)
    return ToolErrors.partsFor(
        ToolErrors.HARDLINE_BLOCKED,
        modelReason,
        extra = code?.let {
            ToolErrors.extraOf(HardlineCommandGuard.HARDLINE_REASON_CODE_FIELD to it)
        }.orEmpty(),
    )
}

/**
 * 续跑路径的硬线重查拒绝：与新发起路径（[deniedToolOutputFor]）同一结论。
 *
 * 错误码必须是 `hardline_blocked`（任何授权都越不过的安全底线），不能是
 * `invalid_argument`——后者会教模型「改改参数再试」，而这条命令永远跑不了。
 * hint 同样不能写「改参重试」，改写「不要经 agent 重试、真有需要去终端外跑」。
 */
internal fun hardlineRejectOutput(reason: String): List<UIMessagePart> {
    val reasonCode = HardlineCommandGuard.reasonCodeForMessage(reason)
    return listOf(
        UIMessagePart.Text(
            ToolErrors.textFor(
                error = ToolErrors.HARDLINE_BLOCKED,
                message = "blocked by safety floor (hardline): $reason. " +
                    "This command cannot run via the agent under any circumstances.",
                hint = "Do not retry this command via the agent. " +
                    "If genuinely needed, run it in a terminal outside the agent.",
                extra = reasonCode?.let {
                    ToolErrors.extraOf(HardlineCommandGuard.HARDLINE_REASON_CODE_FIELD to it)
                }.orEmpty(),
            ),
        ),
    )
}

/**
 * 工具失败输出按异常类型分流：墙钟超时（[TimeoutCancellationException]，预算掐掉）
 * 走 `tool_cancelled_wall_clock` / REQUEST_CANCELLED / abort；其余异常走
 * `tool_failed` / INTERNAL_ERROR / abort。两者 recovery 碰巧都是 abort，
 * 区别在归因：预算掐掉的工具被说成「工具内部出错」会误导模型与排障。
 */
internal fun toolFailureParts(error: Throwable): List<UIMessagePart> =
    when (error) {
        is TimeoutCancellationException -> ToolErrors.partsFor(
            ToolErrors.TOOL_CANCELLED_WALL_CLOCK,
            "Tool cancelled: wall-clock budget exceeded.",
            extra = mapOf("exception" to JsonPrimitive(error.javaClass.simpleName)),
        )
        else -> ToolErrors.partsFor(
            ToolErrors.TOOL_FAILED,
            (error.message ?: error.javaClass.simpleName).take(500),
            extra = mapOf("exception" to JsonPrimitive(error.javaClass.simpleName)),
        )
    }

/**
 * 一次生成轮回的执行体：把「请求模型 → 审批 → 执行工具 → 回灌」的排序交给 `me.rerere.ai.agent` 的循环，
 * 本类只保留这一层需要的东西 —— 请求组装、输出变换与投递节流、执行前的守卫判定、预算处置与归因计数。
 *
 * 每轮生成一个实例（可变状态都是本轮的）。
 */
// 装配类：参数随执行上下文增长（模型/会话/工具/钩子），拆构造只会把装配关系割裂到多处（同 ChatToolFactory 口径）。
@Suppress("LargeClass", "LongParameterList")
internal class GenerationAgentRun(
    private val context: Context,
    private val json: Json,
    private val toolOutputProcessor: ToolOutputProcessor,
    private val aiLoggingManager: AILoggingManager,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val conversationRepo: ConversationRepository,
    emitter: FlowCollector<GenerationChunk>,
    private val runCtx: GenerationRunContext,
    private val settings: Settings,
    private val model: Model,
    private val execModel: Model,
    private val provider: ProviderSetting,
    private val providerImpl: Provider<ProviderSetting>,
    private val assistant: Assistant,
    private val initialMessages: List<UIMessage>,
    private val memories: List<AssistantMemory>,
    private val tools: List<Tool>,
    private val inputTransformers: List<MessageTransformer>,
    private val outputTransformers: List<OutputMessageTransformer>,
    private val maxSteps: Int,
    private val renderProfile: RenderProfile,
    private val processingStatus: kotlinx.coroutines.flow.MutableStateFlow<String?>,
    private val onRetryDiagnosed: ((FailureDiagnosis) -> Unit)?,
    private val onAfterToolExecution: suspend (List<UIMessage>) -> List<UIMessage>?,
    private val onBeforeModelRequest: suspend () -> Unit,
    /** 用户在生成期间是否排了补充消息（只查不取：消费必须由 turn-end 派发统一做，否则消息会丢）。 */
    private val hasQueuedMessages: () -> Boolean = { false },
    private val isToolAutoApproved: suspend (String) -> Boolean,
    private val systemAddendum: String?,
    private val conversationSystemPrompt: String?,
    private val conversationId: Uuid?,
    private val conversationModeInjectionIds: Set<Uuid>,
    private val conversationLorebookIds: Set<Uuid>,
    private val workspaceCwd: String?,
) : AgentHooks {

    private val emitter = FailureTrackingCollector(emitter)

    private var messages: List<UIMessage> = initialMessages

    private val turnStartMs = android.os.SystemClock.elapsedRealtime()
    private val outputTransformCache = OutputTransformCache()

    private var lastOutputFlushAtMs = 0L
    private val pendingOutputFlush = PendingOutputFlush<List<UIMessage>>()
    // All persisted assistant messages are baselines, including history before a new user turn.
    private val usageLedger = me.rerere.rikkahub.costguards.CompletedUsageLedger(initialMessages)

    private var loopGuardTripCount = 0
    private var wrapUpInjected = false

    /** 收尾提醒已安排但最终请求尚未开始（工具结果附提醒或步边界注入）。 */
    private var wrapUpFinalRequestPending = false

    /** 最终纯文本总结请求已发出；下一步边界结束本轮。 */
    private var wrapUpFinalRequestInFlight = false

    /**
     * 预算耗尽后进入的收尾轮：不向模型挂任何工具，只能产出一段文字总结。
     * 否则模型可能在这一轮再次发起工具调用（长任务尤其容易），下一次步边界就被硬 Stop，
     * 用户依旧拿不到任何收尾文字。
     */
    private var wrapUpTerminalStep = false
    /** 收尾总结请求已发出（prepareStep 里置位）：步边界据此判断总结是否已完成。 */
    private var wrapUpSummaryIssued = false
    /** 收尾总结本步是否产出了可见文本（finishStep 里按本步新段判定）。 */
    private var wrapUpSummaryProducedText = false
    private var tokenBudgetWarnInjected = false
    private var autoContinueCount = 0
    private var imageDowngradeApplied = false

    private var pendingAutoContinue = false

    private val maxStreamRetries = if (settings.enableAutoRetry) settings.responseStreamMaxRetries else 0

    // 本次（重）订阅的状态：与逐块投递判定一一对应
    private var streamAttempt = 0
    private val streamProgress = GenerationStreamProgress()
    private var streamStartedAtMs = 0L
    /** 本次流尝试首个 chunk 到达的时刻（0 = 未到）：收尾轮 90 秒上限的起算点。 */
    private var firstStreamChunkAtMs = 0L
    private var lastStreamApplyAtMs = 0L
    private val pendingStreamChunks = mutableListOf<StreamChunk>()
    // 早止点已判定，但要等「下一条」增量到手才真的结束流（收尾见 guardStopAfterCurrentChunk）
    private var streamGuardStopRequested = false
    private var textRepetitionDetector = OutputRepetitionDetector()
    private var summaryRepetitionDetector = OutputRepetitionDetector()
    private var reasoningRepetitionDetector = newReasoningRepetitionDetector()
    /** 思考流的一次「软信号」额度：首次命中只提醒并重置检测器，二次命中才真正掉流。 */
    private var reasoningRepetitionSoftSignaled = false

    private val retryPolicy = RetryPolicy(
        maxRetries = maxStreamRetries,
        // 只重试「传输类」失败：已收到有意义输出、内容风控、上下文超限、客户端 4xx、取消都不重放
        retryOn = { failure ->
            shouldRetryGenerationStreamFailure(
                failure = failure,
                retryAttempt = streamAttempt.toLong(),
                maxRetries = maxStreamRetries,
                receivedMeaningfulOutput = streamProgress.receivedMeaningfulOutput,
            )
        },
        delayFor = { retry -> generationStreamRetryDelayMs((retry - 1).toLong()).milliseconds },
        // 服务端 Retry-After 优先：失败里带了就按它等，而不是固定退避。
        delayForFailure = { retry, failure ->
            generationStreamRetryDelayMs((retry - 1).toLong(), failure).milliseconds
        },
    )

    private val request: AgentRequest
        get() = AgentRequest(
            provider = providerImpl,
            providerSetting = provider,
            messages = messages,
            tools = tools,
            stream = assistant.streamOutput,
            // 预算收尾最多额外保留一次无工具总结请求；普通轮次仍由 beforeStep 限制在 maxSteps。
            maxSteps = maxSteps + 1,
            retry = retryPolicy,
        )

    /**
     * 驱动一次生成轮回。图片不匹配的失败驱动降级只允许一次：降级后重跑整轮（[messages] 已被剥图，
     * 循环会从同一条助手消息继续）。其余失败按原路径向外抛出，由调用方（生成流程外壳）收尾。
     */
    // 有意 catch Throwable：降级/重试要按失败原因分流；取消用类型判断单独上抛（降级判定本身需要先拿到异常对象，
    // 无法用多 catch 表达顺序）。
    // CyclomaticComplexMethod/NestedBlockDepth：单轮生成的分支由协议差异与降级策略共同决定，拆函数会让同一次失败被多处裁定；
    // ThrowingExceptionFromFinally：收尾阶段必须把已锁存的投递失败上抛，否则会被静默吞掉。
    @Suppress(
        "TooGenericExceptionCaught",
        "InstanceOfCheckForException",
        "CyclomaticComplexMethod",
        "NestedBlockDepth",
        "ThrowingExceptionFromFinally",
    )
    suspend fun start() {
        while (true) {
            val requestStartedAtMs = android.os.SystemClock.elapsedRealtime()
            var skipFinalDelivery = false
            var completedNormally = false
            // 本轮重试计数：每轮 while 迭代开始时重置；失败汇总在 catch 块末尾只记 1 条。
            var retryCount = 0
            var lastRetryCause: Throwable? = null
            try {
                runAgent(request, this).collect { event ->
                    when (event) {
                        is AgentEvent.MessageUpdated -> onMessages(listOf(event.message))
                        is AgentEvent.Retrying -> {
                            processingStatus.value = retryStatusText(
                                context = context,
                                retryNumber = event.attempt.retry.toLong(),
                                maxRetries = maxStreamRetries,
                                failure = event.attempt.cause,
                            )
                            // 每 attempt 不再记 1 条 w：只计数，失败汇总在 catch 块末尾记 1 条。
                            retryCount++
                            lastRetryCause = event.attempt.cause
                            onRetryDiagnosed?.invoke(diagnoseFailure(context, event.attempt.cause))
                        }

                        is AgentEvent.Finished -> {
                            accountCompletedUsage(event.message)
                            runCtx.finishReason = event.message.finishReason ?: runCtx.finishReason
                        }
                    }
                }
                completedNormally = true
                return
            } catch (t: Throwable) {
                skipFinalDelivery = emitter.failure != null || t is Error ||
                    isCancellationFailure(t) || t.isFlowTransparencyViolation()
                emitter.throwIfFailed()
                if (t is Error || isCancellationFailure(t)) throw t
                if (t.isFlowTransparencyViolation()) {
                    // Do not attempt recovery emissions on a collector that violated transparency.
                    AppLog.e(TAG, "streamText: event sink broken (flow transparency violation), generation aborted", t)
                    throw t
                }
                runCtx.modelMs += android.os.SystemClock.elapsedRealtime() - requestStartedAtMs
                if (!imageDowngradeApplied &&
                    shouldDowngradeImagesOnFailure(t, t.message.orEmpty()) &&
                    messages.any { msg -> msg.parts.any { part -> part is UIMessagePart.Image } }
                ) {
                    imageDowngradeApplied = true
                    val stripped = stripImagePartsForUnsupportedModel(messages)
                    messages = stripped.messages
                    AppLog.w(
                        TAG,
                        "generateText: model reported images unsupported; retrying once without " +
                            "${stripped.replaced} image part(s)",
                    )
                    processingStatus.value = context.getString(R.string.error_kind_image_unsupported)
                    emitter.emit(GenerationChunk.Messages(messages))
                    continue
                }
                // 服务 5xx、JSON 解析失败、分块合并期异常等：把仍处在 Auto/Pending 的工具落成确定性
                // 的 generation_failed 包络，否则下一轮回放会拿到半截状态的工具 part。审批态保留、
                // 只补输出——借用 Denied 会把系统失败说成「用户拒绝」（见 settleGenerationFailedTools）。
                val lastMsg = messages.lastOrNull()
                if (lastMsg != null) {
                    messages = messages.dropLast(1) + settleGenerationFailedTools(lastMsg, t)
                    emitter.emit(GenerationChunk.Messages(messages))
                }
                // 本轮重试汇总：只在真重试过时记 1 条（含次数 + 最后错误），
                // 替代原来每 attempt 1 条 w；未重试过的单次失败不另记（调用方
                // ChatServiceSendOps 的 msg-fail 行已留痕）。
                if (retryCount > 0) {
                    AppLog.w(
                        TAG,
                        "streamText: failed after $retryCount ${if (retryCount == 1) "retry" else "retries"} " +
                            "(last error: ${lastRetryCause?.message ?: t.message})",
                        t,
                    )
                }
                throw t
            } finally {
                // 收尾投递：合并窗口里被跳过的最后一批快照补上（审批状态单调回贴）
                if (skipFinalDelivery || emitter.failure != null) pendingOutputFlush.clear()
                try {
                    flushPendingOutput()
                } catch (error: Throwable) {
                    emitter.throwIfFailed()
                    if (error is Error || isCancellationFailure(error)) throw error
                    if (completedNormally) {
                        // 正常结束路径下收尾投递失败 = 最后一批输出丢失：不能按成功收尾，上抛。
                        throw error
                    }
                    // 失败路径下不掩盖原始异常，只记日志（原始异常仍由 catch 块上抛）。
                    AppLog.w(TAG, "flushPendingOutput failed on the failure path", error)
                }
            }
        }
    }

    /** 用户主动停止时的收尾：与正常完成走同一条变换链，让落盘的形状与逐次生成一致 */
    suspend fun finalizeCancellation() {
        // Cancellation can arrive after a request completed but before the loop's
        // next boundary/Finished event accounts it. Partial previews keep the prior
        // stepCount, so this remains idempotent and never bills an interrupted attempt.
        messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.let { accountCompletedUsage(it) }
        messages = messages.visualTransformsIncremental(
            transformers = outputTransformers,
            cache = outputTransformCache,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
        )
        messages = messages.onGenerationFinish(
            transformers = outputTransformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
        )
        if (messages.isNotEmpty()) {
            messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
            )
        }
        emitter.emit(GenerationChunk.Messages(messages))
    }

    // ── 逐块投递（原 onUpdateMessages）───────────────────────────────────────────

    private suspend fun onMessages(updated: List<UIMessage>) {
        // 循环维护的是本轮的助手消息；历史部分以本层为准
        val last = updated.lastOrNull() ?: return
        val previous = messages
        messages = upsertMessage(previous, last)

        val nowFlushMs = android.os.SystemClock.elapsedRealtime()
        val effectiveFlushWindowMs = (renderProfile.outputFlushIntervalMs * 3L) / 4L
        if (nowFlushMs - lastOutputFlushAtMs >= effectiveFlushWindowMs) {
            lastOutputFlushAtMs = nowFlushMs
            pendingOutputFlush.clear()
            messages = messages
                .transforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                ).mergeApprovalProgress(previous)
            emitter.emit(
                GenerationChunk.Messages(
                    messages.visualTransformsIncremental(
                        transformers = outputTransformers,
                        cache = outputTransformCache,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings,
                    ),
                ),
            )
        } else {
            // 合并窗口内：只记录最新快照，跳过的中间帧由下一次投递或 step 边界/结束时补齐。
            // 审批状态单调：该快照不含本轮写入的审批迁移，回灌时必须回贴，
            // 否则会把 Pending 打回 Auto（审批按钮一闪即没）。
            messages = messages.mergeApprovalProgress(previous)
            pendingOutputFlush.set(messages.mergeApprovalProgress(previous))
        }
    }

    /** Deliver the latest coalesced snapshot through the normal transforms and approval merge. */
    private suspend fun flushPendingOutput() {
        pendingOutputFlush.flush { pending ->
            lastOutputFlushAtMs = android.os.SystemClock.elapsedRealtime()
            messages = pending
                .transforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                ).mergeApprovalProgress(messages)
            emitter.emit(
                GenerationChunk.Messages(
                    messages.visualTransformsIncremental(
                        transformers = outputTransformers,
                        cache = outputTransformCache,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings,
                    ),
                ),
            )
        }
    }

    /** Account each completed request delta; [UIMessage.usage] is already cumulative. */
    private suspend fun accountCompletedUsage(message: UIMessage) {
        val incurred = usageLedger.takeDelta(message) ?: return
        // Each request's full prompt is billable even though later prompts overlap prior context.
        val stepPromptTokens = incurred.promptTokens.coerceAtLeast(0)
        runCtx.promptTokens += stepPromptTokens
        runCtx.completionTokens += incurred.completionTokens.coerceAtLeast(0)
        runCtx.cachedTokens += incurred.cachedTokens.coerceIn(0, stepPromptTokens)
        incurred.cost?.let { stepCost ->
            runCtx.cost += stepCost.coerceAtLeast(0.0)
            runCtx.costReported = true
        }
        emitter.emit(GenerationChunk.UsageIncurred(incurred))
    }

    // ── AgentHooks ────────────────────────────────────────────────────────────

    override suspend fun prepareStep(kernelMessages: List<UIMessage>): StepRequest {
        // 步边界：新建助手消息时把三个重复检测档案换成本步的
        if (messages.lastOrNull()?.role != MessageRole.ASSISTANT ||
            messages.lastOrNull()?.parts?.isEmpty() != false
        ) {
            textRepetitionDetector = OutputRepetitionDetector()
            summaryRepetitionDetector = OutputRepetitionDetector()
            reasoningRepetitionDetector = newReasoningRepetitionDetector()
            reasoningRepetitionSoftSignaled = false
        }
        outputTransformCache.clear()

        // 收尾轮不挂工具：预算已耗尽（或进入宽限窗口），这一轮只允许产出一段文字收尾。
        val stepTools = if (wrapUpTerminalStep) emptyList() else tools
        // 标记总结请求已发出：下一轮步边界据此判断总结是否已完成（供零文本兜底用）。
        if (wrapUpTerminalStep) wrapUpSummaryIssued = true

        // Tool execution can finish after beforeStep has already run. Flush at the
        // final boundary before asking the provider for the next step.
        flushPendingOutput()

        // 生成期间排队的消息**不进本轮上下文**：它是「中途插话」，应在本次回合结束后作为
        // 新的一轮发出（turn-end 的 dispatchNextQueuedMessage → sendMessageNow）。
        // 早先的两种做法都踩过坑：放在 afterToolExecution 会无条件 drain（模型随后 stop 时
        // 消息被吞、没有回复）；放在 prepareStep 虽然能进上下文，但回复并进当前 assistant
        // 消息，UI 上表现为「转圈后停下、没有独立回复」。

        // 清零点：丢弃上一次请求（异常/取消路径可能）遗留的引用命中，避免归属串档
        ProviderKeyAudit.reset()
        val internalMessages = buildList {
            val allowConversationPrompt = assistant.allowConversationSystemPrompt ||
                (conversationId != null && HeadlessConversations.isHeadless(conversationId))
            val effectiveSystemPrompt =
                if (allowConversationPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                    conversationSystemPrompt
                } else {
                    assistant.systemPrompt
                }
            val memoryPrompt = if (assistant.enableMemory) {
                buildMemoryPrompt(memories = memories)
            } else {
                ""
            }
            val recentChatsPrompt = if (assistant.enableRecentChatsReference) {
                buildRecentChatsPrompt(assistant, conversationRepo)
            } else {
                ""
            }
            val toolPrompts = stepTools.map { tool -> tool.systemPrompt(model, messages) }
            val (stableSystem, volatileSystem) = systemPromptBuilder.buildSections(
                assistantPrompt = effectiveSystemPrompt,
                memoryPrompt = memoryPrompt,
                recentChatsPrompt = recentChatsPrompt,
                toolPrompts = toolPrompts,
                systemAddendum = systemAddendum,
            )
            ContextLedger.record(
                context,
                ContextLedgerSnapshot(
                    assistantPrompt = effectiveSystemPrompt.length,
                    memory = memoryPrompt.length,
                    recentChats = recentChatsPrompt.length,
                    toolPrompts = toolPrompts.sumOf { prompt -> prompt.length },
                    addendum = systemAddendum?.length ?: 0,
                    stable = stableSystem.length,
                    volatile = volatileSystem.length,
                    toolCount = stepTools.size,
                    atMs = System.currentTimeMillis(),
                ),
            )
            val systemParts = buildList {
                if (stableSystem.isNotBlank()) add(UIMessagePart.Text(stableSystem))
                if (volatileSystem.isNotBlank()) add(UIMessagePart.Text(volatileSystem))
            }
            if (systemParts.isNotEmpty()) {
                add(UIMessage(role = MessageRole.SYSTEM, parts = systemParts))
            }
            addAll(
                messages
                    .limitContext(assistant.contextMessageLimit)
                    .ageOldToolImages()
                    .withCheckpointCalibration()
                    .mergeCheckpointIntoUserTurn(),
            )
        }.transforms(
            transformers = inputTransformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
            hasReadableFileTool = stepTools.any { it.name in FILE_READ_TOOL_NAMES },
        )

        val params = TextGenerationParams(
            model = if (assistant.streamOutput) model else execModel,
            temperature = assistant.temperature,
            topP = assistant.topP,
            frequencyPenalty = assistant.frequencyPenalty,
            presencePenalty = assistant.presencePenalty,
            maxTokens = assistant.maxTokens,
            maxStreamRetries = maxStreamRetries,
            tools = stepTools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            },
            sessionId = (conversationId ?: Uuid.random()).toString(),
        )
        aiLoggingManager.addLog(
            AILogging.Generation(
                params = params,
                messages = internalMessages,
                providerSetting = provider,
                stream = assistant.streamOutput,
            ),
        )
        onBeforeModelRequest()
        return StepRequest(internalMessages, params, toolsEnabled = !wrapUpTerminalStep)
    }

    // 三条流各一套检测器 + 窗口合并，分支数由协议复杂度决定；拆开会让同一事件被多次裁定。
    @Suppress("CyclomaticComplexMethod")
    override fun interceptStream(chunks: Flow<StreamChunk>): Flow<StreamChunk> = chunks
        .withIdleWatchdog(
            idleMs = assistant.streamIdleTimeoutMs,
            // 首包等超过 60 秒还没到：处理状态从通用“生成中”换成阶段提示，让用户分清
            // 是在排队/prefill 还是卡死；首个 chunk 一到就清除。
            onWaitingFirstChunk = {
                processingStatus.value = context.getString(R.string.chat_page_waiting_first_chunk)
            },
            onFirstChunk = { clearRetryStatus(processingStatus) },
        )
        .trackGenerationStreamProgress(
            progress = streamProgress,
            onMeaningfulOutput = { clearRetryStatus(processingStatus) },
            onNonMeaningfulCompletion = {
                AppLog.w(
                    TAG,
                    "streamText: stream closed after chunks arrived but none contained " +
                        "parseable parts; ending without retry",
                )
            },
        ).takeWhile { chunk ->
            // 守卫在上一条已判定结束：本条起全部丢弃，并让 takeWhile 掐断连接（失控的流不能继续烧 token）
            if (streamGuardStopRequested) return@takeWhile false
            // 墙钟上限：永不结束的流（无限「思考」）只能在这里掐掉，重复检测对它无感。
            // 收尾轮（预算到点后的纯文本总结）单独收紧：这一段没有任何工具，唯一产出是
            // 一段文字，按 15min 上限等下去只会让界面一直转圈。
            // 收尾轮的上限从首个 chunk 到达起算，不含 prefill/TTFT（见 isStreamCapExceeded）。
            if (firstStreamChunkAtMs == 0L) firstStreamChunkAtMs = System.currentTimeMillis()
            if (isStreamCapExceeded(wrapUpTerminalStep, System.currentTimeMillis(), streamStartedAtMs, firstStreamChunkAtMs)) {
                AppLog.w(TAG, "streamText: stream exceeded max duration; ending this turn's stream early")
                guardStopAfterCurrentChunk(GenerationOutcome.TIMEOUT)
                return@takeWhile true
            }
            // 输出重复检测：三条轨各自判定；正常结束流（不抛异常）→ 不进重试策略
            val deltaText = when (chunk) {
                is StreamChunk.TextDelta -> chunk.text
                is StreamChunk.ReasoningDelta -> chunk.text
                else -> null
            }
            val detector = when (chunk) {
                is StreamChunk.TextDelta -> textRepetitionDetector
                is StreamChunk.ReasoningDelta ->
                    if (chunk.reasoningType == ReasoningType.SUMMARY_TEXT) summaryRepetitionDetector else reasoningRepetitionDetector
                else -> null
            }
            if (deltaText != null && detector != null && detector.feed(deltaText)) {
                val streamKind = when (detector) {
                    reasoningRepetitionDetector -> "reasoning"
                    summaryRepetitionDetector -> "summary"
                    else -> "text"
                }
                // tripDetail = 匹配到的「片段长度 × 重复次数」：真失控与长上下文里反复引用同一固定短语
                // （误触发）只有这个数能分开，光一句「检测到重复」没法复盘。
                val detail = detector.tripDetail()
                if (detector === reasoningRepetitionDetector && !reasoningRepetitionSoftSignaled) {
                    // 思考流首次命中只记软信号并重置检测器：正常思考引用同一片段极常见，
                    // 直接掉流会让整条回复提前结束。二次命中才按失控处理。
                    reasoningRepetitionSoftSignaled = true
                    reasoningRepetitionDetector = newReasoningRepetitionDetector()
                    AppLog.w(
                        TAG,
                        "streamText: repetition suspected in reasoning stream ($detail); " +
                            "soft signal, stream continues",
                    )
                } else {
                    AppLog.w(
                        TAG,
                        "streamText: runaway repetition detected in $streamKind stream " +
                            "($detail); ending this turn's stream early",
                    )
                    guardStopAfterCurrentChunk(GenerationOutcome.LOOP_GUARD)
                }
            }
            true
        }.coalesceStreamDeltas(renderProfile.streamApplyIntervalMs)

    /**
     * 早止点的收尾：登记归因，并把「结束流」推迟到下一条增量。
     *
     * 不在本条就返回 false：takeWhile 会把「判定为 false 的那一条」一起丢掉，而那半截增量是已经收到的
     * 内容 —— 思考卡缺的就是这一条。代价只有一条：下一条（按定义是重复段本身，没有新信息）被丢掉，
     * 连接依旧立刻掐断、不继续烧 token。
     *
     * 归因不覆盖已有值：墙钟 / token 预算 / 工具循环保护先写的那条更贴近本轮真正的终点。
     */
    private fun guardStopAfterCurrentChunk(outcome: GenerationOutcome) {
        streamGuardStopRequested = true
        if (runCtx.abortReason == null) runCtx.abortReason = outcome
    }

    override suspend fun onStreamAttemptReset(attempt: Int) {
        // 诊断（只观测）：重试会清空未投递的缓冲 —— 若这里有内容，说明该 attempt 已生成的
        // 内容被丢弃。用户报过「思考开头/正文末尾断掉」，这是最可疑的一处。
        if (pendingStreamChunks.isNotEmpty()) {
            val textChars =
                pendingStreamChunks.filterIsInstance<StreamChunk.TextDelta>().sumOf { it.text.length }
            val reasoningChars =
                pendingStreamChunks.filterIsInstance<StreamChunk.ReasoningDelta>().sumOf { it.text.length }
            AppLog.w(
                TAG,
                "stream-attempt-reset attempt=$attempt dropped=${pendingStreamChunks.size} " +
                    "chunks (text=$textChars chars, reasoning=$reasoningChars chars)",
            )
        } else {
            AppLog.i(TAG, "stream-attempt-reset attempt=$attempt (buffer empty)")
        }
        streamAttempt = attempt
        streamProgress.reset()
        streamStartedAtMs = System.currentTimeMillis()
        firstStreamChunkAtMs = 0L
        lastStreamApplyAtMs = 0L
        pendingStreamChunks.clear()
        streamGuardStopRequested = false
    }

    /**
     * 审批决策：先查硬线（任何授权都越不过），再按声明判定；需要审批且本轮未被「总是允许」
     * 覆盖时翻成 Pending。每次都是**新鲜**读取，避免刚授予的许可被上一刻的快照漏掉。
     */
    override suspend fun decideApproval(tool: UIMessagePart.Tool, declaredApproval: Boolean): ToolApprovalState {
        val diagnostic =
            "approval-check ${tool.toolName}: declared=$declaredApproval " +
                "state=${tool.approvalState} available=${tools.size}"
        if (declaredApproval) AppLog.i(TAG, diagnostic) else AppLog.d(TAG, diagnostic)
        val hardlineReason = HardlineCommandGuard.checkTool(tool.toolName, tool.input)
        if (hardlineReason != null && tool.approvalState is ToolApprovalState.Auto) {
            AppLog.w(TAG, "hardline-blocked ${tool.toolName}: $hardlineReason")
            val modelMessage =
                "blocked by safety floor (hardline): $hardlineReason. " +
                    "This command cannot run via the agent under any " +
                    "circumstances. If the user genuinely needs it, they " +
                    "should run it themselves in a terminal outside the agent."
            return ToolApprovalState.Denied(HardlineCommandGuard.encodeUiReason(hardlineReason, modelMessage))
        }
        if (declaredApproval && tool.approvalState is ToolApprovalState.Auto) {
            return if (isToolAutoApproved(tool.toolName)) {
                AppLog.i(TAG, "approval-auto ${tool.toolName}: auto-approved, running without prompt")
                ToolApprovalState.Auto
            } else {
                AppLog.i(TAG, "approval-pending ${tool.toolName}: marked Pending, waiting for user")
                ToolApprovalState.Pending
            }
        }
        return tool.approvalState
    }

    /**
     * 执行前守卫：循环签名（键序/空白归一后比对）→ 参数解析 → 工具可用性 → 面包屑 → 墙钟预算。
     * 判定必须发生在执行之前，拦截时注入结构化包络让模型改道。
     */
    override suspend fun beforeToolCall(
        tool: UIMessagePart.Tool,
        parsedArgs: JsonElement?,
        scope: ToolCallScope,
    ): ToolCallOutcome {
        // 续跑路径也要再过一次硬线：老库里持久化成 Approved 的调用不能绕过安全底线
        val resumeHardlineReason = HardlineCommandGuard.checkTool(tool.toolName, tool.input)
        if (resumeHardlineReason != null) {
            AppLog.w(TAG, "generateText: resume-path hardline re-check blocked ${tool.toolName}: $resumeHardlineReason")
            return ToolCallOutcome.Reject(hardlineRejectOutput(resumeHardlineReason))
        }
        // 用户/硬线拒绝的包络在循环之外已成型（Denied/Answered 分支），这里只处理要执行的那些
        val signature = LoopGuard.signature(toolName = tool.toolName, input = tool.input, parsedArgs = parsedArgs)
        val turnStartIndex = messages.indexOfLast { it.role == MessageRole.USER && !it.isSynthetic }
        val turnSlice = messages.subList((turnStartIndex + 1).coerceAtLeast(0), messages.size)
        val priorCalls = turnSlice.flatMap { msg ->
            val epochMs = (msg.finishedAt ?: msg.createdAt)
                .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            msg.parts.filterIsInstance<UIMessagePart.Tool>()
                .filter { it.isExecuted }
                .map { PriorToolCall(it.toolName, LoopGuard.signatureOf(json, it.toolName, it.input), epochMs) }
        }
        val loopDecision = LoopGuard.evaluate(
            priorCalls = priorCalls,
            toolName = tool.toolName,
            signature = signature,
            nowMs = System.currentTimeMillis(),
        )
        if (loopDecision.block) {
            loopGuardTripCount++
            AppLog.w(
                TAG,
                "generateText: loop-guard tripped on $signature (${loopDecision.priorOccurrences + 1} repeat, " +
                    "trip #$loopGuardTripCount this turn); injecting bail-out envelope",
            )
            return ToolCallOutcome.Intercept(
                listOf(
                    UIMessagePart.Text(
                        json.encodeToString(
                            ToolErrors.envelopeFor(
                                error = "loop_detected",
                                message = "Loop detected: repeated identical tool calls without progress",
                                hint = "You have already called ${tool.toolName} with identical arguments " +
                                    "${loopDecision.priorOccurrences} time(s) in this turn without making progress. " +
                                    "Stop retrying. Either: (a) change the args meaningfully, (b) try a different " +
                                    "tool that addresses the underlying request, or (c) hand back to the user with " +
                                    "what you have so far. Examples: for 'search X in chrome' use " +
                                    "open_url(\"https://www.google.com/search?q=X\") instead of fighting Chrome's " +
                                    "URL bar via set_text; for terminal tasks use termux_run_command instead of " +
                                    "typing into Termux.",
                            ),
                        ),
                    ),
                ),
            )
        }
        if (parsedArgs == null) {
            AppLog.w(TAG, "tool ${tool.toolName} args failed to parse (likely truncated stream)")
            return ToolCallOutcome.InvalidArgs
        }
        val toolDef = tools.find { it.name == tool.toolName }
        if (toolDef == null) {
            AppLog.w(TAG, "tool ${tool.toolName} not found among ${tools.size} tools available this turn")
            return ToolCallOutcome.Intercept(
                listOf(
                    UIMessagePart.Text(
                        json.encodeToString(
                            ToolErrors.envelopeFor(
                                error = "tool_not_found",
                                message = "Tool '${tool.toolName}' was called but is not among the tools available this turn.",
                                hint = "Call get_tool_schema with the exact tool name, then retry.",
                                extra = ToolErrors.extraOf(
                                    "tools_available_this_turn" to JsonPrimitive(
                                        tools.joinToString(", ") { it.name }.take(1500),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        if (me.rerere.rikkahub.BuildConfig.DEBUG) {
            AppLog.i(TAG, "generateText: executing tool ${toolDef.name} with args: ${redactSecrets(parsedArgs)}")
        }
        // 执行前先落「已开始执行」面包屑：进程被杀后重放时能看到「起过手但无输出」，拒绝静默重跑
        scope.publish(tool.copy(executionStartedAt = System.currentTimeMillis()))
        val remainingMs = ToolRuntimeLimits.turnBudgetMs -
            (android.os.SystemClock.elapsedRealtime() - turnStartMs)
        val wrapUpGraceMs = wrapUpGraceMsForBudget(ToolRuntimeLimits.turnBudgetMs)
        if (wrapUpInjected || remainingMs <= wrapUpGraceMs) {
            // 软预算：不再启动新工具。将提醒作为被拦截工具的结果返回，
            // 随后 beforeStep 追加唯一的无工具总结请求。
            wrapUpInjected = true
            wrapUpTerminalStep = true
            wrapUpFinalRequestPending = true
            AppLog.w(TAG, "generateText: ${toolDef.name} skipped — entering wrap-up window (${remainingMs}ms left)")
            return ToolCallOutcome.Intercept(
                listOf(UIMessagePart.Text(context.resources.getString(R.string.ai_wrap_up_notice))),
            )
        }
        return ToolCallOutcome.Execute(
            // 已开始的工作给足最小完成窗口后必须真正跑完（见 MIN_TOOL_COMPLETION_MS）；超预算后的收尾由 beforeStep 负责。
            timeout = (remainingMs + wrapUpGraceMs).coerceAtLeast(MIN_TOOL_COMPLETION_MS).milliseconds,
            context = AuditContext(
                conversationId = conversationId?.toString(),
                modelId = model.modelId,
                assistantId = assistant.id.toString(),
                source = "ai-tool",
            ),
        )
    }

    /** 工具失败/被墙钟掐掉的包络：错误码 + 截断后的简述，不把整段栈回灌给模型 */
    override suspend fun toolFailureOutput(tool: UIMessagePart.Tool, error: Throwable): List<UIMessagePart> {
        AppLog.w(TAG, "tool ${tool.toolName} threw", error)
        return toolFailureParts(error)
    }

    /**
     * 拒绝输出的分流：硬线拦截（安全底线）走 `hardline_blocked` 信封（去 UI 内部标记），
     * 其余拒绝返回 null、由循环落默认 `permission_denied` 信封。
     */
    override suspend fun deniedToolOutput(tool: UIMessagePart.Tool, reason: String): List<UIMessagePart>? =
        deniedToolOutputFor(reason)

    /** 执行成功、写回之前：脱敏 → 审批出处 → 超限落盘 + 预览/摘要 */
    override suspend fun processToolOutput(
        tool: UIMessagePart.Tool,
        output: List<UIMessagePart>,
    ): List<UIMessagePart> {
        val hasShellAccess = tools.any { it.name == "workspace_shell" }
        val maskedResult = toolOutputProcessor.maskToolOutput(output)
        return toolOutputProcessor.maybeTruncateToolOutput(
            tool.toolCallId,
            appendApprovalProvenance(maskedResult, tool.approvalState),
            hasShellAccess,
            if (tool.toolName in assistant.toolOutputCompactTools ||
                tool.toolName in assistant.toolOutputDigestTools
            ) {
                settings.toolOutputCompactMaxChars
            } else {
                settings.toolOutputMaxChars
            },
            if (tool.toolName in assistant.toolOutputDigestTools) {
                assistant.toolOutputDigestKeywords
            } else {
                null
            },
            toolName = tool.toolName,
        )
    }

    /**
     * 步边界处置：补齐上一轮流式期间被合并掉的输出变换 → 预算与守卫计数 → 压缩历史与队列消息注入
     * → 断流续写重发。判定顺序与原来一致（先补快照，再判预算，最后决定要不要重发本步）。
     */
    // 步前决策是一条线性清单（预算/续写/循环/注入），拆分会把同一份口径割裂成多段并增加每次请求的往返。
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override suspend fun beforeStep(step: StepContext): StepDecision {
        // The preceding request (and its tools) has completed before this boundary.
        step.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.let { accountCompletedUsage(it) }
        runCtx.steps = step.stepIndex + 1
        outputTransformCache.clear()
        flushPendingOutput()
        // 收尾总结已发出但零文本：补一行兜底，避免用户只看到提醒、看不到总结。
        appendWrapUpFallbackIfEmpty()

        val elapsedMs = android.os.SystemClock.elapsedRealtime() - turnStartMs

        if (wrapUpFinalRequestInFlight) {
            // 最终纯文本请求已完成；停止继续消耗预算。
            runCtx.abortReason = GenerationOutcome.TIMEOUT
            return StepDecision.Stop(AgentFinishReason.BudgetExhausted(AgentFinishReason.Kind.WALL_CLOCK))
        }
        if (wrapUpFinalRequestPending) {
            wrapUpFinalRequestPending = false
            wrapUpFinalRequestInFlight = true
            // 工具结果本身带有提醒；重新发同一步，进入禁用工具的终结总结。
            // terminalSummary=true：即使已经撞上 maxSteps，也放行这一次总结请求。
            return StepDecision.RetryCurrentStep(terminalSummary = true)
        }

        if (step.stepIndex > 0) {
            // 优雅收尾：预算进入宽限窗口时注入一次性收尾指令（合成消息，不落库不进历史请求之外的地方）
            val turnBudgetMs = ToolRuntimeLimits.turnBudgetMs
            val remainingBudgetMs = turnBudgetMs - elapsedMs
            val wrapUpGraceMs = wrapUpGraceMsForBudget(turnBudgetMs)
            if (remainingBudgetMs in 1..wrapUpGraceMs && !wrapUpInjected) {
                AppLog.w(TAG, "generateText: turn budget nearly exhausted (${remainingBudgetMs}ms left); injecting wrap-up reminder")
                return appendWrapUpSystemNotice()
            }
            if (elapsedMs > ToolRuntimeLimits.turnBudgetMs) {
                // 预算已耗尽：步边界是按步采样的，一次长工具执行就可能整段跨过 120s 宽限窗口，
                // 此时若直接 Stop，用户看到的是一次没有任何总结的硬切断。这里补一次收尾生成机会，
                // 且整轮只补一次（wrapUpInjected 已置位时不再续命）。
                if (!wrapUpInjected) {
                    AppLog.w(TAG, "generateText: turn budget exhausted at step #${step.stepIndex}; injecting wrap-up reminder for a final reply")
                    return appendWrapUpSystemNotice()
                }
                AppLog.w(TAG, "generateText: wall-clock cap (${ToolRuntimeLimits.turnBudgetMs}ms) hit at step #${step.stepIndex}; force-ending turn")
                runCtx.abortReason = GenerationOutcome.TIMEOUT
                return StepDecision.Stop(AgentFinishReason.BudgetExhausted(AgentFinishReason.Kind.WALL_CLOCK))
            }
            if (assistant.tokenBudgetSoftCap != null || assistant.tokenBudgetHardCap != null) {
                val budgetStatus = TokenBudgetTracker.classify(
                    TokenBudgetTracker.aggregateMessages(messages),
                    assistant.tokenBudgetSoftCap,
                    assistant.tokenBudgetHardCap,
                )
                val overHard = budgetStatus == TokenBudgetTracker.BudgetStatus.OVER_HARD
                val overSoft = budgetStatus == TokenBudgetTracker.BudgetStatus.WARN
                when {
                    (overSoft || overHard) && !tokenBudgetWarnInjected -> {
                        tokenBudgetWarnInjected = true
                        AppLog.w(TAG, "generateText: token budget crossed (overHard=$overHard); injecting wrap-up reminder")
                        // 与时间预算同款：提醒带 terminalSummary，工具禁用，下一边界硬停前
                        // 模型至少有一次无工具总结机会。
                        return appendTerminalSummaryNotice(
                            UIMessage.system(context.resources.getString(R.string.ai_token_budget_notice)).copy(isSynthetic = true),
                        )
                    }

                    overHard -> {
                        AppLog.w(TAG, "generateText: token budget still over hard cap at step #${step.stepIndex}; force-ending turn")
                        runCtx.abortReason = GenerationOutcome.TOKEN_BUDGET
                        return StepDecision.Stop(AgentFinishReason.BudgetExhausted(AgentFinishReason.Kind.TOKEN_BUDGET))
                    }
                }
            }
            if (loopGuardTripCount >= MAX_LOOP_GUARD_TRIPS_PER_TURN) {
                // 先给一次无工具总结机会，再停；总结已给过时直接停。
                if (!wrapUpInjected) {
                    AppLog.w(TAG, "generateText: loop-guard tripped $loopGuardTripCount times this turn; injecting wrap-up reminder for a final reply")
                    return appendTerminalSummaryNotice(
                        UIMessage.system(context.resources.getString(R.string.ai_final_summary_notice)).copy(isSynthetic = true),
                    )
                }
                AppLog.w(TAG, "generateText: loop-guard tripped $loopGuardTripCount times this turn; force-ending")
                runCtx.abortReason = GenerationOutcome.LOOP_GUARD
                return StepDecision.Stop(AgentFinishReason.BudgetExhausted(AgentFinishReason.Kind.LOOP_GUARD))
            }
            if (pendingAutoContinue) {
                pendingAutoContinue = false
                autoContinueCount++
                AppLog.w(
                    TAG,
                    "generateText: reply ended without finish reason; auto-continuing ($autoContinueCount/$MAX_AUTO_CONTINUE)",
                )
                val prompt = UIMessage.user(context.resources.getString(R.string.ai_auto_continue_prompt)).copy(isSynthetic = true)
                messages += prompt
                return StepDecision.RetryCurrentStep(inject = prompt)
            }
        }
        // 混合态保护：最后一条消息仍有 Pending 工具、又没有可续跑的调用时，不能再发起生成
        // （否则会把那条助手消息孤立掉，用户审批后无法接上）
        if (step.pendingTools.isEmpty() &&
            messages.lastOrNull()?.parts?.any { p -> p is UIMessagePart.Tool && p.isPending } == true
        ) {
            AppLog.i(TAG, "generateText: last message has Pending tools; waiting for approval, not regenerating")
            return StepDecision.Stop(
                AgentFinishReason.AwaitingApproval(
                    messages.last().getTools().filter { it.isPending }.mapTo(LinkedHashSet()) { it.toolCallId },
                ),
            )
        }
        // 用户在生成期间排队了补充消息：当前 step（工具执行完 / 一步回复完）已到边界。
        // 不再继续下一 step，让 turn-end 的 dispatchNextQueuedMessage 把它作为**新一轮**发出
        // —— 既不等整轮跑完，也不打断正在执行的工具（能走到这个边界时工具都已收尾）。
        // ⚠ 只查不取：这里如果 drain，模型随后 stop 时消息会被吞没、永远没有回复
        //   （2026-10-27 那次修复踩的就是这个坑）；消费统一由 turn-end 派发做。
        // ⚠ 必须 `step.pendingTools.isEmpty()`：本轮刚发起的工具还没执行时不能停，
        //   否则它会被标成 interrupted_before_execution（用户侧现象：工具卡「已拒绝 / 有打断提示」）。
        //   用户口径：未完成不要打断 —— 工具全部跑完才收尾。
        if (step.stepIndex > 0 && step.pendingTools.isEmpty() && hasQueuedMessages()) {
            AppLog.i(
                TAG,
                "generateText: queued message pending at step boundary #${step.stepIndex}; ending turn so it is sent as a new one",
            )
            return StepDecision.Stop(AgentFinishReason.Completed)
        }
        // 步数上限后同样先给一次无工具总结机会，再停；收尾已注入时直接停。
        if (step.stepIndex >= maxSteps && !wrapUpTerminalStep) {
            if (!wrapUpInjected) {
                AppLog.w(TAG, "generateText: step limit reached at step #${step.stepIndex}; injecting wrap-up reminder for a final reply")
                return appendTerminalSummaryNotice(
                    UIMessage.system(context.resources.getString(R.string.ai_final_summary_notice)).copy(isSynthetic = true),
                )
            }
            return StepDecision.Stop(AgentFinishReason.StepLimitReached)
        }
        return StepDecision.Continue
    }

    /**
     * 通用收尾提醒：把提醒同时写进两份历史（AgentLoop 的 Append 只更新它自己的历史；
     * prepareStep 从本类 messages 构建请求），并标记本轮已进入无工具收尾，
     * 让这次总结请求越过 maxSteps、且不再挂工具。
     */
    private fun appendTerminalSummaryNotice(notice: UIMessage): StepDecision.Append {
        wrapUpInjected = true
        wrapUpTerminalStep = true
        // AgentLoop 的 Append 会更新自己的历史；prepareStep 还会从本类 messages 构建请求，
        // 两份历史都同步才能确保 provider 实际收到收尾提醒。
        messages = messages + notice
        // AgentLoop 会放行这一次带 terminalSummary 标记的收尾请求。
        return terminalSummaryAppend(notice)
    }

    private fun appendWrapUpSystemNotice(): StepDecision.Append {
        val notice = UIMessage.system(context.resources.getString(R.string.ai_wrap_up_notice)).copy(isSynthetic = true)
        return appendTerminalSummaryNotice(notice)
    }

    /**
     * 收尾总结零文本时的兜底：总结流可能只产出思考、或在首个文本增量前被掐断，
     * 此时用户只看到提醒、看不到总结。补一行说明，避免静默。
     * 只看总结本步是否产出文本（[wrapUpSummaryProducedText]），不看整条助手消息。
     */
    private suspend fun appendWrapUpFallbackIfEmpty() {
        if (!wrapUpTerminalStep || !wrapUpSummaryIssued || wrapUpSummaryProducedText) return
        val index = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (index < 0) return
        val last = messages[index]
        val fallback = UIMessagePart.Text(context.resources.getString(R.string.ai_wrap_up_empty_fallback))
        messages = messages.subList(0, index) + last.copy(parts = last.parts + fallback) +
            messages.subList(index + 1, messages.size)
        emitter.emit(GenerationChunk.Messages(messages))
    }

    override suspend fun afterToolExecution(step: StepContext) {
        // AgentLoop 调用此边界时，工具结果刚写回助手消息；此时还没有发出下一次模型请求。
        val hasCompletedTool = step.messages.lastOrNull()?.getTools()?.any { it.isExecuted } == true
        if (!hasCompletedTool) return
        outputTransformCache.clear()
        flushPendingOutput()
        attachTurnBudgetHint()
        onAfterToolExecution(messages)?.let { compactedMessages ->
            AppLog.i(TAG, "generateText: replacing request history after tool execution")
            messages = compactedMessages
        }
    }

    /** 收尾提醒已挂起时跳过本轮生成，把唯一一次总结留给下一轮 [beforeStep]。 */
    override fun deferGeneration(): Boolean = wrapUpFinalRequestPending

    /**
     * 工具使用预算：接近上限时把收尾指令附到**最后一次工具结果**里。
     *
     * 为什么不往会话里插一条消息：预算是工具执行侧的约束，插成 user/system 消息会让 UI 多出一个气泡，
     * 观感像「被打断」；放进工具结果则模型照常看得到，且只出现在工具卡片内部。
     * 与 [beforeStep] 的注入共用 [wrapUpInjected] 去重：先到先走，整轮只提醒一次。
     */
    private fun attachTurnBudgetHint() {
        if (wrapUpInjected) return
        val elapsedMs = android.os.SystemClock.elapsedRealtime() - turnStartMs
        val wrapUpGraceMs = wrapUpGraceMsForBudget(ToolRuntimeLimits.turnBudgetMs)
        if (ToolRuntimeLimits.turnBudgetMs - elapsedMs > wrapUpGraceMs) return
        val hint = UIMessagePart.Text(context.resources.getString(R.string.ai_wrap_up_notice))
        var attached = false
        messages = messages.mapIndexed { index, message ->
            if (index != messages.lastIndex) return@mapIndexed message
            val parts = message.parts.toMutableList()
            val toolIndex = parts.indexOfLast { it is UIMessagePart.Tool && it.isExecuted }
            if (toolIndex < 0) return@mapIndexed message
            val tool = parts[toolIndex] as UIMessagePart.Tool
            parts[toolIndex] = tool.copy(output = tool.output + hint)
            attached = true
            message.copy(parts = parts)
        }
        if (attached) {
            wrapUpInjected = true
            wrapUpTerminalStep = true
            wrapUpFinalRequestPending = true
            AppLog.w(TAG, "generateText: turn budget nearly exhausted; hint attached to the last tool result")
        }
    }

    /** 一步生成结束：可视化变换 + 收尾变换 + 断流判定 */
    override suspend fun finishStep(message: UIMessage, newPartsFrom: Int): UIMessage {
        messages = upsertMessage(messages, message)
        // 记下收尾总结本步是否产出了可见文本：只看本步的新段（newPartsFrom 起），
        // 不看整条助手消息（前面步骤的工具/文本不能算作总结）。
        if (wrapUpTerminalStep) {
            wrapUpSummaryProducedText = message.parts.drop(newPartsFrom)
                .any { it is UIMessagePart.Text && it.text.isNotBlank() }
        }
        messages = messages.visualTransformsIncremental(
            transformers = outputTransformers,
            cache = outputTransformCache,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
        )
        messages = messages.onGenerationFinish(
            transformers = outputTransformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
        )
        val withFinishedAt = messages.last().copy(
            finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
        )
        messages = upsertMessage(messages, withFinishedAt)
        val finished = messages.last()
        emitter.emit(GenerationChunk.Messages(messages))
        pendingOutputFlush.clear()

        // 断流续写：服务端没给出结束原因、文本非空且没有待执行工具 → 判定为中途被切断
        val truncatedNoFinish = finished.role == MessageRole.ASSISTANT &&
            finished.finishedAt != null &&
            finished.finishReason == null &&
            finished.getTools().isEmpty() &&
            finished.parts.any { it is UIMessagePart.Text && it.text.isNotBlank() }
        if (truncatedNoFinish && autoContinueCount < MAX_AUTO_CONTINUE) {
            pendingAutoContinue = true
        }
        return finished
    }

    /** 本层不额外改写预览内容，投递节奏由 [onMessages] 的窗口决定 */
    override suspend fun previewStep(message: UIMessage, newPartsFrom: Int): UIMessage = message

    /** 按 id 覆盖同一条消息（本轮的助手消息），没有匹配时追加到末尾 */
    private fun upsertMessage(list: List<UIMessage>, message: UIMessage): List<UIMessage> {
        val index = list.indexOfLast { it.id == message.id }
        return if (index < 0) list + message else list.subList(0, index) + listOf(message) + list.subList(index + 1, list.size)
    }
}

