package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.log.AppLog

private const val TAG = "ChatService"

/**
 * 工具历史处理：历史消息中工具输出的截断与工具执行历史摘要。
 *
 * 压缩与落盘前对超长工具输出做截断，避免历史无限膨胀；
 * 压缩时提取工具执行历史，防止压缩后模型重复调用已完成工具。
 */
internal object ToolHistoryHelper {

    fun truncateKeptToolOutput(
        message: UIMessage,
        maxChars: Int,
        compactTools: List<String> = emptyList(),
        compactMaxChars: Int = maxChars,
    ): UIMessage {
        if (maxChars <= 0) return message
        return message.copy(
            parts =
                message.parts.map { part ->
                    if (part is UIMessagePart.Tool) {
                        // 命中助手「紧凑输出」名单的工具用更小阈值（与生成期口径一致）
                        val limit = if (part.toolName in compactTools) compactMaxChars else maxChars
                        val trimmedInput =
                            if (part.input.length > limit) {
                                AppLog.d(TAG, "B5 截断工具入参: tool=${part.toolName} ${part.input.length}→$limit chars")
                                part.input.take(limit) + "\n…[truncated]"
                            } else {
                                part.input
                            }
                        val textParts = part.output.filterIsInstance<UIMessagePart.Text>()
                        val totalLen = textParts.sumOf { it.text.length }
                        if (totalLen > limit) {
                            AppLog.d(TAG, "T12 截断工具输出: tool=${part.toolName} $totalLen→$limit chars")
                            var remaining = limit
                            val truncated =
                                part.output.mapNotNull { p ->
                                    val t = (p as? UIMessagePart.Text)?.text
                                    if (t == null) {
                                        p
                                    } else if (remaining > 0) {
                                        val take = minOf(t.length, remaining)
                                        remaining -= take
                                        if (take == t.length) UIMessagePart.Text(t) else UIMessagePart.Text(t.take(take) + "\n…[truncated]")
                                    } else {
                                        null
                                    }
                                }
                            part.copy(input = trimmedInput, output = truncated)
                        } else if (trimmedInput !== part.input) {
                            part.copy(input = trimmedInput)
                        } else {
                            part
                        }
                    } else {
                        part
                    }
                },
        )
    }

    // 工具输出预览的保留长度：开头 + 结尾，总长不高于原来的 500 字符上限。
    private const val OUTPUT_HEAD_CHARS = 200
    private const val OUTPUT_TAIL_CHARS = 300
    private const val OUTPUT_TRUNCATION_MARK = "…[中间已省略]…"

    /**
     * 工具输出预览：保留**开头**与**结尾**，中间用省略标记替代。
     *
     * 执行结果的状态行（成功/失败、写出的路径、返回的 ID）通常出现在输出尾部，
     * 纯头部截断会把它们整段砍掉，导致压缩后的摘要丢失“这次调用到底成没成功”这一关键信息。
     * 逐段取头尾、不先拼接全文：单次工具输出可达数百 KB，整段复制会造成不必要的内存峰值。
     */
    private fun previewToolOutput(output: List<UIMessagePart>): String {
        val texts = output.mapNotNull { (it as? UIMessagePart.Text)?.text }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) return ""
        val total = texts.sumOf { it.length }
        if (total <= OUTPUT_HEAD_CHARS + OUTPUT_TAIL_CHARS) return texts.joinToString(" ")

        val head = StringBuilder(OUTPUT_HEAD_CHARS)
        for (text in texts) {
            val room = OUTPUT_HEAD_CHARS - head.length
            if (room <= 0) break
            head.append(text, 0, minOf(text.length, room))
        }

        val tailChunks = mutableListOf<String>()
        var tailTaken = 0
        for (text in texts.asReversed()) {
            val room = OUTPUT_TAIL_CHARS - tailTaken
            if (room <= 0) break
            val take = minOf(text.length, room)
            tailChunks.add(0, text.substring(text.length - take))
            tailTaken += take
        }
        return head.toString() + OUTPUT_TRUNCATION_MARK + tailChunks.joinToString(" ")
    }

    /** 提取消息中的工具执行历史（调用+结果），作为标记块附加到压缩摘要，避免压缩后 AI 重复调用已完成工具。 */
    fun toolHistoryBlock(messages: List<UIMessage>): String {
        val records =
            buildList {
                messages.forEach { msg ->
                    msg.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Tool -> {
                                add("Tool ${part.toolName}: in=${part.input.take(200)} out=${previewToolOutput(part.output)}")
                            }
                            is UIMessagePart.ToolResult -> {
                                add("ToolResult ${part.toolName}: ${part.content.toString().take(500)}")
                            }
                            else -> Unit
                        }
                    }
                }
            }
        if (records.isEmpty()) return ""
        return "\n\n[Tool execution history — retained context]\n" + records.joinToString("\n") + "\n[End tool execution history]"
    }
}
