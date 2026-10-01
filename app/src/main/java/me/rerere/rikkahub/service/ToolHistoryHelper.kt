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

    /** 提取消息中的工具执行历史（调用+结果），作为标记块附加到压缩摘要，避免压缩后 AI 重复调用已完成工具。 */
    fun toolHistoryBlock(messages: List<UIMessage>): String {
        val records =
            buildList {
                messages.forEach { msg ->
                    msg.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Tool -> {
                                val outputPreview =
                                    part.output
                                        .joinToString(" ") { p -> (p as? UIMessagePart.Text)?.text?.take(500).orEmpty() }
                                        .take(500)
                                add("Tool ${part.toolName}: in=${part.input.take(200)} out=$outputPreview")
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
