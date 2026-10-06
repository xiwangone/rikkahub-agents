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
 * Telegram 发送类工具渲染器：telegram_send_message / telegram_send_photo /
 * telegram_send_document / telegram_set_default_chat
 *
 * 标题直接显示发送内容摘要（文本截断 / 文件名 / 聊天 ID），
 * 出错时在摘要里展示错误信息。
 */
object TelegramSendToolUI : ToolUIRenderer {
    private const val TEXT_MAX_CHARS = 40

    override val toolName: String = "telegram_send_message"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Telegram

    @Composable
    override fun title(context: ToolUIContext): String {
        val args = context.arguments
        return when (context.tool.toolName) {
            "telegram_send_message" -> {
                val text = args.getStringContent("text")?.take(TEXT_MAX_CHARS) ?: "?"
                stringResource(R.string.tool_ui_tg_send_message, text)
            }
            "telegram_send_photo" -> {
                val name = args.getStringContent("path")?.substringAfterLast("/") ?: "?"
                stringResource(R.string.tool_ui_tg_send_photo, name)
            }
            "telegram_send_document" -> {
                val name = args.getStringContent("path")?.substringAfterLast("/") ?: "?"
                stringResource(R.string.tool_ui_tg_send_document, name)
            }
            "telegram_set_default_chat" -> {
                val chatId = args.getStringContent("chat_id") ?: "?"
                stringResource(R.string.tool_ui_tg_set_default_chat, chatId)
            }
            else -> stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        !context.content.getErrorCode().isNullOrBlank()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val error = context.content.getErrorCode() ?: return
        if (error.isBlank()) return
        HighlightCodeBlock(
            code = error,
            language = "text",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** TelegramSendToolUI 的多 key 别名：同一实例挂多个工具名 */
private fun telegramSendAlias(toolName: String): ToolUIRenderer =
    object : ToolUIRenderer by TelegramSendToolUI {
        override val toolName: String = toolName
    }

val TelegramSendPhotoToolUI: ToolUIRenderer = telegramSendAlias("telegram_send_photo")
val TelegramSendDocumentToolUI: ToolUIRenderer = telegramSendAlias("telegram_send_document")
val TelegramSetDefaultChatToolUI: ToolUIRenderer = telegramSendAlias("telegram_set_default_chat")
