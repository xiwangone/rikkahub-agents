package me.rerere.rikkahub.ui.components.message.tools.generic

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock

/**
 * Termux 会话类工具渲染器：termux_session_start / termux_session_send /
 * termux_session_read / termux_session_list / termux_session_kill / termux_run_command
 *
 * 会话有状态，输出含 session_id。标题显示会话 ID，摘要显示前若干行输出。
 */
object TermuxToolUI : ToolUIRenderer {
    private const val SUMMARY_MAX_LINES = 6

    override val toolName: String = "termux_session_start"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ComputerTerminal01

    @Composable
    override fun title(context: ToolUIContext): String {
        val sessionId =
            context.content?.getStringContent("session_id")
                ?: context.arguments.getStringContent("session_id")
        return when (context.tool.toolName) {
            "termux_session_start" -> {
                if (sessionId != null) {
                    stringResource(R.string.tool_ui_termux_start_with_id, sessionId)
                } else {
                    stringResource(R.string.tool_ui_termux_start)
                }
            }
            "termux_session_send" -> {
                if (sessionId != null) {
                    stringResource(R.string.tool_ui_termux_send_with_id, sessionId)
                } else {
                    stringResource(R.string.tool_ui_termux_send)
                }
            }
            "termux_session_read" -> {
                if (sessionId != null) {
                    stringResource(R.string.tool_ui_termux_read_with_id, sessionId)
                } else {
                    stringResource(R.string.tool_ui_termux_read)
                }
            }
            "termux_session_list" ->
                stringResource(R.string.tool_ui_termux_list)
            "termux_session_kill" -> {
                if (sessionId != null) {
                    stringResource(R.string.tool_ui_termux_kill_with_id, sessionId)
                } else {
                    stringResource(R.string.tool_ui_termux_kill)
                }
            }
            "termux_run_command" -> {
                val pid = context.content?.getStringContent("pid")
                if (pid != null) {
                    stringResource(R.string.tool_ui_termux_run_with_pid, pid)
                } else {
                    stringResource(R.string.tool_ui_termux_run)
                }
            }
            else -> stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        summaryText(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val text = summaryText(context) ?: return
        HighlightCodeBlock(
            code = text,
            language = "text",
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private fun summaryText(context: ToolUIContext): String? {
        val content = context.content ?: return null
        // 会话 read / run_command 的输出文本
        val output =
            content.getStringContent("output")
                ?: content.getStringContent("screen")
                ?: content.getStringContent("stdout")
                ?: return null
        return output.lineSequence()
            .take(SUMMARY_MAX_LINES)
            .joinToString("\n")
            .ifBlank { null }
    }
}

/** TermuxToolUI 的多 key 别名：同一实例挂多个工具名 */
private fun termuxAlias(toolName: String): ToolUIRenderer =
    object : ToolUIRenderer by TermuxToolUI {
        override val toolName: String = toolName
    }

val TermuxSessionSendToolUI: ToolUIRenderer = termuxAlias("termux_session_send")
val TermuxSessionReadToolUI: ToolUIRenderer = termuxAlias("termux_session_read")
val TermuxSessionListToolUI: ToolUIRenderer = termuxAlias("termux_session_list")
val TermuxSessionKillToolUI: ToolUIRenderer = termuxAlias("termux_session_kill")
val TermuxRunCommandToolUI: ToolUIRenderer = termuxAlias("termux_run_command")
