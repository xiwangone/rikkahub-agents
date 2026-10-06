package me.rerere.rikkahub.ui.components.message.tools.generic

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Telegram
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock

/**
 * Telegram 白名单类工具渲染器：telegram_add_whitelist / telegram_remove_whitelist
 *
 * 标题显示增删的 ID，摘要展示操作后的完整白名单。
 */
object TelegramWhitelistToolUI : ToolUIRenderer {
    override val toolName: String = "telegram_add_whitelist"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Telegram

    @Composable
    override fun title(context: ToolUIContext): String {
        val id = context.arguments.getStringContent("id") ?: "?"
        return when (context.tool.toolName) {
            "telegram_add_whitelist" ->
                stringResource(R.string.tool_ui_tg_whitelist_add, id)
            "telegram_remove_whitelist" ->
                stringResource(R.string.tool_ui_tg_whitelist_remove, id)
            else -> stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        whitelistIds(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val ids = whitelistIds(context)
        if (ids.isEmpty()) return
        HighlightCodeBlock(
            code = stringResource(R.string.tool_ui_tg_whitelist_list, ids.joinToString(", ")),
            language = "text",
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private fun whitelistIds(context: ToolUIContext): List<String> =
        context.content?.jsonObjectOrNull?.get("whitelist")?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .orEmpty()
}

/** TelegramWhitelistToolUI 的多 key 别名：同一实例挂多个工具名 */
private fun telegramWhitelistAlias(toolName: String): ToolUIRenderer =
    object : ToolUIRenderer by TelegramWhitelistToolUI {
        override val toolName: String = toolName
    }

val TelegramRemoveWhitelistToolUI: ToolUIRenderer = telegramWhitelistAlias("telegram_remove_whitelist")
