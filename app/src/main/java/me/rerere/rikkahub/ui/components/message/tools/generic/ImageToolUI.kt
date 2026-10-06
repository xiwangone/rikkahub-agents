package me.rerere.rikkahub.ui.components.message.tools.generic

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage

/**
 * 图片类工具渲染器：take_screenshot / take_photo / show_image
 *
 * 输出含 UIMessagePart.Image（file:// URL）。摘要直接显示缩略图，
 * 标题显示来源路径。
 */
object ImageToolUI : ToolUIRenderer {
    override val toolName: String = "take_screenshot"

    override fun icon(context: ToolUIContext): ImageVector =
        when (context.tool.toolName) {
            "take_photo" -> HugeIcons.Camera01
            else -> HugeIcons.Image02
        }

    @Composable
    override fun title(context: ToolUIContext): String {
        val path =
            context.content?.getStringContent("gallery_path")
                ?: context.content?.getStringContent("path")
                ?: context.arguments.getStringContent("path")
        return if (path != null) {
            stringResource(R.string.tool_ui_image_title_with_path, path)
        } else {
            stringResource(
                when (context.tool.toolName) {
                    "take_screenshot" -> R.string.tool_ui_image_screenshot
                    "take_photo" -> R.string.tool_ui_image_photo
                    else -> R.string.tool_ui_image_show
                },
            )
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.tool.output.any { it is UIMessagePart.Image }

    @Composable
    override fun Summary(context: ToolUIContext) {
        val image = context.tool.output.filterIsInstance<UIMessagePart.Image>().firstOrNull()
        if (image != null) {
            ZoomableAsyncImage(
                model = image.url,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/** ImageToolUI 的多 key 别名：同一实例挂多个工具名 */
private fun imageAlias(toolName: String): ToolUIRenderer =
    object : ToolUIRenderer by ImageToolUI {
        override val toolName: String = toolName
    }

val TakePhotoToolUI: ToolUIRenderer = imageAlias("take_photo")
val ShowImageToolUI: ToolUIRenderer = imageAlias("show_image")
