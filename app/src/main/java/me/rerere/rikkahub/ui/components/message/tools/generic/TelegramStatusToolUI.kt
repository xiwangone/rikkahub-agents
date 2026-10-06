package me.rerere.rikkahub.ui.components.message.tools.generic

import me.rerere.rikkahub.ui.components.message.tools.getErrorCode
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Telegram
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock

/**
 * Telegram 状态配置类工具渲染器：telegram_status / telegram_enable /
 * telegram_disable / telegram_set_token
 *
 * telegram_status 在摘要里展示启用/服务运行状态；
 * telegram_set_token 只展示验证结果，绝不回显 token 明文。
 */
object TelegramStatusToolUI : ToolUIRenderer {
    override val toolName: String = "telegram_status"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Telegram

    @Composable
    override fun title(context: ToolUIContext): String {
        return when (context.tool.toolName) {
            "telegram_status" ->
                stringResource(R.string.tool_ui_tg_status)
            "telegram_enable" ->
                stringResource(R.string.tool_ui_tg_enable)
            "telegram_disable" ->
                stringResource(R.string.tool_ui_tg_disable)
            "telegram_set_token" ->
                stringResource(R.string.tool_ui_tg_set_token)
            else -> stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        statusLine(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val line = statusLine(context) ?: return
        HighlightCodeBlock(
            code = line,
            language = "text",
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun statusLine(context: ToolUIContext): String? {
        val content = context.content ?: return null
        return when (context.tool.toolName) {
            "telegram_status" -> {
                val enabled = content.getStringContent("enabled") == "true"
                val running = content.getStringContent("service_running") == "true"
                when {
                    !enabled -> stringResource(R.string.tool_ui_tg_status_disabled)
                    running -> stringResource(R.string.tool_ui_tg_status_enabled_running)
                    else -> stringResource(R.string.tool_ui_tg_status_enabled_stopped)
                }
            }
            "telegram_set_token" -> {
                val error = content.getErrorCode()
                if (error.isNullOrBlank()) {
                    stringResource(R.string.tool_ui_tg_token_ok)
                } else {
                    stringResource(R.string.tool_ui_tg_token_failed)
                }
            }
            else -> null
        }
    }
}

/** TelegramStatusToolUI 的多 key 别名：同一实例挂多个工具名 */
private fun telegramStatusAlias(toolName: String): ToolUIRenderer =
    object : ToolUIRenderer by TelegramStatusToolUI {
        override val toolName: String = toolName
    }

val TelegramEnableToolUI: ToolUIRenderer = telegramStatusAlias("telegram_enable")
val TelegramDisableToolUI: ToolUIRenderer = telegramStatusAlias("telegram_disable")
val TelegramSetTokenToolUI: ToolUIRenderer = telegramStatusAlias("telegram_set_token")
