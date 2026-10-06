package me.rerere.rikkahub.ui.components.message.tools.generic

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileView
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock

/**
 * 本地文件读取工具渲染器：read_file
 *
 * （注意：已有的 ReadFileToolUI 注册的是 `workspace_read_file`，不是这个。）
 * 输出 `{content, truncated, bytes_read}`，正文摘要可用（前 8 行），
 * 但标题"调用工具 read_file"看不出读的是哪个文件——标题直接带路径。
 */
object LocalReadFileToolUI : ToolUIRenderer {
    override val toolName: String = "read_file"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileView

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")?.take(80)?.ifBlank { null } ?: "?"
        return stringResource(R.string.tool_ui_read_local_file_title, path)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        textPreview(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val text = textPreview(context) ?: return
        HighlightCodeBlock(
            code = text,
            language = "text",
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private fun textPreview(context: ToolUIContext): String? {
        val text = context.content?.getStringContent("content")?.ifBlank { null } ?: return null
        return text.lineSequence().take(8).joinToString("\n")
    }
}
