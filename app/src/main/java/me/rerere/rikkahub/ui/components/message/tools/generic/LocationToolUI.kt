package me.rerere.rikkahub.ui.components.message.tools.generic

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Location01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent

/**
 * 定位工具渲染器：get_location
 *
 * 输出 `{latitude, longitude, accuracy_m, ...}`。默认摘要是裸 `k: v` 数字堆砌，
 * 标题直接显示坐标（保留 4 位小数），一眼可读。
 */
object LocationToolUI : ToolUIRenderer {
    override val toolName: String = "get_location"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Location01

    @Composable
    override fun title(context: ToolUIContext): String {
        val lat = context.content?.getStringContent("latitude")?.let(::formatCoord)
        val lng = context.content?.getStringContent("longitude")?.let(::formatCoord)
        return if (lat != null && lng != null) {
            stringResource(R.string.tool_ui_location_title, lat, lng)
        } else {
            stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)
        }
    }

    private fun formatCoord(raw: String): String =
        runCatching { "%.4f".format(raw.toDouble()) }.getOrNull() ?: raw.take(9)
}
