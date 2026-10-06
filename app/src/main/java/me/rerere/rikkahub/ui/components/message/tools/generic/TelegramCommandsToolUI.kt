package me.rerere.rikkahub.ui.components.message.tools.generic

import kotlinx.serialization.json.contentOrNull
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Telegram
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * Telegram 命令管理类工具渲染器：telegram_set_commands / telegram_get_commands /
 * telegram_delete_commands / telegram_set_assistant
 *
 * 标题显示操作结果（自定义命令数 / 绑定助手 ID），
 * telegram_get_commands 在摘要里列出当前命令菜单。
 */
object TelegramCommandsToolUI : ToolUIRenderer {
    private const val COMMANDS_MAX_SHOWN = 12

    override val toolName: String = "telegram_set_commands"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Telegram

    @Composable
    override fun title(context: ToolUIContext): String {
        val args = context.arguments
        val content = context.content
        return when (context.tool.toolName) {
            "telegram_set_commands" -> {
                val custom = content?.getStringContent("custom_count") ?: "?"
                val total = content?.getStringContent("total") ?: "?"
                stringResource(
                    R.string.tool_ui_tg_set_commands,
                    custom.toIntOrNull() ?: 0,
                    total.toIntOrNull() ?: 0,
                )
            }
            "telegram_get_commands" ->
                stringResource(R.string.tool_ui_tg_get_commands)
            "telegram_delete_commands" ->
                stringResource(R.string.tool_ui_tg_delete_commands)
            "telegram_set_assistant" -> {
                val id = args.getStringContent("assistant_id").orEmpty()
                if (id.isBlank()) {
                    stringResource(R.string.tool_ui_tg_unbind_assistant)
                } else {
                    stringResource(R.string.tool_ui_tg_set_assistant, id.take(8))
                }
            }
            else -> stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.tool.toolName == "telegram_get_commands" &&
            commandLines(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val lines = commandLines(context)
        if (lines.isEmpty()) return
        HighlightCodeBlock(
            code = lines.joinToString("\n"),
            language = "text",
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private fun commandLines(context: ToolUIContext): List<String> {
        val arr = context.content?.jsonObjectOrNull?.get("commands")?.jsonArray
            ?: return emptyList()
        return arr.take(COMMANDS_MAX_SHOWN).mapNotNull { el ->
            val obj = el.jsonObject
            val cmd = obj["command"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
            val desc = obj["description"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
            "/$cmd — $desc".trimEnd(' ', '—')
        }
    }
}

/** TelegramCommandsToolUI 的多 key 别名：同一实例挂多个工具名 */
private fun telegramCommandsAlias(toolName: String): ToolUIRenderer =
    object : ToolUIRenderer by TelegramCommandsToolUI {
        override val toolName: String = toolName
    }

val TelegramGetCommandsToolUI: ToolUIRenderer = telegramCommandsAlias("telegram_get_commands")
val TelegramDeleteCommandsToolUI: ToolUIRenderer = telegramCommandsAlias("telegram_delete_commands")
val TelegramSetAssistantToolUI: ToolUIRenderer = telegramCommandsAlias("telegram_set_assistant")
