package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.perf.resolveRenderProfileLogged
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.service.AgentOverlay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.vault.SecretMasker
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "GenerationLoop"
internal val TOOL_OUTPUT_DIGEST_DEFAULT_KEYWORDS =
    listOf("FAILED", "error", "Exception", "✗", "失败", "异常")


@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk

    /**
     * 一次 API 请求的用量（每次请求上报一次，用于会话累计）。
     *
     * 为什么单独上报：一轮里的多步工具调用共用同一条 assistant 消息、usage 被后来的请求覆盖，
     * 事后扫消息只能看到最后一次 → 用量统计会少算数倍。所以请求完成即上报，由上层累加
     * （与主流客户端一致）。该事件不参与 UI 节流。
     */
    data class UsageIncurred(
        val usage: TokenUsage
    ) : GenerationChunk
}

private const val TAG_GH_LOOP = "GenHandlerLoop"

/**
 * If the model calls the same tool with the same exact JSON args this many times within a
 * single user turn, we refuse the next execution and inject a "loop_detected" envelope. The
 * threshold is INCLUSIVE of the prior occurrences, so a value of 3 means: first call runs,
 * second call runs, third call runs — fourth identical call is blocked. Picked low enough
 * that runaway loops can't drain the user's API tokens but high enough to allow legitimate
 * retries (a notification key going stale between read and dismiss, etc.).
 */
private const val LOOP_GUARD_REPEAT_THRESHOLD = 3

// The per-turn wall-clock budget was hardcoded here (most recently 10 min). It now lives in
// ToolRuntimeLimits.turnBudgetMs (default 10 min), user-configurable via Settings -> Termux;
// every read site below uses that holder directly.

/**
 * Max number of times the loop guard can trip in a single turn before we force-end the
 * turn entirely. Prevents the "model keeps trying different tools, each gets loop-detected"
 * pattern that produced the 27-step / 141K-token disaster: one trip means the model is
 * confused; six trips means it's not coming back.
 */
internal const val MAX_LOOP_GUARD_TRIPS_PER_TURN = 6

// 轮预算将尽时提前注入收尾指令的宽限窗口：让模型总结收场，而不是被硬掐在半句话上
internal const val WRAP_UP_GRACE_MS = 120_000L

// 流式期间「输出变换 + 界面投递」的合并窗口由当前渲染档位给出（见 data/perf/RenderProfile.kt）：
// 每个 token 都做一次全量变换，在长会话下开销随消息数增长。同一窗口内只保留最新快照，
// 并在 step 边界与生成结束时强制补齐，最终结果与逐次变换一致（窗口为 0 即恢复逐次处理）。

// 流式分块的合并窗口同样来自当前渲染档位（见 data/perf/RenderProfile.kt）：文本 delta 到达
// 频率很高，逐块拼接与投递在长回复下代价是 O(n²)。窗口内先累积再按序应用，流终止时补齐，
// 最终内容与逐块处理一致（窗口为 0 即恢复逐块处理）。

// 断流续写：服务端未给出结束原因（finish_reason 缺失）时，说明流被中途切断，
// 自动再请求一次把内容接上；每个回合最多续写次数，以及续写时注入的合成指令。
internal const val MAX_AUTO_CONTINUE = 1


/**
 * Hard ceiling on a single streamed generation step. Guards the "model thinks for 2000+ seconds"
 * failure mode, which no chunk-level check can catch (the stream stays alive and looks productive
 * the whole time). Deliberately generous so ordinary long generations are never cut short.
 */
internal const val MAX_STREAM_DURATION_MS = 15 * 60 * 1000L

/**
 * Some read-only tools measure a real-time signal where re-calling after a TTL is
 * legitimate (battery drains, screens change, sensors update). For these, the loop guard
 * lets identical calls through if the most recent identical call is older than the TTL.
 * Without this, asking the model "what's the battery now?" after a previous reading just
 * regurgitates the stale value and the user has no idea.
 *
 * Tools NOT in this map are treated as side-effecting / idempotent-input: re-calling with
 * identical args is a loop, not a refresh. Add new freshness-sensitive tools here.
 */
private val FRESHNESS_TTL_MS_BY_TOOL: Map<String, Long> = mapOf(
    "device_info" to 30_000L,
    "get_brightness" to 10_000L,
    "get_volume" to 10_000L,
    "get_location" to 30_000L,
    "get_time_info" to 5_000L,
    "take_screenshot" to 5_000L,
    "read_window_tree" to 5_000L,
    "list_active_notifications" to 5_000L,
    "list_jobs" to 60_000L,
)

/**
 * 能把上传文件按路径读回正文的工具。大附件的「引用式」呈现（见
 * [me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer]）只在其中任意一个
 * **确实出现在本轮下发的工具列表**里时才启用：`path` 属性给的是 proot 内的 `/upload/<file>`，
 * 只有工作区工具看得懂；设备侧的 `read_file` 拿的是设备路径，不在此列。
 */
internal val FILE_READ_TOOL_NAMES = setOf("workspace_read_file", "workspace_shell")

/**
 * UI-observation tools that read screen/device state without changing it. Used by the loop
 * guard's reset rule below: when the model drives a UI it runs an act-observe cycle and
 * naturally repeats the same observation call (read_window_tree / take_screenshot with
 * identical args) after every action. Those repeats are progress, NOT a loop, so an
 * intervening ACTION (any executed tool NOT in this set) resets the observation repeat count.
 * Tools that ARE in this set do not reset each other, so a model that merely alternates
 * observers on a frozen screen still trips the guard (the token-drain case we must catch).
 *
 * This is the freshness-sensitive realtime readers plus find_node (the other pure screen
 * reader). Keep it to genuine read-only observers: wrongly adding an ACTION tool here would
 * stop it from resetting the counter and reintroduce the false-positive loop_detected.
 */
private val READ_ONLY_OBSERVATION_TOOLS: Set<String> =
    FRESHNESS_TTL_MS_BY_TOOL.keys + "find_node"

/** One prior executed tool call in the current turn, in chronological order. */
internal data class PriorToolCall(
    val toolName: String,
    val signature: String,
    val epochMs: Long,
)

internal data class LoopGuardDecision(
    val block: Boolean,
    val priorOccurrences: Int,
)

/**
 * 「执行已开始但结果未返回」的中断尝试所用的确定性 Denied 理由。
 *
 * 与 [UIMessagePart.Tool.isInterruptedAttempt] 配套：executionStartedAt 有值、output 仍空
 * —— 副作用**可能已发生**（进程在执行中途被杀）。刻意不断言「未执行」：必须告知「状态未知、
 * 先核实再重试」，否则模型重跑同一条 shell/git 命令就是重复计费/重复发送/重复覆盖。
 *
 * 两处消费，必须同文（历史上正是只有一处用对、另一处用错才出的误报）：
 *  1. 本文件 generateText 顶部的 replay 安全通道（请求路径）；
 *  2. ChatService.checkInvalidMessages 的生成前清理（持久化路径，先于 replay 通道执行，
 *     若不在此分叉，replay 的这一句永远轮不到）。
 */
internal const val INTERRUPTED_UNKNOWN_OUTCOME_REASON =
    "interrupted_unknown_outcome: a previous attempt to execute this tool started " +
        "but did not complete (process killed mid-execute). The side effect MAY OR " +
        "MAY NOT have happened. Verify the target state before retrying — do not " +
        "blindly re-run the same call."

/**
 * Fixed compact encoder for [LoopGuard.signature] — deliberately NOT the app-injected [Json]
 * instance: a pretty-printing config there would put formatting whitespace back into the
 * canonical text and silently undo the L2 normalisation.
 */
private val CANONICAL_ARGS_JSON: Json = Json { }

/**
 * Pure, testable loop-detection decision, extracted from [GenerationLoop.generateText] so
 * the act-observe reset and freshness-TTL rules can be unit-tested without an Android Context.
 */
internal object LoopGuard {
    /**
     * The loop signature, with the **representation-layer** noise of [input] normalised away so
     * the same call written two different ways counts as one call. Two levels, and only two:
     *  - L1 key order — object keys sorted recursively (arrays stay ordered: their order is data).
     *  - L2 whitespace — the canonical text is re-emitted from the parsed tree by a compact
     *    encoder, so formatting spaces/newlines between keys and values vanish while the inside
     *    of a string literal is passed through verbatim (never byte-stripped).
     *
     * Deliberately NOT normalised — accepted, known misses: paging offset / line numbers /
     * coordinates / node ids / epoch timestamps / random ids. Those values *are* the semantics,
     * folding them would make genuinely different calls collide and block a legitimate retry.
     *
     * [parsedArgs] is the caller's already-parsed [input] (null when parsing failed — a
     * truncated provider stream leaves `tool.input` as invalid JSON). On failure the raw string
     * is used as-is: this function must never throw, and a non-object top level (array, scalar,
     * bare string) is still canonicalised rather than rejected.
     *
     * Pure: same input ⇒ same output.
     */
    fun signature(toolName: String, input: String, parsedArgs: JsonElement?): String {
        val canonicalArgs = parsedArgs?.let {
            CANONICAL_ARGS_JSON.encodeToString(JsonElement.serializer(), canonicalizeArgs(it))
        } ?: input
        return "$toolName::$canonicalArgs"
    }

    /**
     * [signature] for callers that do not have a parse at hand. Blank args keep the existing
     * `ifBlank { "{}" }`口径, otherwise `""` and `{}` would split into two signatures.
     */
    fun signatureOf(json: Json, toolName: String, input: String): String = signature(
        toolName = toolName,
        input = input,
        parsedArgs = runCatching { json.parseToJsonElement(input.ifBlank { "{}" }) }.getOrNull(),
    )

    private fun canonicalizeArgs(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.sortedBy { it.key }
                .associate { (key, value) -> key to canonicalizeArgs(value) },
        )

        is JsonArray -> JsonArray(element.map { canonicalizeArgs(it) })
        else -> element
    }

    fun evaluate(
        priorCalls: List<PriorToolCall>,
        toolName: String,
        signature: String,
        nowMs: Long,
        threshold: Int = LOOP_GUARD_REPEAT_THRESHOLD,
        readOnlyTools: Set<String> = READ_ONLY_OBSERVATION_TOOLS,
        freshnessTtlMs: Map<String, Long> = FRESHNESS_TTL_MS_BY_TOOL,
    ): LoopGuardDecision {
        // For observation tools, only repeats since the most recent ACTION count: acting on
        // the world is progress, so identical observations taken before it are stale for
        // loop-detection purposes. Side-effecting tools count every identical call in the
        // turn (re-sending the same message 3x is a loop regardless of what ran between).
        val relevant = if (toolName in readOnlyTools) {
            val lastActionIdx = priorCalls.indexOfLast { it.toolName !in readOnlyTools }
            if (lastActionIdx >= 0) priorCalls.subList(lastActionIdx + 1, priorCalls.size)
            else priorCalls
        } else {
            priorCalls
        }
        val matching = relevant.filter { it.signature == signature }
        val priorOccurrences = matching.size
        if (priorOccurrences < threshold) return LoopGuardDecision(false, priorOccurrences)
        // Freshness-TTL bypass: a real-time reader re-called after its TTL is a refresh, not
        // a loop; let it through so the model gets a fresh reading instead of a stale one.
        val ttl = freshnessTtlMs[toolName]
        if (ttl != null && nowMs - matching.maxOf { it.epochMs } >= ttl) {
            return LoopGuardDecision(false, priorOccurrences)
        }
        return LoopGuardDecision(true, priorOccurrences)
    }
}


/**
 * 生成循环：把"一次模型调用 + 工具调用/审批 + 流式回传"串成一整轮，并处理重试与截断。
 *
 * 本文件很长（1800+ 行）。**按符号名定位段落**，不要依赖行号：
 *  - 流式事件协议：`GenerationChunk`（sealed interface，本文件上方）
 *  - 参数与上下文组装：`generateText` 的入参（系统提示、记忆、工具、会话级提示词等）
 *  - 系统提示构建：`SystemPromptBuilder`（本文件调用；会话级提示词在此覆盖助手提示词）
 *  - 单轮生成与工具循环：`generateText` 主体 + 工具调用/审批分支
 *  - 收尾与自动返回：`AutoReturnHandler`（data.ai 包）
 *  - 输出裁剪：`ToolOutputProcessor`（data.ai 包）
 *  - 其它：`translateText`（翻译用途的独立入口）
 *
 * 相关文件：会话中枢 `service/ChatService.kt`；工具装配 `data/ai/tools/ChatToolFactory.kt`；提示词 `data/ai/SystemPromptBuilder.kt`。
 */
/**
 * On resume after a tool-approval decision, picks every tool from the same model step that
 * should execute now: the ones the user acted on ([UIMessagePart.Tool.canResumeExecution] -
 * Approved/Denied/Answered) plus any sibling that was classified `Auto` but never got to run
 * because the step broke early on a *different*, Pending sibling (#107 - otherwise those Auto
 * tools are orphaned forever and the model never learns their results). Order matches [tools],
 * i.e. the original call order. A tool still Pending is never included. This does not change
 * [canResumeToolExecution] itself - Auto stays false there (the top-of-loop Pending-detection
 * relies on that); the inclusion happens only at this resume call site.
 *
 * An Auto tool with [UIMessagePart.Tool.executionStartedAt] set is excluded from the "never
 * got to run" clause even though it isn't executed: that shape means a previous attempt was
 * interrupted mid-execute (see [UIMessagePart.Tool.isInterruptedAttempt]), not that it's
 * still waiting its turn. In production the top-of-generateText replay-safety pass already
 * flips that tool to Denied before this function runs, but this pure function must not rely
 * on that ordering to avoid re-running it.
 */
internal fun resumableToolsIncludingUnexecutedAuto(
    tools: List<UIMessagePart.Tool>,
): List<UIMessagePart.Tool> = tools.filter { tool ->
    tool.canResumeExecution ||
        (tool.approvalState is ToolApprovalState.Auto && !tool.isExecuted && tool.executionStartedAt == null)
}

class GenerationLoop(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val aiLoggingManager: AILoggingManager,
    private val systemPromptBuilder: SystemPromptBuilder,
) {
    private val toolOutputProcessor = ToolOutputProcessor(context)
    private val autoReturnHandler = AutoReturnHandler(context)

    // 取消与普通失败要分开处理：取消时仍需在 NonCancellable 里尽力落最终形状，普通失败照常上抛，
    // 故用类型判断而非拆 catch。
    @Suppress("InstanceOfCheckForException", "LongMethod")
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        // Read live from the runtime holder, not captured once: the default expression is
        // evaluated per call, so a settings change takes effect on the next turn.
        maxSteps: Int = ToolRuntimeLimits.maxToolSteps,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        // Called on each retry after the failure has been diagnosed (label + raw text).
        // ChatService uses it to persist the last failure into the conversation when a
        // retry finally succeeds, so the model knows the generation recovered.
        onRetryDiagnosed: ((FailureDiagnosis) -> Unit)? = null,
        // Called after a tool result has been emitted and persisted, before the next model
        // request is built. The callback may return a compacted request history; the returned
        // list is request-only and does not replace the conversation's original messages.
        onAfterToolExecution: suspend (List<UIMessage>) -> List<UIMessage>? = { null },
        // Called immediately before every model request, including the request after a tool
        // result. ChatService uses this to reassert the foreground service before a background
        // continuation opens a new socket.
        onBeforeModelRequest: suspend () -> Unit = {},
        // 取出用户在生成期间排队补充的消息，在**每个 step 结束（工具执行完）后**立即追加到
        // 请求历史，使「及时修正/补充」在下一 step 就被模型看到，而不必等整轮结束。
        // 返回的消息会随 GenerationChunk.Messages 一并 emit，由 ChatService 落库。
        drainQueuedMessages: suspend () -> List<UIMessage> = { emptyList() },
        // Returns true when the user has pre-approved [toolName] for this turn (e.g.
        // "Allow for this chat" or "Always Allow" granted earlier). When true, the loop
        // below skips the Pending flip and lets the tool execute. ChatService injects the
        // closure that reads ToolApprovalAllowList + ToolApprovalPreferences. Default
        // returns false so callers that don't care still get vanilla approval gating.
        isToolAutoApproved: suspend (toolName: String) -> Boolean = { false },
        // Optional per-call addendum appended to the system prompt. Used by surfaces that
        // need the model to know runtime context (e.g. "you're talking via Telegram, the
        // chat_id is 12345") without polluting the user message body — without this the
        // preamble is replayed in user history every turn, burning ~80 tokens × N turns.
        systemAddendum: String? = null,
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
    ): Flow<GenerationChunk> {
        // 本次生成的归因上下文：flow 体内更新，收尾（onCompletion）时统一落一条记录。
        // 只收集枚举与计数（不含任何内容）；统计开关关闭时记录为空操作。
        val runCtx = GenerationRunContext(android.os.SystemClock.elapsedRealtime())
        return flow {
        val resolvedExecution = resolveBackendProvider(settings.executionBackend, model, settings.providers) ?: error("Provider not found")
        val provider = resolvedExecution.first
        val execModel = resolvedExecution.second
        val providerImpl = providerManager.getProviderByType(provider)

        // Replay safety: scan the input messages for tools that were Approved + began
        // execution but never produced output (process killed mid-execute). Without this
        // pass, the loop below would treat them as "Approved, ready to run" and execute
        // them AGAIN on replay — could double-charge a remote, duplicate a message send,
        // re-overwrite a file. Flip them to Denied so the model sees a deterministic
        // envelope and decides whether to retry deliberately.
        val requestMessages: List<UIMessage> = messages.map { msg ->
            val newParts = msg.parts.map { part ->
                if (part is UIMessagePart.Tool && part.isInterruptedAttempt) {
                    AppLog.w(TAG, "replay: ${part.toolName} (${part.toolCallId}) had executionStartedAt set with empty output → Denied(interrupted_unknown_outcome)")
                    part.copy(approvalState = ToolApprovalState.Denied(INTERRUPTED_UNKNOWN_OUTCOME_REASON))
                } else part
            }
            if (newParts == msg.parts) msg else msg.copy(parts = newParts)
        }
        val messages = requestMessages

        // 渲染 / 合并档位：按「用户偏好 + 设备能力」解析（探测异常回落默认档）。
        // 只影响中间帧的频率与数量，不改变最终结果，也不改变模型上下文。
        val renderProfile = resolveRenderProfileLogged(
            settings.displaySetting.renderPerformance,
            context,
        )

            val agentRun = GenerationAgentRun(
                context = context,
                json = json,
                toolOutputProcessor = toolOutputProcessor,
                aiLoggingManager = aiLoggingManager,
                systemPromptBuilder = systemPromptBuilder,
                conversationRepo = conversationRepo,
                emitter = this,
                runCtx = runCtx,
                settings = settings,
                model = model,
                execModel = execModel,
                provider = provider,
                providerImpl = providerImpl,
                assistant = assistant,
                initialMessages = messages,
                memories = memories ?: emptyList(),
                tools = tools,
                inputTransformers = inputTransformers,
                outputTransformers = outputTransformers,
                maxSteps = maxSteps,
                renderProfile = renderProfile,
                processingStatus = processingStatus,
                onRetryDiagnosed = onRetryDiagnosed,
                onAfterToolExecution = onAfterToolExecution,
                onBeforeModelRequest = onBeforeModelRequest,
                drainQueuedMessages = drainQueuedMessages,
                isToolAutoApproved = isToolAutoApproved,
                systemAddendum = systemAddendum,
                conversationSystemPrompt = conversationSystemPrompt,
                conversationId = conversationId,
                conversationModeInjectionIds = conversationModeInjectionIds,
                conversationLorebookIds = conversationLorebookIds,
                workspaceCwd = workspaceCwd,
            )
            try {
                agentRun.start()
            } catch (t: Throwable) {
                // 停止生成也要留下与正常完成一致的最终形状（未闭合的 think 块等由收尾变换处理）。
                // 本协程的 Job 此处已取消，普通 suspend 调用会立即再抛，所以在 NonCancellable 里尽力收尾；
                // 收尾自身失败不能顶替原始的取消异常。
                if (t is CancellationException) {
                    runCatching {
                        withContext(NonCancellable) { agentRun.finalizeCancellation() }
                    }.onFailure { finalizeError ->
                        AppLog.w(TAG, "generateText: cancellation finalize failed, stop proceeds without it", finalizeError)
                    }
                }
                throw t
            }

    }
        .onStart {
            // Reset per-turn navigation tracking and surface the overlay so the user
            // sees that automation is happening even when the agent runs from Telegram.
            AgentTurnTracker.reset()
            AgentOverlay.show(context)
        }
        .onCompletion { cause ->
            AgentOverlay.hide(context)
            autoReturnHandler.handleAutoReturnAfterTurn()
            // 运行归因：唯一收尾点（正常结束 / 异常 / 取消都会到这里）。只采数据，不做结论。
            runCatching {
                val outcome =
                    classifyGenerationOutcome(
                        cause = cause,
                        abortReason = runCtx.abortReason,
                        // 只在真的是取消时才取用标记（正常完成不用它，也避免误消化别人的标记）
                        cancelSource =
                            if (cause is CancellationException) {
                                GenerationRunTracker.consumeCancellation(conversationId?.toString())
                            } else {
                                null
                            },
                        rawError = cause?.message.orEmpty(),
                    )
                // provider 只解析一次：id 沿用旧口径，快照 key/name 供历史展示（provider 改名/删除后仍可读）
                val resolvedProvider = runCatching { model.findProvider(settings.providers) }.getOrNull()
                GenerationRunTracker.record(
                    context = context,
                    run =
                        GenerationRun(
                            ts = System.currentTimeMillis(),
                            conversationId = conversationId?.toString(),
                            modelId = model.id.toString(),
                            providerId = resolvedProvider?.id?.toString(),
                            providerKey = resolvedProvider?.providerKey,
                            providerName = resolvedProvider?.name?.takeIf { it.isNotBlank() },
                            modelDisplayName = model.displayName.takeIf { it.isNotBlank() },
                            assistantId = assistant.id.toString(),
                            outcome = outcome.name,
                            finishReason = runCtx.finishReason,
                            errorKind =
                                cause
                                    ?.takeIf { it !is CancellationException }
                                    ?.let { classifyFailureKind(it, it.message.orEmpty()).name },
                            durationMs = android.os.SystemClock.elapsedRealtime() - runCtx.startedAtMs,
                            steps = runCtx.steps,
                            modelMs = runCtx.modelMs,
                            toolMs = runCtx.toolMs,
                            promptTokens = runCtx.promptTokens.toLong(),
                            completionTokens = runCtx.completionTokens.toLong(),
                            cachedTokens = runCtx.cachedTokens.toLong(),
                            // 未上报费用的 provider 记 null（展示为"未上报"），不按 token 估算
                            cost = if (runCtx.costReported) runCtx.cost else null,
                        ),
                )
            }.onFailure { AppLog.w(TAG, "run attribution failed", it) }
        }
        .flowOn(Dispatchers.IO)
    }

    /**
     * If the agent navigated away from RikkaHub Agents during this turn (launch_app / open_url) and
     * the user is still on that destination, bring RikkaHub Agents back to the foreground so the
     * user is not stranded inside Chrome / Termux / etc. If the user manually switched apps
     * mid-turn, we skip the auto-return and surface a Toast explaining the safety behavior.
     */

    /**
     * 工具输出超限时的处置：落盘全文 + 回一段「预览 / 摘要」。
     *
     * [digestKeywords] 非 null = **摘要模式**（助手级名单命中）：预览更短（首段）、关键词命中的行更多，
     * 关键词用调用方给的（空列表 → 内置默认集）。null = 普通截断模式（行为不变）。
     */

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""
            val streamChunkHandler = StreamChunkHandler(model)

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                    maxStreamRetries = if (settings.enableAutoRetry) settings.responseStreamMaxRetries else 0,
                ),
            ).collect { chunk ->
                messages = streamChunkHandler.handle(messages, chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = result.message.toText()

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** 本地独有：对工具输出 parts 做凭证脱敏（SecretMasker），失败降级为原样输出。 */
}

/**
 * 给工具结果追加**审批来源**标记，让模型能区分「用户点了批准」与「自动批准」。
 *
 * `approvalState` 只存在消息 part 上，而发给模型的内容只有 output 文本；不加标记时模型
 * 无法得知本次调用是否经过人工审批。标记追加在最后一个文本块末尾，不改动原有内容，
 * 非文本 part（图片等）原样保留。
 */
internal fun appendApprovalProvenance(
    parts: List<UIMessagePart>,
    approvalState: ToolApprovalState,
): List<UIMessagePart> {
    val marker =
        when (approvalState) {
            ToolApprovalState.Auto -> "[approval: auto]"
            ToolApprovalState.Approved -> "[approval: approved-by-user]"
            ToolApprovalState.Pending -> "[approval: pending]"
            is ToolApprovalState.Denied -> "[approval: denied-by-user]"
            is ToolApprovalState.Answered -> "[approval: answered-by-user]"
        }
    // 追加到最后一个文本块，避免多段文本时中间插入打断阅读
    val lastTextIndex = parts.indexOfLast { it is UIMessagePart.Text }
    if (lastTextIndex < 0) return parts
    return parts.mapIndexed { index, part ->
        if (index == lastTextIndex && part is UIMessagePart.Text) {
            val separator = if (part.text.isEmpty() || part.text.endsWith("\n")) "" else "\n"
            UIMessagePart.Text(text = part.text + separator + marker, metadata = part.metadata)
        } else {
            part
        }
    }
}

/**
 * 压缩检查点摘要发给模型前，在摘要文本尾部追加一段固定「执行提示」。
 *
 * 压缩会把结构化的 tool_call / result 对文字化，上下文里只剩"做过什么"的叙述、缺少
 * "接下来做什么"的行动锚点 —— 表现可能是模型在思考中反复复述计划却不发起工具调用。
 * 这段提示直接对冲该模式。
 *
 * 只在**请求组装时**拼接：不落库、不进 UI 渲染，对同一份摘要文本是确定性的（因此不破坏
 * 提示词缓存前缀），存量压缩记录也自动生效。幂等 —— step 循环里 messages 会回流再组装，
 * 已带提示的文本不再重复追加。
 */
private const val CHECKPOINT_CALIBRATION_NOTE =
    "\n\n[执行提示] 以上是此前对话的摘要，仅供回溯。其中记录的工具调用与结果均已真实发生，" +
        "不要重新执行已成功的调用。工具环境本身没有变化：需要行动时直接发起工具调用，" +
        "不要在思考中复述计划。若摘要与工具的实际状态不一致，先用只读工具核实再行动。" +
        "摘要末尾的「## 待办」小节是下一步行动清单，优先处理其中的事项；该小节不存在时忽略此句。"

internal fun List<UIMessage>.withCheckpointCalibration(): List<UIMessage> {
    val head = firstOrNull() ?: return this
    if (!head.isContextCheckpoint) return this
    var touched = false
    val parts = head.parts.map { part ->
        if (!touched && part is UIMessagePart.Text) {
            touched = true
            if (part.text.contains(CHECKPOINT_CALIBRATION_NOTE)) part
            else UIMessagePart.Text(text = part.text + CHECKPOINT_CALIBRATION_NOTE, metadata = part.metadata)
        } else {
            part
        }
    }
    if (!touched) return this
    return listOf(head.copy(parts = parts)) + drop(1)
}

/**
 * 把压缩检查点与其后紧邻的连续用户消息折叠成**一条**用户回合。
 *
 * 检查点是 USER 角色的散文摘要，它的下一条常常还是用户发言（摘要分块，或保留区的第一条
 * 正落在用户消息上）。协议按消息条数计回合，请求里出现连续两条 user ⇒ 模型读到「两个连续
 * 的用户回合」，压缩点那条「assistant 发起 tool_calls → role:tool 回结果」的示范回合被
 * 割开，表现即压缩后只说不做。本函数只动**回合边界**这一结构，不改任何一个字的内容。
 *
 * 边界刻意收窄，未压缩会话的请求体逐字节不变：
 *  - 只在**最后一条检查点**起始的那一段连续 USER 上生效；无检查点、或检查点后面不是用户
 *    消息时返回自身（引用相等），不产生新列表；
 *  - 含工具 part 的消息**不参与**折叠（`ToolCall` / `ToolResult` 是旧形态，此步在
 *    `ToolPairingRepairTransformer` 之前跑，历史里仍可能出现），绝不触碰
 *    tool_calls ↔ 结果的配对；
 *  - 相邻文本块合并为一块（`\n\n` 分隔、空文本丢弃）：provider 侧一条消息若有多个文本块会
 *    改走 `content` 数组形态，那属请求体形状变化，不是本次要引入的；非文本 part 原样保序；
 *  - 折叠结果沿用检查点的 id / createdAt 与 `isContextCheckpoint`：摘要文本对同一份压缩记录
 *    是固定的，紧随其后的用户发言在本轮内也不变，因此请求前缀仍然稳定，不额外破提示词缓存。
 *
 * 排在 [withCheckpointCalibration] **之后**：校准注先贴回摘要文本尾部，再与后面的用户发言
 * 合成一条，模型最后读到的仍是用户的最新指令。
 *
 * 只在请求副本上做：本函数位于 `internalMessages` 组装链末端，结果只交给 provider，不回写
 * 会话、不落库、不进 UI 渲染；LoopGuard 读的是未经组装的 `messages`，因此本轮重复调用计数
 * 的口径不受影响。
 */
internal fun List<UIMessage>.mergeCheckpointIntoUserTurn(): List<UIMessage> {
    val checkpointIndex = indexOfLast { it.isContextCheckpoint }
    if (checkpointIndex < 0) return this
    val checkpoint = this[checkpointIndex]
    if (checkpoint.role != MessageRole.USER || checkpoint.hasToolPart()) return this

    var endIndex = checkpointIndex + 1
    while (endIndex < size) {
        val message = this[endIndex]
        if (message.role != MessageRole.USER || message.hasToolPart()) break
        endIndex++
    }
    if (endIndex == checkpointIndex + 1) return this

    val run = subList(checkpointIndex, endIndex)
    val merged = checkpoint.copy(
        parts = coalesceAdjacentTextParts(run.flatMap { it.parts }),
        annotations = run.flatMap { it.annotations },
    )
    return take(checkpointIndex) + merged + drop(endIndex)
}

@Suppress("DEPRECATION")
private fun UIMessage.hasToolPart(): Boolean = parts.any { part ->
    part is UIMessagePart.Tool ||
        part is UIMessagePart.ServerTool ||
        part is UIMessagePart.ToolCall ||
        part is UIMessagePart.ToolResult
}

/** 相邻文本块并成一块（`\n\n` 分隔），空白文本丢弃；其余 part 原样保序保留。 */
private fun coalesceAdjacentTextParts(parts: List<UIMessagePart>): List<UIMessagePart> {
    val merged = mutableListOf<UIMessagePart>()
    parts.forEach { part ->
        if (part is UIMessagePart.Text && part.text.isBlank()) return@forEach
        val last = merged.lastOrNull()
        if (part is UIMessagePart.Text && last is UIMessagePart.Text) {
            // 保留首块的 metadata：同一回合内多块的 metadata 无法同时表达，取先出现者
            merged[merged.lastIndex] = UIMessagePart.Text(text = "${last.text}\n\n${part.text}", metadata = last.metadata)
        } else {
            merged += part
        }
    }
    return merged
}
