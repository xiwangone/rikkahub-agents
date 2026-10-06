package me.rerere.rikkahub.ui.components.message.tools.generic

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent

/**
 * 应用启动类工具渲染器：launch_app / launch_activity
 *
 * 输出 `{success}`，默认标题看不出启动了什么。标题直接显示包名。
 */
object LaunchAppToolUI : ToolUIRenderer {
    override val toolName: String = "launch_app"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.SmartPhone01

    @Composable
    override fun title(context: ToolUIContext): String {
        val pkg = context.arguments.getStringContent("package_name")?.ifBlank { null } ?: "?"
        return when (context.tool.toolName) {
            "launch_activity" -> stringResource(R.string.tool_ui_launch_activity_title, pkg)
            else -> stringResource(R.string.tool_ui_launch_app_title, pkg)
        }
    }
}

/** LaunchAppToolUI 的多 key 别名：同一实例挂多个工具名 */
private fun launchAppAlias(toolName: String): ToolUIRenderer =
    object : ToolUIRenderer by LaunchAppToolUI {
        override val toolName: String = toolName
    }

val LaunchActivityToolUI: ToolUIRenderer = launchAppAlias("launch_activity")
