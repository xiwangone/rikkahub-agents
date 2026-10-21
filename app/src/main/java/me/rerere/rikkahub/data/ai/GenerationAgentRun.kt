package me.rerere.rikkahub.data.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
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
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private const val TAG = "GenerationAgentRun"

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
    private val emitter: FlowCollector<GenerationChunk>,
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
    private val drainQueuedMessages: suspend () -> List<UIMessage>,
    private val isToolAutoApproved: suspend (String) -> Boolean,
    private val systemAddendum: String?,
    private val conversationSystemPrompt: String?,
    private val conversationId: Uuid?,
    private val conversationModeInjectionIds: Set<Uuid>,
    private val conversationLorebookIds: Set<Uuid>,
    private val workspaceCwd: String?,
) : AgentHooks {

    private var messages: List<UIMessage> = initialMessages

    private val turnStartMs = android.os.SystemClock.elapsedRealtime()
    private val outputTransformCache = OutputTransformCache()

    private var lastOutputFlushAtMs = 0L
    private var pendingOutputMessages: List<UIMessage>? = null
    private var reportedUsage: TokenUsage? = null

    private var loopGuardTripCount = 0
    private var wrapUpInjected = false
    private var tokenBudgetWarnInjected = false
    private var autoContinueCount = 0
    private var imageDowngradeApplied = false

    // 上一步是否真的执行过工具：决定本步开头要不要做压缩与队列消息注入
    private var executedToolsLastStep = false
    private var pendingAutoContinue = false

    private val maxStreamRetries = if (settings.enableAutoRetry) settings.responseStreamMaxRetries else 0

    // 本次（重）订阅的状态：与逐块投递判定一一对应
    private var streamAttempt = 0
    private var receivedAnyChunk = false
    private var receivedMeaningfulOutput = false
    private var streamStartedAtMs = 0L
    private var lastStreamApplyAtMs = 0L
    private val pendingStreamChunks = mutableListOf<StreamChunk>()
    private var textRepetitionDetector = OutputRepetitionDetector()
    private var summaryRepetitionDetector = OutputRepetitionDetector()
    private var reasoningRepetitionDetector = newReasoningRepetitionDetector()

    private val retryPolicy = RetryPolicy(
        maxRetries = maxStreamRetries,
        // 只重试「传输类」失败：已收到有意义输出、内容风控、上下文超限、客户端 4xx、取消都不重放
        retryOn = { failure ->
            shouldRetryGenerationStreamFailure(
                failure = failure,
                retryAttempt = streamAttempt.toLong(),
                maxRetries = maxStreamRetries,
                receivedMeaningfulOutput = receivedMeaningfulOutput,
            )
        },
        delayFor = { retry -> generationStreamRetryDelayMs((retry - 1).toLong()).milliseconds },
    )

    private val request: AgentRequest
        get() = AgentRequest(
            provider = providerImpl,
            providerSetting = provider,
            messages = messages,
            tools = tools,
            stream = assistant.streamOutput,
            maxSteps = maxSteps,
            retry = retryPolicy,
        )

    /**
     * 驱动一次生成轮回。图片不匹配的失败驱动降级只允许一次：降级后重跑整轮（[messages] 已被剥图，
     * 循环会从同一条助手消息继续）。其余失败按原路径向外抛出，由调用方（生成流程外壳）收尾。
     */
    // 有意 catch Throwable：降级/重试要按失败原因分流；取消用类型判断单独上抛（降级判定本身需要先拿到异常对象，
    // 无法用多 catch 表达顺序）。
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    suspend fun start() {
        while (true) {
            val requestStartedAtMs = android.os.SystemClock.elapsedRealtime()
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
                            AppLog.w(
                                TAG,
                                "streamText: retrying after failure " +
                                    "(${event.attempt.retry}/$maxStreamRetries) in ${event.attempt.delay.inWholeMilliseconds}ms",
                                event.attempt.cause,
                            )
                            onRetryDiagnosed?.invoke(diagnoseFailure(context, event.attempt.cause))
                        }

                        is AgentEvent.Finished -> runCtx.finishReason =
                            event.message.finishReason ?: runCtx.finishReason
                    }
                }
                return
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
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
                // 的 generation_failed 包络，否则下一轮回放会拿到半截状态的工具 part
                val lastMsg = messages.lastOrNull()
                if (lastMsg != null) {
                    val newParts = lastMsg.parts.map { part ->
                        if (part is UIMessagePart.Tool &&
                            (part.approvalState is ToolApprovalState.Auto ||
                                part.approvalState is ToolApprovalState.Pending)
                        ) {
                            part.copy(
                                approvalState = ToolApprovalState.Denied(
                                    "generation_failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}",
                                ),
                            )
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMsg.copy(parts = newParts)
                    emitter.emit(GenerationChunk.Messages(messages))
                }
                throw t
            } finally {
                // 收尾投递：合并窗口里被跳过的最后一批快照补上（审批状态单调回贴）
                runCatching {
                    pendingOutputMessages?.let { pending ->
                        pendingOutputMessages = null
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
            }
        }
    }

    /** 用户主动停止时的收尾：与正常完成走同一条变换链，让落盘的形状与逐次生成一致 */
    suspend fun finalizeCancellation() {
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

        // 每次请求的用量单独上报（不受下方 UI 提交节流影响）：见 GenerationChunk.UsageIncurred
        last.usage?.let { incurred ->
            if (incurred != reportedUsage) {
                // 按**请求**累加（与平台账单、会话累计同口径）：一轮里的多步请求各按其**完整** prompt
                // 计费 —— 第 N 步的 prompt 已含前 N-1 步的历史，这是计费口径、不是重复
                val stepPromptTokens = incurred.promptTokens.coerceAtLeast(0)
                runCtx.promptTokens += stepPromptTokens
                runCtx.completionTokens += incurred.completionTokens.coerceAtLeast(0)
                // 单步内先夹一次：缓存命中量不可能超过该步输入量
                runCtx.cachedTokens += incurred.cachedTokens.coerceIn(0, stepPromptTokens)
                incurred.cost?.let { stepCost ->
                    runCtx.cost += stepCost.coerceAtLeast(0.0)
                    runCtx.costReported = true
                }
                reportedUsage = incurred
                emitter.emit(GenerationChunk.UsageIncurred(incurred))
            }
        }
        val nowFlushMs = android.os.SystemClock.elapsedRealtime()
        if (nowFlushMs - lastOutputFlushAtMs >= renderProfile.outputFlushIntervalMs) {
            lastOutputFlushAtMs = nowFlushMs
            pendingOutputMessages = null
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
            pendingOutputMessages = messages.mergeApprovalProgress(previous)
        }
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
        }
        outputTransformCache.clear()

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
            val toolPrompts = tools.map { tool -> tool.systemPrompt(model, messages) }
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
                    toolCount = tools.size,
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
            hasReadableFileTool = tools.any { it.name in FILE_READ_TOOL_NAMES },
        )

        val params = TextGenerationParams(
            model = if (assistant.streamOutput) model else execModel,
            temperature = assistant.temperature,
            topP = assistant.topP,
            frequencyPenalty = assistant.frequencyPenalty,
            presencePenalty = assistant.presencePenalty,
            maxTokens = assistant.maxTokens,
            maxStreamRetries = maxStreamRetries,
            tools = tools,
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
        return StepRequest(internalMessages, params)
    }

    // 三条流各一套检测器 + 窗口合并，分支数由协议复杂度决定；拆开会让同一事件被多次裁定。
    @Suppress("CyclomaticComplexMethod")
    override fun interceptStream(chunks: Flow<StreamChunk>): Flow<StreamChunk> = chunks
        .withIdleWatchdog(idleMs = assistant.streamIdleTimeoutMs)
        .onCompletion { cause ->
            if (cause == null && shouldReportEmptyGenerationStream(receivedAnyChunk)) {
                throw IOException("Model stream closed without meaningful output")
            }
            if (cause == null && receivedAnyChunk && !receivedMeaningfulOutput) {
                AppLog.w(
                    TAG,
                    "streamText: stream closed after chunks arrived but none contained " +
                        "parseable parts; ending without retry",
                )
            }
        }.takeWhile { chunk ->
            // 墙钟上限：永不结束的流（无限「思考」）只能在这里掐掉，重复检测对它无感
            if (System.currentTimeMillis() - streamStartedAtMs > MAX_STREAM_DURATION_MS) {
                AppLog.w(TAG, "streamText: stream exceeded max duration; ending this turn's stream early")
                return@takeWhile false
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
                AppLog.w(TAG, "streamText: runaway repetition detected in $streamKind stream; ending this turn's stream early")
                false
            } else {
                true
            }
        }.coalesceStreamDeltas(renderProfile.streamApplyIntervalMs)

    override suspend fun onStreamAttemptReset(attempt: Int) {
        streamAttempt = attempt
        receivedAnyChunk = false
        receivedMeaningfulOutput = false
        streamStartedAtMs = System.currentTimeMillis()
        lastStreamApplyAtMs = 0L
        pendingStreamChunks.clear()
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
            val reasonCode = HardlineCommandGuard.reasonCodeForMessage(resumeHardlineReason)
            return ToolCallOutcome.Reject(
                listOf(
                    UIMessagePart.Text(
                        json.encodeToString(
                            ToolErrors.envelopeFor(
                                error = "invalid_argument",
                                message = "blocked by safety floor (hardline): $resumeHardlineReason. " +
                                    "This command cannot run via the agent under any circumstances.",
                                hint = "Check the parameter values and retry with corrected arguments.",
                                extra = reasonCode?.let {
                                    ToolErrors.extraOf(HardlineCommandGuard.HARDLINE_REASON_CODE_FIELD to it)
                                }.orEmpty(),
                            ),
                        ),
                    ),
                ),
            )
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
        if (remainingMs <= 0L) {
            AppLog.w(TAG, "generateText: ${toolDef.name} skipped — wall-clock budget already exceeded")
            return ToolCallOutcome.Intercept(
                ToolErrors.partsFor(
                    ToolErrors.TOOL_CANCELLED_WALL_CLOCK,
                    "turn budget exceeded before tool started",
                ),
            )
        }
        return ToolCallOutcome.Execute(
            timeout = remainingMs.milliseconds,
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
        return ToolErrors.partsFor(
            ToolErrors.TOOL_FAILED,
            (error.message ?: error.javaClass.simpleName).take(500),
            extra = mapOf("exception" to JsonPrimitive(error.javaClass.simpleName)),
        )
    }

    /** 执行成功、写回之前：脱敏 → 审批出处 → 超限落盘 + 预览/摘要 */
    override suspend fun processToolOutput(
        tool: UIMessagePart.Tool,
        output: List<UIMessagePart>,
    ): List<UIMessagePart> {
        val hasShellAccess = tools.any { it.name == "workspace_shell" }
        val maskedResult = toolOutputProcessor.maskToolOutput(output)
        executedToolsLastStep = true
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
        runCtx.steps = step.stepIndex + 1
        outputTransformCache.clear()

        pendingOutputMessages?.let { pending ->
            pendingOutputMessages = null
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

        if (step.stepIndex > 0) {
            if (executedToolsLastStep) {
                executedToolsLastStep = false
                onAfterToolExecution(messages)?.let { compactedMessages ->
                    AppLog.i(TAG, "generateText: replacing request history after tool execution")
                    messages = compactedMessages
                }
                val queued = drainQueuedMessages()
                if (queued.isNotEmpty()) {
                    AppLog.i(TAG, "generateText: injecting ${queued.size} queued user message(s) at end of step #${step.stepIndex}")
                    messages = messages + queued
                    emitter.emit(
                        GenerationChunk.Messages(
                            messages.transforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant,
                                settings = settings,
                            ),
                        ),
                    )
                    pendingOutputMessages = null
                }
            }

            // 优雅收尾：预算进入宽限窗口时注入一次性收尾指令（合成消息，不落库不进历史请求之外的地方）
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - turnStartMs
            val remainingBudgetMs = ToolRuntimeLimits.turnBudgetMs - elapsedMs
            if (remainingBudgetMs in 1..WRAP_UP_GRACE_MS && !wrapUpInjected) {
                wrapUpInjected = true
                AppLog.w(TAG, "generateText: turn budget nearly exhausted (${remainingBudgetMs}ms left); injecting wrap-up reminder")
                messages += UIMessage.user(context.getString(R.string.ai_wrap_up_notice)).copy(isSynthetic = true)
                return StepDecision.Append(
                    UIMessage.user(context.getString(R.string.ai_wrap_up_notice)).copy(isSynthetic = true),
                )
            }
            if (elapsedMs > ToolRuntimeLimits.turnBudgetMs) {
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
                        messages += UIMessage.user(context.getString(R.string.ai_token_budget_notice)).copy(isSynthetic = true)
                        return StepDecision.Append(
                            UIMessage.user(context.getString(R.string.ai_token_budget_notice)).copy(isSynthetic = true),
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
                val prompt = UIMessage.user(context.getString(R.string.ai_auto_continue_prompt)).copy(isSynthetic = true)
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
        return StepDecision.Continue
    }

    /** 一步生成结束：可视化变换 + 收尾变换 + 断流判定 */
    override suspend fun finishStep(message: UIMessage, newPartsFrom: Int): UIMessage {
        messages = upsertMessage(messages, message)
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
        pendingOutputMessages = null

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

private fun newReasoningRepetitionDetector(): OutputRepetitionDetector = OutputRepetitionDetector(
    minPeriod = 8,
    minRepeats = 3,
    minCheckIntervalChars = 48,
)

/**
 * 按窗口合并相邻的同轨增量：每个 delta 都做一次折叠 + 全量投递，长回复下是 O(n²)。
 * 合并保持顺序与内容完全一致（同一 id 的连续增量拼接成一条），窗口为 0 时逐块透出。
 */
private fun Flow<StreamChunk>.coalesceStreamDeltas(windowMs: Long): Flow<StreamChunk> = channelFlow {
    if (windowMs <= 0L) {
        collect { send(it) }
        return@channelFlow
    }
    val buffer = mutableListOf<StreamChunk>()
    var lastFlushMs = android.os.SystemClock.elapsedRealtime()
    suspend fun flush() {
        if (buffer.isEmpty()) return
        val merged = mergeAdjacentDeltas(buffer)
        buffer.clear()
        merged.forEach { send(it) }
        lastFlushMs = android.os.SystemClock.elapsedRealtime()
    }
    try {
        collect { chunk ->
            val coalescable = chunk is StreamChunk.TextDelta || chunk is StreamChunk.ReasoningDelta
            if (!coalescable) {
                flush()
                send(chunk)
                return@collect
            }
            buffer.add(chunk)
            if (android.os.SystemClock.elapsedRealtime() - lastFlushMs >= windowMs) flush()
        }
    } finally {
        // 终止（正常/异常/取消）都补齐窗口内未透出的分块，尾部内容不丢
        runCatching { flush() }
    }
}

private fun mergeAdjacentDeltas(chunks: List<StreamChunk>): List<StreamChunk> {
    if (chunks.size < 2) return chunks
    val out = mutableListOf<StreamChunk>()
    for (chunk in chunks) {
        val prev = out.lastOrNull()
        val merged = when {
            prev is StreamChunk.TextDelta && chunk is StreamChunk.TextDelta && prev.id == chunk.id ->
                prev.copy(text = prev.text + chunk.text)

            prev is StreamChunk.ReasoningDelta && chunk is StreamChunk.ReasoningDelta &&
                prev.id == chunk.id && prev.reasoningType == chunk.reasoningType ->
                prev.copy(text = prev.text + chunk.text)

            else -> null
        }
        if (merged != null) out[out.lastIndex] = merged else out.add(chunk)
    }
    return out
}
