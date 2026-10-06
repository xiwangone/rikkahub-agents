package me.rerere.rikkahub.ui.components.message.tools.generic

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.JsonElement
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock

/**
 * SSH / Shizuku 终端执行类工具渲染器：ssh_exec / shizuku_exec / ssh_exec_saved
 *
 * 输出 `{success, exit_code, stdout, stderr}`。默认标题"调用工具 ssh_exec"
 * 看不出跑了什么命令——标题直接显示命令（截 60 字），摘要保持终端输出
 * 前 8 行（非零退出码带 `[exit N]` 标记），与默认分支行为一致。
 */
object SshExecToolUI : ToolUIRenderer {
    override val toolName: String = "ssh_exec"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ComputerTerminal01

    @Composable
    override fun title(context: ToolUIContext): String {
        val command = context.arguments.getStringContent("command")?.trim()?.take(60)
        return command?.ifBlank { null }
            ?: stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        terminalSummary(context.content) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val text = terminalSummary(context.content) ?: return
        HighlightCodeBlock(
            code = text,
            language = "text",
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private fun terminalSummary(content: JsonElement?): String? {
        content ?: return null
        val stdout = content.getStringContent("stdout")
        val exit = content.getStringContent("exit_code") ?: content.getStringContent("exitCode")
        if (stdout == null && exit == null) return null
        val body = (stdout ?: "").lineSequence().take(8).joinToString("\n")
        if (exit != null && exit != "0") return "[exit $exit]\n$body"
        return body.ifBlank { null }
    }
}

/** SshExecToolUI 的多 key 别名：同一实例挂多个工具名 */
private fun sshExecAlias(toolName: String): ToolUIRenderer =
    object : ToolUIRenderer by SshExecToolUI {
        override val toolName: String = toolName
    }

val ShizukuExecToolUI: ToolUIRenderer = sshExecAlias("shizuku_exec")
val SshExecSavedToolUI: ToolUIRenderer = sshExecAlias("ssh_exec_saved")
