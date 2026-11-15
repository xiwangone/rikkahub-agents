package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.R

/**
 * phone_use（无障碍操控手机）的渲染器。
 *
 * 此前没注册渲染器 → 落到默认渲染器，聊天里只显示一串 JSON：「点了什么 / 结果如何」
 * 都得自己从 JSON 里读。这里补两处：标题带动作名（tap / observe / open_app…），
 * 内联摘要显示输出的第一行。完整内容仍走默认 Preview（点开看全部）。
 *
 * 注意：本工具输出是**纯文本**（不是 JSON），所以摘要从 [UIMessagePart.Tool.output]
 * 里取第一个文本部件，而不是解析 [ToolUIContext.content]。
 */
internal object PhoneUseToolUI : ToolUIRenderer {
    override val toolName: String = "phone_use"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.SmartPhone01

    @Composable
    override fun title(context: ToolUIContext): String {
        val base = stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)
        val action = (context.arguments as? JsonObject)
            ?.get("action")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }
        return if (action == null) base else "$base · $action"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = firstOutputLine(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val line = firstOutputLine(context) ?: return
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }

    /** 输出为纯文本：取第一行非空文本，超长截断（摘要只用来一眼看结果）。 */
    private fun firstOutputLine(context: ToolUIContext): String? =
        context.tool.output
            .filterIsInstance<UIMessagePart.Text>()
            .asSequence()
            .flatMap { it.text.lineSequence() }
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(200)
}
