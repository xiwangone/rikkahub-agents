package me.rerere.rikkahub.ui.components.message.tools.generic

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Message02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock

/**
 * 发送短信工具渲染器：send_sms
 *
 * 输出 `{success, parts_sent}`，默认渲染"调用工具 send_sms / success: true"，
 * 看不出发给谁。发短信是真实外发（花钱 / 留痕），收件人必须在标题可见。
 */
object SendSmsToolUI : ToolUIRenderer {
    override val toolName: String = "send_sms"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Message02

    @Composable
    override fun title(context: ToolUIContext): String {
        val recipient = context.arguments.getStringContent("recipient")?.ifBlank { null } ?: "?"
        return stringResource(R.string.tool_ui_send_sms_title, recipient)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content?.getStringContent("parts_sent") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val parts = context.content?.getStringContent("parts_sent")?.toIntOrNull() ?: return
        HighlightCodeBlock(
            code = stringResource(R.string.tool_ui_send_sms_parts, parts),
            language = "text",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
