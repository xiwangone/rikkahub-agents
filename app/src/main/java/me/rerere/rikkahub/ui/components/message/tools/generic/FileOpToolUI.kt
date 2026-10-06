package me.rerere.rikkahub.ui.components.message.tools.generic

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock

/**
 * 文件操作类工具渲染器：copy_file / move_file / delete_file / create_directory /
 * batch_copy / batch_move / batch_delete / zip_files / unzip_file / download_file /
 * open_file / write_binary_file（+ ssh_download / ssh_upload 复用）
 *
 * 输出都是 `{success, path/from/to, ...}`。标题直接显示路径，
 * 批量操作显示成功/失败计数。
 */
object FileOpToolUI : ToolUIRenderer {
    override val toolName: String = "copy_file"

    override fun icon(context: ToolUIContext): ImageVector =
        when (context.tool.toolName) {
            "delete_file", "batch_delete" -> HugeIcons.Delete01
            "move_file", "batch_move" -> HugeIcons.FileEdit
            else -> HugeIcons.FileAdd
        }

    @Composable
    override fun title(context: ToolUIContext): String {
        val args = context.arguments
        return when (context.tool.toolName) {
            "copy_file", "move_file", "ssh_download", "ssh_upload" -> {
                val from = args.getStringContent("from") ?: args.getStringContent("remote_path") ?: "?"
                val to = args.getStringContent("to") ?: args.getStringContent("local_path") ?: "?"
                val resId = if (context.tool.toolName.startsWith("move")) {
                    R.string.tool_ui_fileop_move
                } else {
                    R.string.tool_ui_fileop_copy
                }
                stringResource(resId, from, to)
            }
            "delete_file" -> {
                val path = args.getStringContent("path") ?: "?"
                stringResource(R.string.tool_ui_fileop_delete, path)
            }
            "create_directory" -> {
                val path = args.getStringContent("path") ?: "?"
                stringResource(R.string.tool_ui_fileop_mkdir, path)
            }
            "batch_copy", "batch_move", "batch_delete" -> {
                val success = context.content?.getStringContent("success") ?: "?"
                stringResource(R.string.tool_ui_fileop_batch, success)
            }
            "zip_files" -> {
                val count = context.content?.getStringContent("entry_count") ?: "?"
                stringResource(R.string.tool_ui_fileop_zip, count)
            }
            "unzip_file" -> {
                val count = context.content?.getStringContent("entries_extracted") ?: "?"
                stringResource(R.string.tool_ui_fileop_unzip, count)
            }
            "download_file" -> {
                val id = context.content?.getStringContent("download_id")
                    ?: args.getStringContent("url") ?: "?"
                stringResource(R.string.tool_ui_fileop_download, id)
            }
            "open_file" -> {
                val path = args.getStringContent("path") ?: "?"
                stringResource(R.string.tool_ui_fileop_open, path)
            }
            "write_binary_file" -> {
                val path = args.getStringContent("path") ?: "?"
                stringResource(R.string.tool_ui_fileop_write_bin, path)
            }
            else -> stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean {
        // 批量操作的失败列表值得展示
        val failed = context.content?.getStringContent("failed")
        return !failed.isNullOrBlank() && failed != "[]" && failed != "0"
    }

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        val failedList = content.getStringContent("failed") ?: return
        if (failedList.isBlank() || failedList == "[]" || failedList == "0") return
        HighlightCodeBlock(
            code = failedList,
            language = "text",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** FileOpToolUI 的多 key 别名：同一实例挂多个工具名 */
private fun fileOpAlias(toolName: String): ToolUIRenderer =
    object : ToolUIRenderer by FileOpToolUI {
        override val toolName: String = toolName
    }

val MoveFileToolUI: ToolUIRenderer = fileOpAlias("move_file")
val DeleteFileToolUI: ToolUIRenderer = fileOpAlias("delete_file")
val CreateDirectoryToolUI: ToolUIRenderer = fileOpAlias("create_directory")
val BatchCopyToolUI: ToolUIRenderer = fileOpAlias("batch_copy")
val BatchMoveToolUI: ToolUIRenderer = fileOpAlias("batch_move")
val BatchDeleteToolUI: ToolUIRenderer = fileOpAlias("batch_delete")
val ZipFilesToolUI: ToolUIRenderer = fileOpAlias("zip_files")
val UnzipFileToolUI: ToolUIRenderer = fileOpAlias("unzip_file")
val DownloadFileToolUI: ToolUIRenderer = fileOpAlias("download_file")
val OpenFileToolUI: ToolUIRenderer = fileOpAlias("open_file")
val WriteBinaryFileToolUI: ToolUIRenderer = fileOpAlias("write_binary_file")
// SSH 文件型复用
val SshDownloadToolUI: ToolUIRenderer = fileOpAlias("ssh_download")
val SshUploadToolUI: ToolUIRenderer = fileOpAlias("ssh_upload")
