package me.rerere.rikkahub.ui.components.message.tools.generic

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.JsonElement
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer
import me.rerere.rikkahub.ui.components.message.tools.getStringContent
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * 网页抓取工具渲染器：web_fetch
 *
 * 输出含 `title` / `final_url` / 长文本 `text`。正文摘要可用（前 8 行），
 * 但标题"调用工具 web_fetch"看不出抓的是哪个页面——标题显示页面标题或域名。
 */
object WebFetchToolUI : ToolUIRenderer {
    override val toolName: String = "web_fetch"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.GlobalSearch

    @Composable
    override fun title(context: ToolUIContext): String {
        val pageTitle = context.content?.getStringContent("title")?.ifBlank { null }
        val url = context.content?.getStringContent("final_url")
            ?: context.arguments.getStringContent("url")
        val host = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.ifBlank { null }
        val label = pageTitle?.take(40) ?: host ?: url?.take(40) ?: "?"
        return stringResource(R.string.tool_ui_webfetch_title, label)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        longText(context.content) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val text = longText(context.content) ?: return
        HighlightCodeBlock(
            code = text,
            language = "text",
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private fun longText(content: JsonElement?): String? {
        val obj = content?.jsonObjectOrNull ?: return null
        val longest = obj.entries.mapNotNull { (_, v) ->
            v.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.length > 200 }
        }.maxByOrNull { it.length } ?: return null
        return longest.lineSequence().take(8).joinToString("\n")
    }
}
