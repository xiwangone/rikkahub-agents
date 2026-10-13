package me.rerere.rikkahub.data.ai

import androidx.core.net.toFile
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File

data class ContextBudgetPlan(
    val estimatedInputTokens: Int,
    val triggerTokens: Int,
    val shouldCompact: Boolean,
)

/**
 * Conservative provider-independent context estimator.
 *
 * The most recent real usage is preferred because it includes provider-specific system and
 * tool-schema overhead. Messages added after that response are estimated locally. Providers
 * that do not report usage fall back to estimating the complete message list.
 */
object ContextBudgetPlanner {
    private const val CHARS_PER_TOKEN = 3
    private const val MESSAGE_OVERHEAD_TOKENS = 8
    private const val MEDIA_PART_TOKENS = 1_024

    /**
     * 附件正文「最多可能被内联多少字符」的兜底默认值，与
     * `DisplaySetting.attachmentInlineThreshold` 的默认值一致。
     * 调用方（ChatVM / ChatService）应把设置项的实际值传进来。
     */
    const val DEFAULT_DOCUMENT_INLINE_CAP_CHARS = 8_000

    fun plan(
        messages: List<UIMessage>,
        contextLength: Int,
        thresholdPercent: Int,
        thresholdTokensK: Int? = null,
        reservedTokens: Int = 0,
        documentInlineCapChars: Int = DEFAULT_DOCUMENT_INLINE_CAP_CHARS,
    ): ContextBudgetPlan {
        require(contextLength > 0) { "contextLength must be positive" }
        val normalizedThreshold = thresholdPercent.coerceIn(5, 95)
        val safeInputBudget = (contextLength.toLong() - reservedTokens.coerceAtLeast(0).toLong())
            .coerceAtLeast(1L)
        val configuredTriggerTokens = thresholdTokensK?.let { tokensK ->
            (tokensK.toLong().coerceAtLeast(1L) * 1_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        } ?: (contextLength.toLong() * normalizedThreshold / 100L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val triggerTokens = minOf(configuredTriggerTokens.toLong(), safeInputBudget)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val estimatedInputTokens = estimateInputTokens(messages, documentInlineCapChars)
        return ContextBudgetPlan(
            estimatedInputTokens = estimatedInputTokens,
            triggerTokens = triggerTokens,
            shouldCompact = estimatedInputTokens >= triggerTokens,
        )
    }

    fun estimateInputTokens(
        messages: List<UIMessage>,
        documentInlineCapChars: Int = DEFAULT_DOCUMENT_INLINE_CAP_CHARS,
    ): Int {
        val usageIndex = messages.indexOfLast { message ->
            val usage = message.usage
            usage != null && (usage.totalTokens > 0 || usage.promptTokens + usage.completionTokens > 0)
        }
        val estimate = if (usageIndex >= 0) {
            val usage = messages[usageIndex].usage!!
            val reportedTotal = usage.totalTokens.takeIf { it > 0 }
                ?: (usage.promptTokens + usage.completionTokens)
            // Tool outputs are attached to the assistant message only after the provider has
            // reported usage for its tool-call response. They therefore are not represented in
            // that usage figure, even though the next model request includes them.
            reportedTotal.toLong() +
                estimatePostUsageToolOutputTokens(messages[usageIndex], documentInlineCapChars) +
                messages.drop(usageIndex + 1).sumOf { estimateMessageTokens(it, documentInlineCapChars) }
        } else {
            messages.sumOf { estimateMessageTokens(it, documentInlineCapChars) }
        }
        return estimate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Estimates the context represented by the supplied messages without using provider usage.
     * This is required after compaction because reported usage belongs to the pre-compaction
     * request and cannot describe the synthetic summary that replaced it.
     */
    fun estimateContextTokens(
        messages: List<UIMessage>,
        documentInlineCapChars: Int = DEFAULT_DOCUMENT_INLINE_CAP_CHARS,
    ): Int = messages
        .sumOf { estimateMessageTokens(it, documentInlineCapChars) }
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    fun estimateMessageTokens(
        message: UIMessage,
        documentInlineCapChars: Int = DEFAULT_DOCUMENT_INLINE_CAP_CHARS,
    ): Long {
        val contentTokens = message.parts.sumOf { estimatePartTokens(it, documentInlineCapChars) }
        return MESSAGE_OVERHEAD_TOKENS + contentTokens
    }

    /**
     * Estimates only the execution results that were appended after provider usage was emitted.
     * Tool-call names and arguments already belong to the assistant completion represented by the
     * reported usage, so counting them here would double-count that response.
     */
    @Suppress("DEPRECATION")
    private fun estimatePostUsageToolOutputTokens(
        message: UIMessage,
        documentInlineCapChars: Int,
    ): Long = message.parts.sumOf { part ->
        when (part) {
            is UIMessagePart.Tool -> part.output.sumOf { estimatePartTokens(it, documentInlineCapChars) }
            is UIMessagePart.ToolResult -> estimateTextTokens(part.content.toString())
            else -> 0L
        }
    }

    /** Selects a recent tail whose estimated size fits [targetTokens]. */
    fun chooseTailStartIndex(
        messages: List<UIMessage>,
        targetTokens: Int,
        minimumRecentMessages: Int = 4,
    ): Int {
        if (messages.size <= 1) return 0

        val minimumStart = (messages.size - minimumRecentMessages.coerceAtLeast(1))
            .coerceAtLeast(0)
        var start = messages.lastIndex
        var used = 0L
        while (start >= 0) {
            val next = estimateMessageTokens(messages[start])
            if (start < minimumStart && used + next > targetTokens) break
            used += next
            start--
        }
        return (start + 1).coerceIn(1, messages.lastIndex)
    }

    @Suppress("DEPRECATION")
    private fun estimatePartTokens(
        part: UIMessagePart,
        documentInlineCapChars: Int,
    ): Long = when (part) {
        is UIMessagePart.Text -> estimateTextTokens(part.text)
        is UIMessagePart.Reasoning -> estimateTextTokens(part.reasoning)
        is UIMessagePart.Tool -> estimateTextTokens(part.toolName) +
            estimateTextTokens(part.input) + part.output.sumOf { estimatePartTokens(it, documentInlineCapChars) }
        is UIMessagePart.ToolCall -> estimateTextTokens(part.toolName) + estimateTextTokens(part.arguments)
        is UIMessagePart.ServerTool -> estimateTextTokens(part.toolName) +
            (part.input?.toString()?.let(::estimateTextTokens) ?: 0) +
            (part.output?.toString()?.let(::estimateTextTokens) ?: 0)
        is UIMessagePart.ToolResult -> estimateTextTokens(part.toolName) +
            estimateTextTokens(part.arguments.toString()) + estimateTextTokens(part.content.toString())
        // 附件正文由 DocumentAsPromptTransformer 在请求装配时内联进提示词（超阈值时改为
        // 引用式），预算必须把正文算进去：旧口径只算 MEDIA_PART_TOKENS + 文件名，
        // 附件越大估得越偏（等于没估）。近似口径见 estimateDocumentBodyTokens。
        is UIMessagePart.Document -> MEDIA_PART_TOKENS + estimateTextTokens(part.fileName) +
            estimateDocumentBodyTokens(part, documentInlineCapChars)
        is UIMessagePart.Image,
        is UIMessagePart.Video,
        is UIMessagePart.Audio,
        UIMessagePart.Search,
            -> MEDIA_PART_TOKENS.toLong()
    }

    /**
     * 附件**正文**的近似 token 数（近似口径，非精确分词）。
     *
     * 时序依据：planner 的调用方（ChatVM 自动压缩检测、ChatService 压缩分块）跑在**库里的
     * 原始消息**上，而 transformer 只在请求装配点展开消息、结果不落库 —— planner 必然先于
     * transformer，拿不到解析后的正文，只能按「将要实际发送的量」估（任务书方向②）：
     *  - 只 `File.length()`（一次 stat，不把文件读进内存）取字节数：UTF-8 下 ASCII 3 字符/token、
     *    CJK 3 字节/字符且 1 token/字符，两端都落到 **字节数 / 3 ≈ token 数**；
     *  - 用 [documentInlineCapChars]（= 设置项内联阈值）封顶：transformer 最多内联这么多字符，
     *    每字符最多 1 token，故该值即内联正文的 token 上界 —— 估算有界，不随文件大小膨胀；
     *  - pdf/docx/pptx/epub 的容器字节数大于解析出的文本字节数（zip 压缩/二进制结构），
     *    此处按偏高方向近似：保守侧只会提前触发压缩，不会低估到超窗。
     *
     * 已知偏差：无文件工具的会话里 transformer 退化为整篇内联，此时超大附件的实际发送量
     * 会超过 min(字节/3, 阈值) 的估计；这是任务书认可的 min(文件字符数, 阈值) 口径，
     * 属保守下限而非精确值，已写进交付说明。
     */
    internal fun estimateDocumentBodyTokens(
        part: UIMessagePart.Document,
        documentInlineCapChars: Int,
    ): Long {
        val file = documentFileOf(part.url) ?: return 0L
        if (!file.isFile) return 0L
        val bytes = runCatching { file.length() }.getOrDefault(0L)
        if (bytes <= 0L) return 0L
        val bodyTokens = (bytes + CHARS_PER_TOKEN - 1L) / CHARS_PER_TOKEN
        return bodyTokens.coerceAtMost(documentInlineCapChars.coerceAtLeast(0).toLong())
    }

    /**
     * url -> 本地文件。与 [me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer]
     * 的解析保持一致：先走 `android.net.Uri`（生产环境，正确处理 %XX 百分号编码）；
     * JVM 单测里 Uri 是未打桩的 Android 存根（parse 返回 null 或抛错，被 runCatching 吞掉），
     * 此时退回纯 JVM 的 `file://` 前缀解析，使同一函数在单测中可用真实临时文件驱动。
     * 非 `file://`（content://、http、解析失败）一律 null，不参与估算。
     */
    private fun documentFileOf(url: String): File? {
        // 先校 isFile：旧写法把"能构造但文件不存在"的 File 无条件返回，使下面兼容 JVM 单测
        // （android.net.Uri 为未打桩存根）的 file:// 兜底分支永远走不到，正文估算恒为 0。
        runCatching { url.toUri().toFile() }.getOrNull()?.takeIf { it.isFile }?.let { return it }
        if (!url.startsWith("file://")) return null
        val path = url.removePrefix("file://")
        return if (path.startsWith("/")) File(path).takeIf { it.isFile } else null
    }

    private fun estimateTextTokens(text: String): Long {
        var asciiChars = 0L
        var nonAsciiChars = 0L
        text.forEach { char ->
            if (char.code <= 0x7F) asciiChars++ else nonAsciiChars++
        }
        return nonAsciiChars + (asciiChars + CHARS_PER_TOKEN - 1L) / CHARS_PER_TOKEN
    }
}
