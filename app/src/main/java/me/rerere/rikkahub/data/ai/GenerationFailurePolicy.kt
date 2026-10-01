package me.rerere.rikkahub.data.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.provider.providers.openai.ResponseStreamErrorException
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.rikkahub.data.log.AppLog

/**
 * 生成失败的诊断与重试策略。
 *
 * 从 [GenerationLoop] 抽出的纯函数簇：失败分类（[classifyFailureKind] / [diagnoseFailure]）、
 * 流式重试判定与退避（[shouldRetryGenerationStreamFailure] / [retryGenerationTransportRequest]）、
 * 图片能力降级（[shouldDowngradeImagesOnFailure] / [stripImagePartsForUnsupportedModel]）。
 * 除字符串资源外无 Android 框架依赖，便于单测。
 */

private const val TAG = "GenerationLoop"

internal const val GENERATION_STREAM_RETRY_INITIAL_DELAY_MS = 750L
internal const val GENERATION_STREAM_RETRY_MAX_DELAY_MS = 4_000L

internal val USER_CANCELLATION_MARKERS = listOf(
    "canceled by user",
    "cancelled by user",
    "user_canceled",
    "user_cancelled",
)

// A deterministic 4xx will not succeed on retry, so retrying it just burns quota and delay for
// an outcome that was never going to change. These four are the exceptions: they signal a
// transient condition (timeout, conflict, precondition, rate limit) rather than a request that
// is permanently invalid.
internal val RETRYABLE_4XX_STATUS_CODES = setOf(408, 409, 425, 429)

internal fun isCancellationFailure(failure: Throwable): Boolean =
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

/**
 * 图片能力不匹配时的失败驱动降级判据（纯函数，便于单测）。
 *
 * 场景：模型元数据声称支持图片、服务端实际拒绝（自建网关 / 聚合商 / 模型换版）。
 * 只认「失败分类 = IMAGE_UNSUPPORTED」；**是否含图、是否已降级过由调用方另行判断**。
 */
internal fun shouldDowngradeImagesOnFailure(failure: Throwable, rawError: String): Boolean =
    classifyFailureKind(failure, rawError) == FailureKind.IMAGE_UNSUPPORTED

/** 去图结果：新消息 + 被替换的图片 part 数。 */
internal data class ImageStripResult(val messages: List<UIMessage>, val replaced: Int)

/**
 * 与 provider 适配层同语义的占位文案：告诉模型「这里原本有图，但当前模型看不了」，
 * 而不是静默删掉（刻意不新增资源键，保持纯文本）。
 */
internal const val IMAGE_DOWNGRADE_PLACEHOLDER =
    "[image omitted] The current model does not support image input; the image was removed before retrying."

/**
 * 把消息里的图片 part 换成明确占位文本（供降级重试用）。
 */
internal fun stripImagePartsForUnsupportedModel(messages: List<UIMessage>): ImageStripResult {
    var replaced = 0
    val stripped =
        messages.map { message ->
            if (message.parts.none { part -> part is UIMessagePart.Image }) {
                message
            } else {
                message.copy(
                    parts =
                        message.parts.map { part ->
                            if (part is UIMessagePart.Image) {
                                replaced++
                                UIMessagePart.Text(IMAGE_DOWNGRADE_PLACEHOLDER)
                            } else {
                                part
                            }
                        },
                )
            }
        }
    return ImageStripResult(stripped, replaced)
}

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
    // 内容风控是确定性拒绝（同一份上下文重发，结论不变），重试只会白等一轮
    if (classifyFailureKind(failure, retryFailureReason(failure)) == FailureKind.CONTENT_SAFETY) {
        return false
    }
    // Retry provider, parsing, and local processing failures alike. Cancellation is kept
    // out of the retry loop so stop-generation and parent-scope cancellation propagate.
    return !isCancellationFailure(failure)
}

// A 4xx other than the RETRYABLE_4XX_STATUS_CODES exceptions is deterministic: the same
// request will fail the same way on every retry. 5xx and failures with no known status code
// (most providers don't attach one) keep the existing retry behaviour.
internal fun isNonRetryableClientError(failure: Throwable): Boolean {
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
internal val QUOTA_EXHAUSTED_MARKERS = listOf(
    "resource exhausted",
    "resource has been exhausted",
)

internal fun isQuotaExhaustedFailure(failure: Throwable): Boolean {
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

internal fun isContextLimitFailure(failure: Throwable): Boolean =
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

internal fun generationStreamRetryDelayMs(retryAttempt: Long): Long =
    ((retryAttempt + 1) * GENERATION_STREAM_RETRY_INITIAL_DELAY_MS)
        .coerceAtMost(GENERATION_STREAM_RETRY_MAX_DELAY_MS)

internal fun retryFailureReason(failure: Throwable): String =
    generateSequence(failure) { it.cause }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?.replace(Regex("\\s+"), " ")
        ?.take(240)
        ?: failure.javaClass.simpleName

/** 错误分类：根据失败原因与异常类型定位问题，命中→资源化标签，未命中→UNKNOWN（原文兜底）。 */
enum class FailureKind {
    /** 会话历史里的工具调用缺少对应结果（协议配对破损）。 */
    TOOL_PAIRING,

    /** 模型不支持图片输入，而历史里有图片内容。 */
    IMAGE_UNSUPPORTED,

    CONTENT_SAFETY, AUTH, QUOTA, RATE_LIMIT, MODEL_NOT_FOUND, NETWORK, SERVER, CONTEXT_LENGTH,
    PERMISSION, STORAGE, UNSUPPORTED, BAD_REQUEST, UNKNOWN,
}

// 服务端内容风控的机器可读码（各家措辞不同、code 稳定）：
// OpenAI / Azure OpenAI：content_policy_violation / content_filter / ResponsibleAIPolicyViolation
// 阿里百炼：DataInspectionFailed / data_inspection_failed
private val CONTENT_SAFETY_ERROR_CODES = setOf(
    "content_policy_violation",
    "content_filter",
    "responsibleaipolicyviolation",
    "data_inspection_failed",
    "datainspectionfailed",
)

// 用非标准状态码表示「内容拦截」的平台：小米 MiMo 官方错误码表把 421 定义为「内容拦截·内容审核拦截」
private const val CONTENT_BLOCKED_STATUS_CODE = 421

/**
 * 是否属于服务端内容风控拒绝：优先看机器可读码与专用状态码，其次看各家文案。
 *
 * 文案只收「有据可查」的那几句：DeepSeek `Content Exists Risk`，Anthropic
 * `Output blocked by content filtering policy`，OpenAI `…not allowed by our safety system`，
 * Azure OpenAI `…content management policy`。聚合/中转平台（opencode、TokenRhythm 等）
 * 多是原样透传服务端错误，所以不必逐平台枚举 —— 认服务端那句即可。
 * 拆成独立函数还有第二个作用：把 [classifyFailureKind] 的 when 分支数压住，不撞复杂度上限。
 */
private fun isContentSafetyFailure(
    text: String,
    providerErrorCodes: List<String>,
    statusCode: Int?,
): Boolean =
    statusCode == CONTENT_BLOCKED_STATUS_CODE ||
        providerErrorCodes.any { it in CONTENT_SAFETY_ERROR_CODES } ||
        listOf(
            "data_inspection_failed", "safetyerror", "content_filter", "inappropriate",
            "sensitive", "安全", "敏感",
            "content exists risk",
            "content filtering policy",
            "not allowed by our safety system",
            "content management policy",
        ).any { text.contains(it) }

// 并列判据的直译：十多类各自一组关键词，拆成数据表反而更难核对「哪类先判」；
// 故保留 when 写法并显式豁免复杂度（阈值 20，本处因新增「工具配对 / 图片不支持」两类刚到 20）。
@Suppress("CyclomaticComplexMethod")
fun classifyFailureKind(failure: Throwable, raw: String): FailureKind {
    val text = raw.lowercase() + " " + failure.javaClass.name.lowercase()
    // 服务端的 code/type 比文案可靠：同一类内容风控各家措辞不同，但机器可读码稳定
    val providerErrorCodes = generateSequence(failure) { it.cause }
        .take(8)
        .filterIsInstance<HttpException>()
        .flatMap { sequenceOf(it.providerErrorCode, it.providerErrorType) }
        .filterNotNull()
        .map { it.lowercase() }
        .toList()
    // 状态码也是判据：有平台用非标准码表达固定语义（如小米 MiMo 用 421 表示内容拦截）
    val statusCode = generateSequence(failure) { it.cause }
        .take(8)
        .filterIsInstance<HttpException>()
        .firstOrNull()
        ?.statusCode
    return when {
        // 先判「结构 / 能力」两类：它们最具体，且都要求同时命中对象词与原因词，避免被泛规则截胡
        (text.contains("tool_calls") || text.contains("tool_call_id")) &&
            listOf("tool messages", "insufficient tool", "must be followed by").any { text.contains(it) } ->
            FailureKind.TOOL_PAIRING
        (text.contains("image") || text.contains("vision")) &&
            listOf("not support", "unsupported", "does not support").any { text.contains(it) } &&
            // 排除「图片本身 / 请求体的问题」：这类报错同样含 image+unsupported 字样，但模型能看懂图片，
            // 误判会触发剥图重试（只重试一次）→ 用户看到的是「图没了」。只保留指向「模型能力」的情形。
            listOf(
                "format", "mime", "content type", "content_type", "file type", "decode",
                "too large", "size", "dimension", "resolution", "pixel",
                "base64", "url", "context", "token", "too many", "payload",
            ).none { text.contains(it) } ->
            FailureKind.IMAGE_UNSUPPORTED

        isContentSafetyFailure(text, providerErrorCodes, statusCode) -> FailureKind.CONTENT_SAFETY
        listOf("401", "invalid_api_key", "authentication", "unauthorized", "api key", "密钥", "鉴权").any { text.contains(it) } ->
            FailureKind.AUTH
        listOf("402", "insufficient_quota", "insufficientbalance", "credits", "余额", "额度", "quota", "billing").any { text.contains(it) } ->
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
        FailureKind.TOOL_PAIRING -> context.getString(me.rerere.rikkahub.R.string.error_kind_tool_pairing)
        FailureKind.IMAGE_UNSUPPORTED -> context.getString(me.rerere.rikkahub.R.string.error_kind_image_unsupported)
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

internal fun retryStatusText(
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

internal fun clearRetryStatus(processingStatus: MutableStateFlow<String?>) {
    processingStatus.value = null
}

// Marks the retry loop's "meaningful output already arrived" flag. Only chunks that carry
// actual model output (text/reasoning/tool/image content, or annotations) count - the bare
// Start/End markers and Usage/Finish bookkeeping chunks don't, mirroring the old
// choice.delta/message.parts.isNotEmpty() check against the pre-refactor chunk shape.
internal fun isMeaningfulStreamChunk(chunk: StreamChunk): Boolean = when (chunk) {
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

@Suppress("TooGenericExceptionCaught")   // 有意 catch Throwable：provider/解析/本地处理失败都要重试；取消已由 shouldRetryGenerationStreamFailure 显式排除（isCancellationFailure）
internal suspend fun <T> retryGenerationTransportRequest(
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
