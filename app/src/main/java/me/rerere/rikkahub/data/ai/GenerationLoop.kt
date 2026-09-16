package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.perf.resolveRenderProfileLogged
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import me.rerere.rikkahub.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.service.AgentOverlay
import me.rerere.rikkahub.service.RikkaAccessibilityService
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseStreamErrorException
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.limitContext
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.redactSecrets
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import java.io.IOException
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.OutputTransformCache
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.transformers.visualTransformsIncremental
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.SecretMasker
import me.rerere.rikkahub.utils.applyPlaceholders
import org.koin.java.KoinJavaComponent.getKoin
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationLoop"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024
private const val GENERATION_STREAM_RETRY_INITIAL_DELAY_MS = 750L
private const val GENERATION_STREAM_RETRY_MAX_DELAY_MS = 4_000L

private val USER_CANCELLATION_MARKERS = listOf(
    "canceled by user",
    "cancelled by user",
    "user_canceled",
    "user_cancelled",
)

// A deterministic 4xx will not succeed on retry, so retrying it just burns quota and delay for
// an outcome that was never going to change. These four are the exceptions: they signal a
// transient condition (timeout, conflict, precondition, rate limit) rather than a request that
// is permanently invalid.
private val RETRYABLE_4XX_STATUS_CODES = setOf(408, 409, 425, 429)

private fun isCancellationFailure(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }
        .take(8)
        .any { cause ->
            cause is CancellationException ||
                USER_CANCELLATION_MARKERS.any { marker ->
                    cause.message?.contains(marker, ignoreCase = true) == true
                }
        }

/**
 * A clean stream close only signals a transport failure worth retrying when NOTHING was ever
 * received. If at least one chunk arrived but none of them yielded parseable parts (e.g. every
 * part shape was unrecognized), that is a permanent condition - retrying the whole generation
 * cannot help, so the caller should log it and let the generation end normally instead of
 * synthesizing a retryable failure.
 */
internal fun shouldReportEmptyGenerationStream(receivedAnyChunk: Boolean): Boolean =
    !receivedAnyChunk

internal fun shouldRetryGenerationStreamFailure(
    failure: Throwable,
    retryAttempt: Long,
    maxRetries: Int,
    receivedMeaningfulOutput: Boolean,
): Boolean {
    if (receivedMeaningfulOutput || retryAttempt >= maxRetries.coerceAtLeast(0).toLong()) {
        return false
    }
    if (failure is ResponseStreamErrorException || isContextLimitFailure(failure)) {
        return false
    }
    if (isNonRetryableClientError(failure)) {
        return false
    }
    if (isQuotaExhaustedFailure(failure)) {
        return false
    }
    // Retry provider, parsing, and local processing failures alike. Cancellation is kept
    // out of the retry loop so stop-generation and parent-scope cancellation propagate.
    return !isCancellationFailure(failure)
}

// A 4xx other than the RETRYABLE_4XX_STATUS_CODES exceptions is deterministic: the same
// request will fail the same way on every retry. 5xx and failures with no known status code
// (most providers don't attach one) keep the existing retry behaviour.
private fun isNonRetryableClientError(failure: Throwable): Boolean {
    val statusCode = generateSequence(failure) { it.cause }
        .take(8)
        .filterIsInstance<HttpException>()
        .firstOrNull()
        ?.statusCode
        ?: return false
    return statusCode in 400..499 && statusCode !in RETRYABLE_4XX_STATUS_CODES
}

// 429 is normally in RETRYABLE_4XX_STATUS_CODES because it usually signals ordinary rate
// limiting, which is worth retrying. But a 429 that also carries a RESOURCE_EXHAUSTED marker
// means the account is quota-blocked server-side (CCA returns this instantly): the same
// request will fail the same way on every retry, so retrying just burns time and requests.
private val QUOTA_EXHAUSTED_MARKERS = listOf(
    "resource exhausted",
    "resource has been exhausted",
)

private fun isQuotaExhaustedFailure(failure: Throwable): Boolean {
    val statusCode = generateSequence(failure) { it.cause }
        .take(8)
        .filterIsInstance<HttpException>()
        .firstOrNull()
        ?.statusCode
    if (statusCode != 429) {
        return false
    }
    return generateSequence(failure) { it.cause }
        .take(8)
        .any { cause ->
            val text = (cause.message.orEmpty() + " " + cause.toString())
                .lowercase()
                .replace('_', ' ')
            QUOTA_EXHAUSTED_MARKERS.any { marker -> marker in text }
        }
}

private fun isContextLimitFailure(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }
        .take(8)
        .any { cause ->
            val text = (cause.message.orEmpty() + " " + cause.toString())
                .lowercase()
                .replace('_', ' ')
            "context length exceeded" in text ||
                "maximum context length" in text ||
                "maximum context window" in text
        }

private fun generationStreamRetryDelayMs(retryAttempt: Long): Long =
    ((retryAttempt + 1) * GENERATION_STREAM_RETRY_INITIAL_DELAY_MS)
        .coerceAtMost(GENERATION_STREAM_RETRY_MAX_DELAY_MS)

private fun retryFailureReason(failure: Throwable): String =
    generateSequence(failure) { it.cause }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?.replace(Regex("\\s+"), " ")
        ?.take(240)
        ?: failure.javaClass.simpleName

/** 错误分类：根据失败原因与异常类型定位问题，命中→资源化标签，未命中→UNKNOWN（原文兜底）。 */
enum class FailureKind {
    CONTENT_SAFETY, AUTH, QUOTA, RATE_LIMIT, MODEL_NOT_FOUND, NETWORK, SERVER, CONTEXT_LENGTH,
    PERMISSION, STORAGE, UNSUPPORTED, BAD_REQUEST, UNKNOWN,
}

fun classifyFailureKind(failure: Throwable, raw: String): FailureKind {
    val text = raw.lowercase() + " " + failure.javaClass.name.lowercase()
    return when {
        listOf("data_inspection_failed", "safetyerror", "content_filter", "inappropriate", "sensitive", "安全", "敏感").any { text.contains(it) } ->
            FailureKind.CONTENT_SAFETY
        listOf("401", "invalid_api_key", "authentication", "unauthorized", "api key", "密钥", "鉴权").any { text.contains(it) } ->
            FailureKind.AUTH
        listOf("402", "insufficient_quota", "insufficientbalance", "余额", "额度", "quota", "billing").any { text.contains(it) } ->
            FailureKind.QUOTA
        listOf("429", "rate_limit", "toomanyrequests", "throttl", "限流", "tpm", "rpm", "per minute", "throughput").any { text.contains(it) } ->
            FailureKind.RATE_LIMIT
        listOf("model_not_found", "invalid_model", "modelnotfound", "not found", "404").any { text.contains(it) } ->
            FailureKind.MODEL_NOT_FOUND
        listOf("permission denied", "eperm", "eacces", "securityexception", "not granted", "权限", "拒绝访问").any { text.contains(it) } ->
            FailureKind.PERMISSION
        listOf("enospc", "no space", "disk full", "read-only file system", "存储空间", "磁盘").any { text.contains(it) } ->
            FailureKind.STORAGE
        listOf("unsupported", "not supported", "不支持", "格式不支持").any { text.contains(it) } ->
            FailureKind.UNSUPPORTED
        listOf("timeout", "sockettimeout", "connectexception", "unknownhost", "unreachable", "timed out", "超时", "网络").any { text.contains(it) } ->
            FailureKind.NETWORK
        listOf(
            "500", "502", "503", "server_error", "internalerror", "internal error", "服务端",
            "unavailable", "upstream", "overloaded", "bad gateway", "gateway timeout", "capacity",
        ).any { text.contains(it) } ->
            FailureKind.SERVER
        listOf("context_length", "token limit", "context_window", "maximum context", "上下文", "超长").any { text.contains(it) } ->
            FailureKind.CONTEXT_LENGTH
        listOf("400", "invalid_request", "bad_request", "invalid parameter", "参数").any { text.contains(it) } ->
            FailureKind.BAD_REQUEST
        else -> FailureKind.UNKNOWN
    }
}

data class FailureDiagnosis(val kind: FailureKind, val label: String, val raw: String)

fun diagnoseFailure(context: Context, failure: Throwable): FailureDiagnosis {
    val raw = retryFailureReason(failure)
    val kind = classifyFailureKind(failure, raw)
    val label = when (kind) {
        FailureKind.CONTENT_SAFETY -> context.getString(me.rerere.rikkahub.R.string.error_kind_content_safety)
        FailureKind.AUTH -> context.getString(me.rerere.rikkahub.R.string.error_kind_auth)
        FailureKind.QUOTA -> context.getString(me.rerere.rikkahub.R.string.error_kind_quota)
        FailureKind.RATE_LIMIT -> context.getString(me.rerere.rikkahub.R.string.error_kind_rate_limit)
        FailureKind.MODEL_NOT_FOUND -> context.getString(me.rerere.rikkahub.R.string.error_kind_model_not_found)
        FailureKind.NETWORK -> context.getString(me.rerere.rikkahub.R.string.error_kind_network)
        FailureKind.SERVER -> context.getString(me.rerere.rikkahub.R.string.error_kind_server)
        FailureKind.CONTEXT_LENGTH -> context.getString(me.rerere.rikkahub.R.string.error_kind_context_length)
        FailureKind.PERMISSION -> context.getString(me.rerere.rikkahub.R.string.error_kind_permission)
        FailureKind.STORAGE -> context.getString(me.rerere.rikkahub.R.string.error_kind_storage)
        FailureKind.UNSUPPORTED -> context.getString(me.rerere.rikkahub.R.string.error_kind_unsupported)
        FailureKind.BAD_REQUEST -> context.getString(me.rerere.rikkahub.R.string.error_kind_bad_request)
        FailureKind.UNKNOWN -> context.getString(me.rerere.rikkahub.R.string.error_kind_unknown)
    }
    return FailureDiagnosis(kind, label, raw)
}

private fun retryStatusText(
    context: Context,
    retryNumber: Long,
    maxRetries: Int,
    failure: Throwable,
): String {
    val diag = diagnoseFailure(context, failure)
    return context.getString(
        me.rerere.rikkahub.R.string.chat_page_retrying,
        retryNumber,
        maxRetries,
        diag.label,
        diag.raw,
    )
}

private fun clearRetryStatus(processingStatus: MutableStateFlow<String?>) {
    processingStatus.value = null
}

// Marks the retry loop's "meaningful output already arrived" flag. Only chunks that carry
// actual model output (text/reasoning/tool/image content, or annotations) count - the bare
// Start/End markers and Usage/Finish bookkeeping chunks don't, mirroring the old
// choice.delta/message.parts.isNotEmpty() check against the pre-refactor chunk shape.
private fun isMeaningfulStreamChunk(chunk: StreamChunk): Boolean = when (chunk) {
    is StreamChunk.TextDelta,
    is StreamChunk.ReasoningDelta,
    is StreamChunk.ToolCallDelta,
    is StreamChunk.ImageDelta,
    is StreamChunk.ImageSnapshot,
    is StreamChunk.ServerToolStart,
    is StreamChunk.ServerToolInputDelta,
    is StreamChunk.ServerToolEnd,
    is StreamChunk.Annotations -> true
    else -> false
}

private suspend fun <T> retryGenerationTransportRequest(
    maxRetries: Int,
    onRetry: (retryNumber: Long, failure: Throwable) -> Unit = { _, _ -> },
    request: suspend () -> T,
): T {
    var retryAttempt = 0L
    while (true) {
        try {
            return request()
        } catch (failure: Throwable) {
            if (!shouldRetryGenerationStreamFailure(
                    failure = failure,
                    retryAttempt = retryAttempt,
                    maxRetries = maxRetries,
                    receivedMeaningfulOutput = false,
                )) {
                throw failure
            }
            val delayMs = generationStreamRetryDelayMs(retryAttempt)
            AppLog.w(
                TAG,
                "generateText: retrying after failure " +
                    "(${retryAttempt + 1}/$maxRetries) in ${delayMs}ms",
                failure,
            )
            onRetry(retryAttempt + 1, failure)
            delay(delayMs)
            retryAttempt++
        }
    }
}

/**
 * Replace older tool-result `Image` parts with a small text elision so the same JPEGs
 * aren't re-encoded into base64 on every subsequent step. We keep the
 * [IMAGE_KEEP_LAST_N_TOOL_RESULTS] most-recent tool-result-bearing assistant messages
 * verbatim and elide everything older. User uploads (`role=USER`) are NEVER elided —
 * those are real input the model needs to reason over. Assistant-generated images
 * (model image-gen output) are also kept verbatim as those are visible product, not
 * intermediate reasoning state.
 */
private fun List<UIMessage>.ageOldToolImages(): List<UIMessage> {
    var toolResultsWithImagesSeen = 0
    return this.asReversed().map { msg ->
        if (msg.role == MessageRole.USER) return@map msg
        val hasImageInTool = msg.parts.any { p ->
            p is UIMessagePart.Tool && p.output.any { it is UIMessagePart.Image }
        }
        if (!hasImageInTool) return@map msg
        toolResultsWithImagesSeen++
        if (toolResultsWithImagesSeen <= IMAGE_KEEP_LAST_N_TOOL_RESULTS) return@map msg
        val newParts = msg.parts.map { part ->
            if (part is UIMessagePart.Tool) {
                val newOutput = part.output.map { o ->
                    if (o is UIMessagePart.Image) {
                        UIMessagePart.Text(
                            "[image elided — original at ${o.url}; superseded by newer screenshots]"
                        )
                    } else o
                }
                part.copy(output = newOutput)
            } else part
        }
        msg.copy(parts = newParts)
    }.asReversed()
}

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
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
private const val MAX_LOOP_GUARD_TRIPS_PER_TURN = 6

// 轮预算将尽时提前注入收尾指令的宽限窗口：让模型总结收场，而不是被硬掐在半句话上
private const val WRAP_UP_GRACE_MS = 120_000L

// 流式期间「输出变换 + 界面投递」的合并窗口由当前渲染档位给出（见 data/perf/RenderProfile.kt）：
// 每个 token 都做一次全量变换，在长会话下开销随消息数增长。同一窗口内只保留最新快照，
// 并在 step 边界与生成结束时强制补齐，最终结果与逐次变换一致（窗口为 0 即恢复逐次处理）。

// 流式分块的合并窗口同样来自当前渲染档位（见 data/perf/RenderProfile.kt）：文本 delta 到达
// 频率很高，逐块拼接与投递在长回复下代价是 O(n²)。窗口内先累积再按序应用，流终止时补齐，
// 最终内容与逐块处理一致（窗口为 0 即恢复逐块处理）。

// 断流续写：服务端未给出结束原因（finish_reason 缺失）时，说明流被中途切断，
// 自动再请求一次把内容接上；每个回合最多续写次数，以及续写时注入的合成指令。
private const val MAX_AUTO_CONTINUE = 1

/**
 * Number of most-recent tool-result-bearing messages whose `Image` parts are kept
 * verbatim in the prompt. Older tool-result images are replaced with a small text
 * elision so the same JPEG isn't re-encoded into base64 on every step. Without this
 * a screen-automation turn that takes 5 screenshots makes the provider re-pay
 * ~1–2MB × 5 base64 encode + upload on every subsequent step.
 *
 * 2 is the smallest value that lets the model do "look at this screenshot, decide
 * action; take new screenshot, compare" — needs both the previous and the current
 * screenshot in context. Anything older has been superseded.
 */
private const val IMAGE_KEEP_LAST_N_TOOL_RESULTS = 2

/**
 * Hard ceiling on a single streamed generation step. Guards the "model thinks for 2000+ seconds"
 * failure mode, which no chunk-level check can catch (the stream stays alive and looks productive
 * the whole time). Deliberately generous so ordinary long generations are never cut short.
 */
private const val MAX_STREAM_DURATION_MS = 15 * 60 * 1000L

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
 * Pure, testable loop-detection decision, extracted from [GenerationLoop.generateText] so
 * the act-observe reset and freshness-TTL rules can be unit-tested without an Android Context.
 */
internal object LoopGuard {
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

/** 按 executionBackend 解析执行 provider + 模型：local/空→模型自动；否则→指定 provider(取该 provider 默认模型)。 */
private fun resolveBackendProvider(executionBackend: String, model: Model, providers: List<ProviderSetting>): Pair<ProviderSetting, Model>? =
    if (executionBackend.isBlank() || executionBackend == "local") {
        model.findProvider(providers)?.let { it to model }
    } else {
        providers.firstOrNull { it.id.toString() == executionBackend }?.let { p -> p to (p.models.firstOrNull() ?: model) }
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
    ): Flow<GenerationChunk> = flow {
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
        var messages: List<UIMessage> = messages.map { msg ->
            val newParts = msg.parts.map { part ->
                if (part is UIMessagePart.Tool && part.isInterruptedAttempt) {
                    AppLog.w(TAG, "replay: ${part.toolName} (${part.toolCallId}) had executionStartedAt set with empty output → Denied(interrupted_unknown_outcome)")
                    part.copy(approvalState = ToolApprovalState.Denied(
                        "interrupted_unknown_outcome: a previous attempt to execute this tool started " +
                            "but did not complete (process killed mid-execute). The side effect MAY OR " +
                            "MAY NOT have happened. Verify the target state before retrying — do not " +
                            "blindly re-run the same call."
                    ))
                } else part
            }
            if (newParts == msg.parts) msg else msg.copy(parts = newParts)
        }

        // 渲染 / 合并档位：按「用户偏好 + 设备能力」解析（探测异常回落默认档）。
        // 只影响中间帧的频率与数量，不改变最终结果，也不改变模型上下文。
        val renderProfile = resolveRenderProfileLogged(
            settings.displaySetting.renderPerformance,
            context,
        )

        val turnStartMs = android.os.SystemClock.elapsedRealtime()
        var loopGuardTripCount = 0
        var wrapUpInjected = false
        // 断流续写计数（见 MAX_AUTO_CONTINUE）
        var autoContinueCount = 0
        // 输出变换节流状态（窗口见渲染档位）：窗口内被合并掉的最新快照，
        // 由 step 边界 / 生成结束补齐；任何"直接投递"点都会把它清空，避免旧快照回退内容。
        var lastOutputFlushAtMs = 0L
        var pendingOutputMessages: List<UIMessage>? = null
        // 增量输出变换缓存（见 visualTransformsIncremental）：按 step 隔离，
        // 历史消息段复用上次结果，单块成本从 O(消息数) 降到 O(1)。
        val outputTransformCache = OutputTransformCache()

        for (stepIndex in 0 until maxSteps) {
            outputTransformCache.clear()
            // Wall-clock cap: any single user turn that has been running longer than the
            // budget is force-ended, regardless of whether the model wants more steps.
            // This is the second line of defence after maxSteps; without it a model that
            // discovers many distinct tool calls (each within the loop guard) can still
            // run for hours.
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - turnStartMs
            // 优雅收尾：预算进入宽限窗口时注入一次性收尾指令（合成消息，不落库不进历史），
            // 让模型停止开新工具并总结收场；硬掐只作为最终兜底
            val remainingBudgetMs = ToolRuntimeLimits.turnBudgetMs - elapsedMs
            if (remainingBudgetMs in 1..WRAP_UP_GRACE_MS && !wrapUpInjected) {
                wrapUpInjected = true
                AppLog.w(TAG, "generateText: turn budget nearly exhausted (${remainingBudgetMs}ms left); injecting wrap-up reminder")
                messages = messages + UIMessage.user(
                    context.getString(R.string.ai_wrap_up_notice),
                ).copy(isSynthetic = true)
            }
            if (elapsedMs > ToolRuntimeLimits.turnBudgetMs) {
                AppLog.w(TAG, "generateText: wall-clock cap (${ToolRuntimeLimits.turnBudgetMs}ms) hit at step #$stepIndex; force-ending turn")
                break
            }
            // Repeated loop-guard trips mean the model is flailing: it bumps into the
            // guard, picks a different tool, that one also gets guarded, and so on. After
            // N trips we just stop — the model is not going to recover, and every extra
            // step is paid for in tokens.
            if (loopGuardTripCount >= MAX_LOOP_GUARD_TRIPS_PER_TURN) {
                AppLog.w(TAG, "generateText: loop-guard tripped $loopGuardTripCount times this turn; force-ending")
                break
            }

            AppLog.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            // step 边界：把上一轮流式期间合并掉的输出变换补上，
            // 保证本 step 的请求上下文与界面状态与逐次变换完全一致
            pendingOutputMessages?.let { pending ->
                pendingOutputMessages = null
                lastOutputFlushAtMs = android.os.SystemClock.elapsedRealtime()
                messages = pending.transforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                emit(
                    GenerationChunk.Messages(
                        messages.visualTransformsIncremental(
                            transformers = outputTransformers,
                            cache = outputTransformCache,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                    )
                )
            }

            // 工具面在调用前由装配工厂统一构建（记忆/搜索/本地/工作区/技能/MCP），
            // 这里只做只读引用，避免两处拼装漂移。
            val toolsInternal = tools

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            // Mixed-state guard: if the last message has tools STILL in Pending (waiting
            // on user approval keyboard) but nothing canResumeExecution, the existing
            // path would call generateInternal and start a brand-new assistant turn,
            // orphaning the Pending tool. Bail out instead and let handleToolApproval
            // re-enter when the user taps the keyboard.
            if (pendingTools.isEmpty()) {
                val lastHasPending = messages.lastOrNull()?.parts?.any { p ->
                    p is UIMessagePart.Tool && p.isPending
                } == true
                if (lastHasPending) {
                    AppLog.i(TAG, "generateText: last message has Pending tools; waiting for approval, not regenerating")
                    break
                }
            }

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                try {
                    onBeforeModelRequest()
                    generateInternal(
                        assistant = assistant,
                        settings = settings,
                        systemAddendum = systemAddendum,
                        messages = messages,
                        onUpdateMessages = {
                            val nowFlushMs = android.os.SystemClock.elapsedRealtime()
                            if (nowFlushMs - lastOutputFlushAtMs >= renderProfile.outputFlushIntervalMs) {
                                lastOutputFlushAtMs = nowFlushMs
                                pendingOutputMessages = null
                                messages = it.transforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                                emit(
                                    GenerationChunk.Messages(
                                        messages.visualTransformsIncremental(
                                            transformers = outputTransformers,
                                            cache = outputTransformCache,
                                            context = context,
                                            model = model,
                                            assistant = assistant,
                                            settings = settings
                                        )
                                    )
                                )
                            } else {
                                // 合并窗口内：只记录最新快照，跳过的中间帧由下一次投递或
                                // step 边界/结束时的补齐覆盖（最终结果与逐次处理一致）
                                messages = it
                                pendingOutputMessages = it
                            }
                        },
                        transformers = inputTransformers,
                        model = model,
                        providerImpl = providerImpl,
                        provider = provider,
                        tools = toolsInternal,
                        memories = memories ?: emptyList(),
                        stream = assistant.streamOutput,
                        processingStatus = processingStatus,
                        conversationSystemPrompt = conversationSystemPrompt,
                        conversationId = conversationId,
                        conversationModeInjectionIds = conversationModeInjectionIds,
                        conversationLorebookIds = conversationLorebookIds,
                        workspaceCwd = workspaceCwd,
                    )
                } catch (t: Throwable) {
                    // CancellationException is honoured verbatim — stopGeneration has its
                    // own cancelToolByUser path that marks tools cancelled. We only need
                    // to handle non-cancel failures here.
                    if (t !is CancellationException) {
                        // Server 5xx, JSON parse failure, OOM during chunk-merge, etc. Without
                        // this transition, any tool already at Auto/Pending in the just-built
                        // assistant message is stranded — the next user turn replays the
                        // conversation with tool parts in an in-between state and downstream
                        // filtering misbehaves. We mark them Denied with a generation_failed
                        // envelope so the shape is deterministic on replay.
                        val lastMsg = messages.lastOrNull()
                        if (lastMsg != null) {
                            val newParts = lastMsg.parts.map { part ->
                                if (part is UIMessagePart.Tool &&
                                    (part.approvalState is ToolApprovalState.Auto ||
                                        part.approvalState is ToolApprovalState.Pending)) {
                                    part.copy(approvalState = ToolApprovalState.Denied(
                                        "generation_failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}"
                                    ))
                                } else part
                            }
                            messages = messages.dropLast(1) + lastMsg.copy(parts = newParts)
                            emit(GenerationChunk.Messages(messages))
                        }
                    }
                    throw t
                }
                messages = messages.visualTransformsIncremental(
                    transformers = outputTransformers,
                    cache = outputTransformCache,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                // 断流续写：服务端未给出结束原因（finish_reason 缺失）、文本非空且没有待执行工具时，
                // 判定为流被中途切断 → 自动再请求一次把内容接上（受 MAX_AUTO_CONTINUE 限制）。
                val lastAssistant = messages.lastOrNull()
                val truncatedNoFinish =
                    lastAssistant != null &&
                        lastAssistant.role == MessageRole.ASSISTANT &&
                        lastAssistant.finishedAt != null &&
                        lastAssistant.finishReason == null &&
                        lastAssistant.getTools().isEmpty() &&
                        lastAssistant.parts.any { it is UIMessagePart.Text && it.text.isNotBlank() }
                if (truncatedNoFinish && autoContinueCount < MAX_AUTO_CONTINUE) {
                    autoContinueCount++
                    AppLog.w(
                        TAG,
                        "generateText: reply ended without finish reason; auto-continuing ($autoContinueCount/$MAX_AUTO_CONTINUE)",
                    )
                    messages = messages + UIMessage.user(
                        context.getString(R.string.ai_auto_continue_prompt),
                    ).copy(isSynthetic = true)
                    continue
                }

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // Imperative loop (was .map) so we can call the suspending
                // [isToolAutoApproved] FRESH per-tool, not from a frozen pre-resolved set.
                // Without this, a grant landing between the pre-resolve and the .map
                // (user taps Always-Allow on tool X mid-iteration) gets ignored — X
                // flips to Pending and a duplicate prompt is emitted even though X is
                // now persisted-approved.
                var hasPendingApproval = false
                val updatedTools = ArrayList<UIMessagePart.Tool>(tools.size)
                for (tool in tools) {
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    // 诊断（「审批停住但无按钮」排查）：把判定输入与结果一次性打出来。
                    // 这一步回答的问题是：GenLoop 到底有没有走到「需要审批」分支。
                    val needsApprovalResolved = toolDef?.needsApproval(tool.inputAsJson()) == true
                    AppLog.i(
                        TAG,
                        "approval-check ${tool.toolName}: toolDefFound=${toolDef != null} " +
                            "needsApproval=$needsApprovalResolved state=${tool.approvalState} " +
                            "available=${toolsInternal.size}",
                    )
                    // HARDLINE check: certain command patterns (rm -rf /, mkfs, shutdown,
                    // fork bomb, …) are blocked unconditionally — even "Always Allow"
                    // can't override. We check BEFORE the auto-approval lookup so a
                    // permanently-allowed termux/ssh tool still can't smuggle one of
                    // these through. Result: tool is marked Denied with the hardline
                    // reason, the regular Denied branch downstream emits an error
                    // envelope to the model without executing.
                    val hardlineReason = me.rerere.rikkahub.data.ai.tools
                        .HardlineCommandGuard.checkTool(tool.toolName, tool.input)
                    val transformed = when {
                        hardlineReason != null && tool.approvalState is ToolApprovalState.Auto -> {
                            AppLog.w(TAG, "hardline-blocked ${tool.toolName}: $hardlineReason")
                            tool.copy(approvalState = ToolApprovalState.Denied(
                                "blocked by safety floor (hardline): $hardlineReason. " +
                                    "This command cannot run via the agent under any " +
                                    "circumstances. If the user genuinely needs it, they " +
                                    "should run it themselves in a terminal outside the agent."
                            ))
                        }
                        // Tool needs approval and state is Auto:
                        needsApprovalResolved &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            // Fresh per-tool auto-approval check (was a frozen pre-
                            // resolved set). Costs a DataStore.first() per tool but tools
                            // are typically <5 per turn so the latency is negligible, and
                            // freshness matters for the YOLO toggle / mid-iteration grants.
                            if (isToolAutoApproved(tool.toolName)) {
                                AppLog.i(TAG, "approval-auto ${tool.toolName}: auto-approved, running without prompt")
                                tool  // leave as Auto so the executor runs it without prompting
                            } else {
                                hasPendingApproval = true
                                AppLog.i(TAG, "approval-pending ${tool.toolName}: marked Pending, waiting for user")
                                tool.copy(approvalState = ToolApprovalState.Pending)
                            }
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                    updatedTools.add(transformed)
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    AppLog.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                AppLog.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool.
                        //
                        // Defence-in-depth HARDLINE re-check: the primary check at line ~442
                        // only runs when approvalState is Auto (the generation step that just
                        // proposed the tool). On the resume path (pendingTools branch above)
                        // tools arrive here with state=Approved and skip that block entirely.
                        // Re-check here so that a hardline-matched tool persisted in Approved
                        // state from an old DB row (pre-hardline schema, direct DB edit) can
                        // never execute via the resume path.
                        val resumeHardlineReason = me.rerere.rikkahub.data.ai.tools
                            .HardlineCommandGuard.checkTool(tool.toolName, tool.input)
                        if (resumeHardlineReason != null) {
                            AppLog.w(TAG, "generateText: resume-path hardline re-check blocked ${tool.toolName}: $resumeHardlineReason")
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(buildJsonObject {
                                            put("error", JsonPrimitive(
                                                "blocked by safety floor (hardline): $resumeHardlineReason. " +
                                                    "This command cannot run via the agent under any circumstances."
                                            ))
                                        })
                                    )
                                )
                            )
                            return@forEach
                        }

                        // Loop-guard: check whether the model has already called this exact
                        // tool with the same args multiple times in this turn. Refuse a
                        // repeat run and inject a "loop_detected" envelope so the model has
                        // to pivot to a different approach. Cost safety net.
                        val signature = tool.toolName + "::" + tool.input
                        // "This turn" = since the most recent user message. Earlier
                        // identical calls in PREVIOUS turns aren't the model flailing
                        // now — they're history, and counting them produces a confusing
                        // "you already called this 3 times in this turn" envelope after
                        // a single fresh call.
                        val turnStartIndex = messages.indexOfLast { it.role == MessageRole.USER }
                        val turnSlice = messages.subList(
                            (turnStartIndex + 1).coerceAtLeast(0),
                            messages.size
                        )
                        // Flatten this turn's executed tool calls in chronological order. The
                        // epoch ms (for the freshness-TTL bypass) comes from the parent
                        // message's finish/create time, matching the prior inline behaviour.
                        val priorCalls = turnSlice.flatMap { msg ->
                            val epochMs = (msg.finishedAt ?: msg.createdAt)
                                .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                            msg.parts.filterIsInstance<UIMessagePart.Tool>()
                                .filter { it.isExecuted }
                                .map { PriorToolCall(it.toolName, it.toolName + "::" + it.input, epochMs) }
                        }
                        val loopDecision = LoopGuard.evaluate(
                            priorCalls = priorCalls,
                            toolName = tool.toolName,
                            signature = signature,
                            nowMs = System.currentTimeMillis(),
                        )
                        val priorOccurrences = loopDecision.priorOccurrences
                        if (loopDecision.block) {
                            loopGuardTripCount++
                            AppLog.w(TAG, "generateText: loop-guard tripped on $signature (${priorOccurrences + 1} repeat, trip #$loopGuardTripCount this turn); injecting bail-out envelope")
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put("error", JsonPrimitive("loop_detected"))
                                                put(
                                                    "recovery", JsonPrimitive(
                                                        "You have already called ${tool.toolName} with identical arguments " +
                                                            "${priorOccurrences} time(s) in this turn without making progress. " +
                                                            "Stop retrying. Either: (a) change the args meaningfully, (b) try a " +
                                                            "different tool that addresses the underlying request, or (c) hand " +
                                                            "back to the user with what you have so far. Examples: for 'search " +
                                                            "X in chrome' use open_url(\"https://www.google.com/search?q=X\") " +
                                                            "instead of fighting Chrome's URL bar via set_text; for terminal " +
                                                            "tasks use termux_run_command instead of typing into Termux."
                                                    )
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                            // Skip the actual execution. The next generation step will see
                            // this envelope and (if the model is well-prompted by the skill
                            // docs) will pivot to a different approach.
                            return@forEach
                        }
                        // Pre-parse args BEFORE the runCatching block so we can surface a
                        // clean structured envelope when the LLM provider truncates the
                        // streaming response mid-string (max_tokens hit, network drop, etc.).
                        // Without this, kotlinx.serialization's raw exception message —
                        // which includes the entire failed input — lands in the LLM-facing
                        // `detail` field, can be thousands of tokens, and the model often
                        // retries the same too-big call.
                        val parsedArgs = runCatching {
                            json.parseToJsonElement(tool.input.ifBlank { "{}" })
                        }
                        if (parsedArgs.isFailure) {
                            val cause = parsedArgs.exceptionOrNull()
                            cause?.let { AppLog.w(TAG, "tool ${tool.toolName} args failed to parse (likely truncated stream)", it) }
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(buildJsonObject {
                                            put("error", JsonPrimitive("invalid_tool_args"))
                                            put(
                                                "detail",
                                                JsonPrimitive(
                                                    (cause?.message ?: cause?.javaClass?.simpleName ?: "json_parse_failed")
                                                        .take(200)
                                                ),
                                            )
                                            put(
                                                "recovery",
                                                JsonPrimitive(
                                                    "Tool args JSON failed to parse — most often the provider's " +
                                                        "stream was cut off mid-string by max_tokens or a network drop. " +
                                                        "Retry with a shorter call. For long payloads (e.g. a 4000-char " +
                                                        "message), split into multiple smaller tool calls or shrink the " +
                                                        "content."
                                                ),
                                            )
                                            put(
                                                "exception",
                                                JsonPrimitive(cause?.javaClass?.simpleName ?: "JsonParseException"),
                                            )
                                        })
                                    )
                                )
                            )
                            return@forEach
                        }
                        // Resolve the tool def BEFORE the runCatching block, same reason as
                        // parsedArgs above: this is not a random tool-body throw, so it gets
                        // its own structured envelope naming exactly what was called and what
                        // was actually available — rather than the generic 500-char-capped
                        // exception message the runCatching/.onFailure below produces (#88:
                        // this is the self-diagnosing surface for a server that connects and
                        // lists tools but contributes zero entries to the dispatch list, e.g.
                        // a newly mcp_add-ed server never enabled for this assistant).
                        val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                        if (toolDef == null) {
                            AppLog.w(TAG, "tool ${tool.toolName} not found among ${toolsInternal.size} tools available this turn")
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(buildJsonObject {
                                            put("error", JsonPrimitive("tool_not_found"))
                                            put(
                                                "detail",
                                                JsonPrimitive("Tool '${tool.toolName}' was called but is not among the tools available this turn."),
                                            )
                                            put(
                                                "tools_available_this_turn",
                                                JsonPrimitive(toolsInternal.joinToString(", ") { it.name }.take(1500)),
                                            )
                                        })
                                    )
                                )
                            )
                            return@forEach
                        }
                        runCatching {
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error(
                                    // 工具面按会话动态构建（工作区就绪状态、技能开关、
                                    // MCP 在线情况都会增删工具），历史里出现本轮已不可用的
                                    // 调用是正常的。给出可行动的信息，而不是干巴巴的 not found：
                                    // 模型可据此换用替代手段或直接向用户说明。
                                    "Tool '${tool.toolName}' is unavailable in this turn. " +
                                        "It may be disabled in the assistant's tool settings, " +
                                        "or its prerequisite (such as the workspace shell) is not " +
                                        "ready yet. Do not retry it; use another available tool or " +
                                        "explain to the user what is needed.",
                                )
                            val args = parsedArgs.getOrThrow()
                            if (BuildConfig.DEBUG) {
                                AppLog.i(TAG, "generateText: executing tool ${toolDef.name} with args: ${redactSecrets(args)}")
                            }
                            // Mark the tool as "execution started" BEFORE actually running.
                            // ChatService persists this when it sees the chunk so a process
                            // kill between mark-and-output leaves a clear breadcrumb on disk:
                            // on replay we'll see Approved + executionStartedAt + empty output
                            // and refuse to silently re-run. The mark survives via the
                            // existing emit-and-persist plumbing — see ChatService chunk
                            // handler's needsImmediatePersist branch.
                            val markedTool = tool.copy(executionStartedAt = System.currentTimeMillis())
                            run {
                                val lastMsg = messages.lastOrNull()
                                if (lastMsg != null) {
                                    val markedParts = lastMsg.parts.map { p ->
                                        if (p is UIMessagePart.Tool && p.toolCallId == tool.toolCallId) markedTool else p
                                    }
                                    messages = messages.dropLast(1) + lastMsg.copy(parts = markedParts)
                                    emit(GenerationChunk.Messages(messages))
                                }
                            }
                            // Hard-cap individual tool execution at the remaining wall-clock
                            // budget so a single tool with its OWN long timeout (camera 5min,
                            // ssh_exec timeout_seconds=300) can't carry the turn past the
                            // global ${ToolRuntimeLimits.turnBudgetMs}ms cap. If the budget is
                            // already blown when we start the tool, return a structured
                            // wall-clock envelope instead of even attempting.
                            val remainingMs = ToolRuntimeLimits.turnBudgetMs -
                                (android.os.SystemClock.elapsedRealtime() - turnStartMs)
                            val result = if (remainingMs <= 0L) {
                                AppLog.w(TAG, "generateText: ${toolDef.name} skipped — wall-clock budget already exceeded")
                                listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
                                    put("error", JsonPrimitive("tool_cancelled_wall_clock"))
                                    put("detail", JsonPrimitive("turn budget exceeded before tool started"))
                                })))
                            } else {
                                withTimeoutOrNull(remainingMs) { toolDef.execute(args) }
                                    ?: run {
                                        AppLog.w(TAG, "generateText: ${toolDef.name} cancelled — wall-clock budget exhausted mid-execution")
                                        listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
                                            put("error", JsonPrimitive("tool_cancelled_wall_clock"))
                                            put(
                                                "detail",
                                                JsonPrimitive("tool execution exceeded the ${ToolRuntimeLimits.turnBudgetMs / 1000}s turn budget")
                                            )
                                        })))
                                    }
                            }
                            // Tool-output truncation: when the workspace shell is
                            // available, oversized text output is spilled to /tool_outputs/
                            // and replaced with a preview + read/grep instructions so the
                            // model can pull the full payload on demand instead of burning
                            // the context window.
                            val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                            // 本地独有：工具结果写回前做凭证脱敏（SecretMasker 掩码），避免密钥进上下文
                            val maskedResult = maskToolOutput(result)
                            executedTools += markedTool.copy(
                                output = maybeTruncateToolOutput(tool.toolCallId, maskedResult, hasShellAccess)
                            )
                        }.onFailure {
                            // Stack trace stays in logcat for debugging; the JSON envelope
                            // sent BACK to the LLM gets just the exception's message and a
                            // short class hint. Stuffing the full multi-frame R8-obfuscated
                            // trace into `error` (the prior behaviour) burned hundreds of
                            // tokens per failure, confused the model, and surfaced
                            // user-visible "java.lang.IllegalStateException at ..." walls
                            // for what was usually a one-line "name is required" problem.
                            AppLog.w(TAG, "tool ${tool.toolName} threw", it)
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put("error", JsonPrimitive("tool_failed"))
                                                put(
                                                    "detail",
                                                    // Cap at 500 chars so a tool that throws with
                                                    // a giant message (e.g. an OkHttp body dump or
                                                    // an echoed input arg) doesn't ship 8000+
                                                    // tokens back to the LLM on every failure.
                                                    JsonPrimitive((it.message ?: it.javaClass.simpleName).take(500)),
                                                )
                                                // Class name as a separate hint so the LLM can
                                                // distinguish validation (IllegalStateException /
                                                // IllegalArgumentException) from runtime issues.
                                                put(
                                                    "exception",
                                                    JsonPrimitive(it.javaClass.simpleName),
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
            // 已按最新 messages 直接投递，之前合并的待处理快照作废
            pendingOutputMessages = null

            onAfterToolExecution(messages)?.let { compactedMessages ->
                AppLog.i(TAG, "generateText: replacing request history after tool execution")
                messages = compactedMessages
            }

            // 队列消息在「本 step 的工具执行完毕、下一次模型请求之前」插入：
            // 用户的补充/修正能在下一 step 直接被模型看到（而不是等整轮结束）。
            val queued = drainQueuedMessages()
            if (queued.isNotEmpty()) {
                AppLog.i(TAG, "generateText: injecting ${queued.size} queued user message(s) at end of step #$stepIndex")
                messages = messages + queued
                emit(
                    GenerationChunk.Messages(
                        messages.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings,
                        )
                    )
                )
                // 已按最新 messages 直接投递，之前合并的待处理快照作废
                pendingOutputMessages = null
            }
        }

        // 生成结束：补齐最后一次被合并的输出变换（保证界面与落盘拿到最终状态）
        pendingOutputMessages?.let { pending ->
            pendingOutputMessages = null
            messages = pending.transforms(
                transformers = outputTransformers,
                context = context,
                model = model,
                assistant = assistant,
                settings = settings
            )
            emit(
                GenerationChunk.Messages(
                    messages.visualTransformsIncremental(
                        transformers = outputTransformers,
                        cache = outputTransformCache,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }

    }
        .onStart {
            // Reset per-turn navigation tracking and surface the overlay so the user
            // sees that automation is happening even when the agent runs from Telegram.
            AgentTurnTracker.reset()
            AgentOverlay.show(context)
        }
        .onCompletion {
            AgentOverlay.hide(context)
            handleAutoReturnAfterTurn()
        }
        .flowOn(Dispatchers.IO)

    /**
     * If the agent navigated away from RikkaHub during this turn (launch_app / open_url) and
     * the user is still on that destination, bring RikkaHub back to the foreground so the
     * user is not stranded inside Chrome / Termux / etc. If the user manually switched apps
     * mid-turn, we skip the auto-return and surface a Toast explaining the safety behavior.
     */
    private fun handleAutoReturnAfterTurn() {
        if (!AgentTurnTracker.didNavigateAway()) return
        // Only auto-return when the agent actually drove the destination app via screen
        // automation (tap, click_node, set_text, swipe, scroll, global_action). A pure
        // "open Chrome and stay there" request is just launch_app + a text reply — yanking
        // the user back to RikkaHub in that case defeats the purpose of the request.
        if (!AgentTurnTracker.didAutomate()) return
        val destination = AgentTurnTracker.lastDestination()
        val currentForeground = RikkaAccessibilityService.instance
            ?.rootInActiveWindow?.packageName?.toString()

        val userSwitchedAway = destination != null
            && currentForeground != null
            && currentForeground != destination
            && currentForeground != context.packageName

        if (userSwitchedAway) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context.applicationContext,
                    "RikkaHub Agents: skipped auto-return because you switched apps. (Safety feature)",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // startActivity throws ActivityNotFoundException / SecurityException —
            // both Exception. Catching Throwable here would also swallow JVM errors
            // (OOM, StackOverflowError); let those propagate.
            AppLog.w(TAG, "auto-return launch failed", e)
        }
    }

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        systemAddendum: String? = null,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        // Called on each retry after the failure has been diagnosed (label + raw text).
        // ChatService uses it to persist the last failure into the conversation when a
        // retry finally succeeds, so the model knows the generation recovered.
        onRetryDiagnosed: ((FailureDiagnosis) -> Unit)? = null,
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
    ) {
        // 流式分块合并窗口同样取自渲染档位（0 = 逐块处理，与未优化的原始行为一致）
        val renderProfile = resolveRenderProfileLogged(
            settings.displaySetting.renderPerformance,
            context,
        )
        val internalMessages = buildList {
            // Conversation-level system prompt override: when the assistant
            // allows it and the conversation supplies one, it replaces the assistant prompt.
            val effectiveSystemPrompt =
                if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                    conversationSystemPrompt
                } else {
                    assistant.systemPrompt
                }
            val memoryPrompt = if (assistant.enableMemory) {
                buildMemoryPrompt(memories = memories)
            } else ""
            val recentChatsPrompt = if (assistant.enableRecentChatsReference) {
                buildRecentChatsPrompt(assistant, conversationRepo)
            } else ""
            val toolPrompts = tools.map { tool -> tool.systemPrompt(model, messages) }
            // Split into stable (assistant + tools) and volatile (memory + recent chats +
            // addendum) so prompt caching survives memory injection: the stable part is the
            // cached prefix, the volatile part sits after it. See SystemPromptBuilder.
            val (stableSystem, volatileSystem) = systemPromptBuilder.buildSections(
                assistantPrompt = effectiveSystemPrompt,
                memoryPrompt = memoryPrompt,
                recentChatsPrompt = recentChatsPrompt,
                toolPrompts = toolPrompts,
                systemAddendum = systemAddendum,
            )
            val systemParts = buildList {
                if (stableSystem.isNotBlank()) add(UIMessagePart.Text(stableSystem))
                if (volatileSystem.isNotBlank()) add(UIMessagePart.Text(volatileSystem))
            }
            if (systemParts.isNotEmpty()) {
                add(UIMessage(role = MessageRole.SYSTEM, parts = systemParts))
            }
            // Keeps this app's multi-part system assembly and tool-image ageing, on top of
            // the renamed field and its stepped truncation (which now preserves
            // prompt caching instead of trimming one message at a time).
            addAll(messages.limitContext(assistant.contextMessageLimit).ageOldToolImages())
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            frequencyPenalty = assistant.frequencyPenalty,
            presencePenalty = assistant.presencePenalty,
            maxTokens = assistant.maxTokens,
            maxStreamRetries = if (settings.enableAutoRetry) settings.responseStreamMaxRetries else 0,
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
            // Conversation-scoped sticky-routing key (OpenRouter `session_id`): keeps
            // every turn of one conversation pinned to the same provider so its prompt
            // cache stays warm. Utility generations (no conversation) get a random id.
            sessionId = (conversationId ?: Uuid.random()).toString(),
        )
        if (stream) {
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = messages,
                    providerSetting = provider,
                    stream = true
                )
            )
            var receivedMeaningfulOutput = false
            var receivedAnyChunk = false
            val streamChunkHandler = StreamChunkHandler(model)
            val repetitionDetector = OutputRepetitionDetector()
            val streamStartedAtMs = System.currentTimeMillis()
            // 流式分块累积：每个 delta 都做字符串拼接与全量投递，长回复下是 O(n²)；
            // 这里按窗口合并后**保序**应用，并在流终止（正常/异常/取消）时补齐最后一批，
            // 保证最终内容与逐块处理完全一致。
            val pendingStreamChunks = mutableListOf<StreamChunk>()
            var lastStreamApplyAtMs = 0L
            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = params
            ).onCompletion { cause ->
                // 流终止（正常/异常/取消）：先补齐合并窗口内未应用的分块，
                // 再走下面的传输失败判定，避免尾部内容丢失
                if (pendingStreamChunks.isNotEmpty()) {
                    pendingStreamChunks.forEach { messages = streamChunkHandler.handle(messages, it) }
                    pendingStreamChunks.clear()
                    onUpdateMessages(messages)
                }
                // Some SSE implementations report an abruptly closed socket through onClosed
                // without an exception. Treat a clean close with no chunks at all as a transport
                // failure so the retry policy can recover a background continuation. A clean
                // close after chunks arrived but none produced parseable parts is a permanent
                // condition (unrecognized part shapes), not a transport hiccup, so it must not
                // burn retries - log it and let the generation end normally with an empty reply.
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
            }.retryWhen { cause, retryAttempt ->
                val shouldRetry = shouldRetryGenerationStreamFailure(
                    failure = cause,
                    retryAttempt = retryAttempt,
                    maxRetries = params.maxStreamRetries,
                    receivedMeaningfulOutput = receivedMeaningfulOutput,
                )
                if (shouldRetry) {
                    // A new attempt is about to start collecting from scratch: reset the
                    // per-attempt "did anything arrive" flag so onCompletion's transport-failure
                    // check reflects this attempt, not a chunk seen in an earlier one.
                    receivedAnyChunk = false
                    val delayMs = generationStreamRetryDelayMs(retryAttempt)
                    processingStatus.value = retryStatusText(
                        context = context,
                        retryNumber = retryAttempt + 1,
                        maxRetries = params.maxStreamRetries,
                        failure = cause,
                    )
                    AppLog.w(
                        TAG,
                        "streamText: retrying after failure " +
                            "(${retryAttempt + 1}/${params.maxStreamRetries}) in ${delayMs}ms",
                        cause,
                    )
                    delay(delayMs)
                }
                shouldRetry
            }.takeWhile { chunk ->
                // Wall-clock ceiling: a stream that never ends (endless "thinking") can only be
                // caught here — repetition detection is blind to it, because the stream keeps
                // producing fresh-looking text the whole time.
                if (System.currentTimeMillis() - streamStartedAtMs > MAX_STREAM_DURATION_MS) {
                    AppLog.w(TAG, "streamText: stream exceeded max duration; ending this turn's stream early")
                    return@takeWhile false
                }
                // Generation-side guard: stop early once the model starts repeating itself.
                // Ending the flow normally (instead of throwing) keeps this out of the retry
                // policy — a repetition loop is not a transport failure and must not be replayed.
                val deltaText =
                    when (chunk) {
                        is StreamChunk.TextDelta -> chunk.text
                        is StreamChunk.ReasoningDelta -> chunk.text
                        else -> null
                    }
                if (deltaText != null && repetitionDetector.feed(deltaText)) {
                    AppLog.w(TAG, "streamText: runaway repetition detected; ending this turn's stream early")
                    false
                } else {
                    true
                }
            }.collect { chunk ->
                receivedAnyChunk = true
                if (isMeaningfulStreamChunk(chunk)) {
                    receivedMeaningfulOutput = true
                    clearRetryStatus(processingStatus)
                }
                pendingStreamChunks += chunk
                val nowApplyMs = android.os.SystemClock.elapsedRealtime()
                if (nowApplyMs - lastStreamApplyAtMs >= renderProfile.streamApplyIntervalMs) {
                    lastStreamApplyAtMs = nowApplyMs
                    pendingStreamChunks.forEach { messages = streamChunkHandler.handle(messages, it) }
                    pendingStreamChunks.clear()
                    onUpdateMessages(messages)
                }
            }
            // 正常收尾：补齐最后一批（异常/取消路径由 onCompletion 兜底）
            if (pendingStreamChunks.isNotEmpty()) {
                pendingStreamChunks.forEach { messages = streamChunkHandler.handle(messages, it) }
                pendingStreamChunks.clear()
                onUpdateMessages(messages)
            }
        } else {
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = messages,
                    providerSetting = provider,
                    stream = false
                )
            )
            val result = retryGenerationTransportRequest(
                maxRetries = params.maxStreamRetries,
                onRetry = { retryNumber, failure ->
                    onRetryDiagnosed?.invoke(diagnoseFailure(context, failure))
                    processingStatus.value = retryStatusText(
                        context = context,
                        retryNumber = retryNumber,
                        maxRetries = params.maxStreamRetries,
                        failure = failure,
                    )
                },
            ) {
                providerImpl.generateText(
                    providerSetting = provider,
                    messages = internalMessages,
                    params = params.copy(model = (resolveBackendProvider(settings.executionBackend, model, settings.providers)?.second ?: model)),
                )
            }
            messages = messages.handleTextGenerationResult(result = result, model = (resolveBackendProvider(settings.executionBackend, model, settings.providers)?.second ?: model))
            onUpdateMessages(messages)
        }
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        AppLog.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

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
    private suspend fun maskToolOutput(parts: List<UIMessagePart>): List<UIMessagePart> {
        val vaultRepo = runCatching { getKoin().get<CredentialVaultRepository>() }.getOrNull()
            ?: return parts
        try {
            SecretMasker.refresh(vaultRepo)
        } catch (_: Exception) {
            // 掩码失败不阻断工具结果（安全兜底降级为原样输出）
        }
        return parts.map { part ->
            if (part is UIMessagePart.Text) {
                UIMessagePart.Text(
                    text = SecretMasker.mask(part.text),
                    metadata = part.metadata, // 保留 metadata（如 DiffMetadata 存 diff 供 UI 渲染红绿）
                )
            } else {
                part
            }
        }
    }
}
