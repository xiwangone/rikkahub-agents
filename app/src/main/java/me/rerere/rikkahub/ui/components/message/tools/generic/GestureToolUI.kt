package me.rerere.rikkahub.ui.components.message.tools.generic

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cursor01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * 手势自动化类工具渲染器：tap / swipe / long_press / scroll / click_node / set_text
 *
 * 这类工具输出只有 `{success}`，默认渲染是"调用工具 tap / success: true"，
 * 完全看不出操作了什么。标题直接显示手势内容（坐标 / 方向 / 文本），
 * 成功时无摘要，失败时摘要显示原因。
 */
object GestureToolUI : ToolUIRenderer {
    override val toolName: String = "tap"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Cursor01

    @Composable
    @Suppress("ComplexCondition") // 四个可选坐标的非空判断，拆开反而更难读
    override fun title(context: ToolUIContext): String {
        val args = context.arguments
        return when (context.tool.toolName) {
            "tap" -> {
                val x = args.intArg("x")
                val y = args.intArg("y")
                if (x != null && y != null) stringResource(R.string.tool_ui_gesture_tap, x, y)
                else genericTitle("tap")
            }
            "long_press" -> {
                val x = args.intArg("x")
                val y = args.intArg("y")
                if (x != null && y != null) stringResource(R.string.tool_ui_gesture_long_press, x, y)
                else genericTitle("long_press")
            }
            "swipe" -> {
                val x1 = args.intArg("start_x")
                val y1 = args.intArg("start_y")
                val x2 = args.intArg("end_x")
                val y2 = args.intArg("end_y")
                if (x1 != null && y1 != null && x2 != null && y2 != null) {
                    stringResource(R.string.tool_ui_gesture_swipe, x1, y1, x2, y2)
                } else genericTitle("swipe")
            }
            "scroll" -> {
                val dir = args.getStringContent("direction")?.let { directionText(it) }
                if (dir != null) stringResource(R.string.tool_ui_gesture_scroll, dir)
                else genericTitle("scroll")
            }
            "click_node" -> {
                val target = args.getStringContent("value")
                    ?: args.getStringContent("node_id")?.take(24)
                if (target != null) stringResource(R.string.tool_ui_gesture_click_node, target)
                else genericTitle("click_node")
            }
            "set_text" -> {
                val text = args.getStringContent("text")?.take(24)?.ifBlank { null }
                if (text != null) stringResource(R.string.tool_ui_gesture_set_text, text)
                else genericTitle("set_text")
            }
            else -> genericTitle(context.tool.toolName)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content?.getStringContent("success") == "false"

    @Composable
    override fun Summary(context: ToolUIContext) {
        val reason = context.content?.getStringContent("reason") ?: return
        HighlightCodeBlock(
            code = stringResource(R.string.tool_ui_gesture_failed, reason),
            language = "text",
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun genericTitle(toolName: String): String =
        stringResource(R.string.chat_message_tool_call_generic, toolName)

    @Composable
    private fun directionText(direction: String): String =
        when (direction) {
            "up" -> stringResource(R.string.tool_ui_gesture_dir_up)
            "down" -> stringResource(R.string.tool_ui_gesture_dir_down)
            "left" -> stringResource(R.string.tool_ui_gesture_dir_left)
            "right" -> stringResource(R.string.tool_ui_gesture_dir_right)
            else -> direction
        }

    private fun JsonElement.intArg(key: String): Int? =
        jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.intOrNull
}

/** GestureToolUI 的多 key 别名：同一实例挂多个工具名 */
private fun gestureAlias(toolName: String): ToolUIRenderer =
    object : ToolUIRenderer by GestureToolUI {
        override val toolName: String = toolName
    }

val SwipeToolUI: ToolUIRenderer = gestureAlias("swipe")
val LongPressToolUI: ToolUIRenderer = gestureAlias("long_press")
val ScrollToolUI: ToolUIRenderer = gestureAlias("scroll")
val ClickNodeToolUI: ToolUIRenderer = gestureAlias("click_node")
val SetTextToolUI: ToolUIRenderer = gestureAlias("set_text")
