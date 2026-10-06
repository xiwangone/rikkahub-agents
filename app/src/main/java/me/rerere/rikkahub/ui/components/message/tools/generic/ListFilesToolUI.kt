package me.rerere.rikkahub.ui.components.message.tools.generic

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * 文件列表类工具渲染器：list_files / find_files
 *
 * 列表摘要本身可用（文件名逐条列出），但标题"调用工具 list_files"
 * 看不出列的是哪个目录。标题直接带路径 / 查询词。
 */
object ListFilesToolUI : ToolUIRenderer {
    override val toolName: String = "list_files"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String {
        val args = context.arguments
        return when (context.tool.toolName) {
            "find_files" -> {
                val query = args.getStringContent("query")?.take(40)?.ifBlank { null } ?: "?"
                stringResource(R.string.tool_ui_findfiles_title, query)
            }
            else -> {
                val path = args.getStringContent("path")?.take(60)?.ifBlank { null } ?: "?"
                stringResource(R.string.tool_ui_listfiles_title, path)
            }
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        fileLines(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val lines = fileLines(context)
        if (lines.isEmpty()) return
        HighlightCodeBlock(
            code = lines.joinToString("\n"),
            language = "text",
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private fun fileLines(context: ToolUIContext): List<String> {
        val arr = context.content?.jsonObjectOrNull?.get("files")?.let {
            runCatching { it.jsonArray }.getOrNull()
        } ?: return emptyList()
        val lines = arr.take(8).mapNotNull { el ->
            el.jsonPrimitiveOrNull?.contentOrNull
                ?: el.jsonObjectOrNull?.let { obj ->
                    obj["name"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?: obj["path"]?.jsonPrimitiveOrNull?.contentOrNull
                }?.ifBlank { null }
        }
        return if (arr.size > 8) lines + "… (${arr.size})" else lines
    }
}

/** ListFilesToolUI 的多 key 别名：同一实例挂多个工具名 */
private fun listFilesAlias(toolName: String): ToolUIRenderer =
    object : ToolUIRenderer by ListFilesToolUI {
        override val toolName: String = toolName
    }

val FindFilesToolUI: ToolUIRenderer = listFilesAlias("find_files")
