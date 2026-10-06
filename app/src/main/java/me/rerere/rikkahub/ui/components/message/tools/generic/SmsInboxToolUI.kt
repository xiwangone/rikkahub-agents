package me.rerere.rikkahub.ui.components.message.tools.generic

import kotlinx.serialization.json.contentOrNull
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Message01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * 短信收件箱类工具渲染器：list_sms_inbox / search_sms
 *
 * 短信对象是 `{id, address, body, date_ms, read}`，默认的通用标签提取
 * 命中了 `id` 键，摘要退化成数字 ID 列表。这里逐条显示"发件人: 内容前 40 字"。
 */
object SmsInboxToolUI : ToolUIRenderer {
    override val toolName: String = "list_sms_inbox"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Message01

    @Composable
    override fun title(context: ToolUIContext): String {
        return when (context.tool.toolName) {
            "search_sms" -> {
                val query = context.arguments.getStringContent("query")?.take(24)?.ifBlank { null }
                if (query != null) stringResource(R.string.tool_ui_sms_search_title, query)
                else stringResource(R.string.tool_ui_sms_list_title)
            }
            else -> stringResource(R.string.tool_ui_sms_list_title)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        messageLines(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val lines = messageLines(context)
        if (lines.isEmpty()) return
        HighlightCodeBlock(
            code = lines.joinToString("\n"),
            language = "text",
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private fun messageLines(context: ToolUIContext): List<String> {
        val arr = context.content?.jsonObjectOrNull?.get("messages")?.let {
            runCatching { it.jsonArray }.getOrNull()
        } ?: return emptyList()
        val lines = arr.take(8).mapNotNull { el ->
            val obj = el.jsonObjectOrNull ?: return@mapNotNull null
            val address = obj["address"]?.jsonPrimitiveOrNull?.contentOrNull?.ifBlank { null } ?: "?"
            val body = obj["body"]?.jsonPrimitiveOrNull?.contentOrNull
                ?.replace("\n", " ")?.take(40)?.ifBlank { null } ?: ""
            "$address: $body".trimEnd(' ', ':')
        }
        return if (arr.size > 8) lines + "… (${arr.size})" else lines
    }
}

/** SmsInboxToolUI 的多 key 别名：同一实例挂多个工具名 */
private fun smsInboxAlias(toolName: String): ToolUIRenderer =
    object : ToolUIRenderer by SmsInboxToolUI {
        override val toolName: String = toolName
    }

val SearchSmsToolUI: ToolUIRenderer = smsInboxAlias("search_sms")
