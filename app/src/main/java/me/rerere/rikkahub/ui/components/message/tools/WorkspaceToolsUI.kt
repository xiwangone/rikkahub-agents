package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.R

/**
 * 工作区类工具的调用展示。
 *
 * 只覆写「折叠标题 + 展开摘要」，详情沿用 [ToolUIRenderer] 的默认 JSON 预览——这两个工具
 * 此前没有渲染器（半成品自查一直报「工具无自定义 UI」），用户只能看到一串原始 JSON，
 * 跨文件事务编辑尤其看不出「到底改了哪几个文件」。
 */

/** `workspace_apply_edits`：折叠标题给「N 个文件 / M 处编辑」，展开列出每个文件。 */
internal object WorkspaceApplyEditsToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_apply_edits"

    /** 入参里每个文件的（路径, 编辑条数）；入参不可解析时为空列表。 */
    private fun fileEntries(context: ToolUIContext): List<Pair<String, Int>> =
        ((context.arguments as? JsonObject)?.get("files") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.map { obj ->
                val path = (obj["path"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val edits = (obj["edits"] as? JsonArray)?.size ?: 0
                path to edits
            }
            .orEmpty()

    @Composable
    override fun title(context: ToolUIContext): String {
        val entries = fileEntries(context)
        return stringResource(
            R.string.chat_tool_workspace_edit_title,
            entries.size,
            entries.sumOf { it.second },
        )
    }

    override fun hasSummary(context: ToolUIContext): Boolean = fileEntries(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        Column {
            fileEntries(context).fastForEach { (path, edits) ->
                Text(
                    text = stringResource(R.string.chat_tool_workspace_edit_line, path, edits),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** `workspace_search_code`：折叠标题给匹配条数，展开列出命中的文件路径。 */
internal object WorkspaceSearchCodeToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_search_code"

    /** 返回 (total 匹配数, 去重后的文件列表)。结构与工具实现一致：{matches:[{file,line,text}], total, …}。 */
    private fun matchedFiles(context: ToolUIContext): Pair<Int, List<String>> {
        val content = context.content as? JsonObject ?: return 0 to emptyList()
        val total = (content["total"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val files = (content["matches"] as? JsonArray)
            ?.mapNotNull { ((it as? JsonObject)?.get("file") as? JsonPrimitive)?.contentOrNull }
            ?.distinct()
            .orEmpty()
        return total to files
    }

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_tool_workspace_search_title, matchedFiles(context).first)

    override fun hasSummary(context: ToolUIContext): Boolean =
        matchedFiles(context).second.isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        Column {
            matchedFiles(context).second.fastForEach { path ->
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
