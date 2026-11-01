package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.highlight.CodeHighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.format.DateTimeFormatter


/**
 * 网络搜索: 标题带查询词, 摘要显示 answer 与结果数, 详情为结果列表
 */
object SearchWebToolUI : ToolUIRenderer {
    override val toolName: String = "search_web"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(
            R.string.chat_message_tool_search_web,
            context.arguments.getStringContent("query") ?: "",
        )

    private fun searchWebItems(context: ToolUIContext): List<JsonElement> =
        context.content
            ?.jsonObjectOrNull
            ?.get("items")
            ?.jsonArray ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("answer") != null || searchWebItems(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("answer")?.let { answer ->
            Text(
                text = answer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.shimmer(isLoading = context.loading),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val items = searchWebItems(context)
        if (items.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FaviconRow(
                    urls = items.mapNotNull { it.getStringContent("url") },
                    size = 18.dp,
                )
                Text(
                    text = stringResource(R.string.chat_message_tool_search_results_count, items.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }

    @Composable
    override fun Preview(
        context: ToolUIContext,
        onDismissRequest: () -> Unit,
    ) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        SearchWebPreview(arguments = context.arguments, content = content)
    }
}

/**
 * 网页抓取: 摘要显示 URL, 详情为各网页的 Markdown 内容
 */
object ScrapeWebToolUI : ToolUIRenderer {
    override val toolName: String = "scrape_web"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.GlobalSearch

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(R.string.chat_message_tool_scrape_web)

    override fun hasSummary(context: ToolUIContext): Boolean = context.arguments.getStringContent("url") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        Text(
            text = context.arguments.getStringContent("url") ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
    }

    @Composable
    override fun Preview(
        context: ToolUIContext,
        onDismissRequest: () -> Unit,
    ) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        ScrapeWebPreview(content = content)
    }
}

@Composable
private fun SearchWebPreview(
    arguments: JsonElement,
    content: JsonElement,
) {
    val context = LocalContext.current
    val items = content.jsonObject["items"]?.jsonArray ?: emptyList()
    val answer = content.getStringContent("answer")
    val query = arguments.getStringContent("query") ?: ""
    val parameters = arguments.jsonObjectOrNull?.entries?.filter { it.key != "query" }.orEmpty()
    val images =
        content.jsonObject["images"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    LazyColumn(
        modifier =
            Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(stringResource(R.string.chat_message_tool_search_prefix, query))
        }

        if (parameters.isNotEmpty()) {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    parameters.forEach { (name, value) ->
                        val displayValue = when (value) {
                            is JsonArray -> value.joinToString(", ") {
                                it.jsonPrimitiveOrNull?.contentOrNull ?: it.toString()
                            }
                            else -> value.jsonPrimitiveOrNull?.contentOrNull ?: value.toString()
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Text(
                                text = "$name: $displayValue",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }

        if (answer != null) {
            item {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                ) {
                    MarkdownBlock(
                        content = answer,
                        modifier =
                            Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (images.isNotEmpty()) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(images) { imageUrl ->
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .height(120.dp)
                                    .width(160.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { context.openUrl(imageUrl) },
                        )
                    }
                }
            }
        }

        if (items.isNotEmpty()) {
            items(items) { item ->
                val url = item.getStringContent("url") ?: return@items
                val title = item.getStringContent("title") ?: return@items
                val text = item.getStringContent("text") ?: return@items
                val publishedDate = item.getStringContent("publishedDate")?.takeIf { it.isNotBlank() }
                val dateLabel = remember(publishedDate) {
                    publishedDate?.let { value ->
                        runCatching {
                            LocalDate.parse(value, DateTimeFormatter.ISO_DATE_TIME)
                        }.recoverCatching {
                            LocalDate.parse(value, DateTimeFormatter.ISO_DATE)
                        }.getOrNull()?.toLocalString(includeYear = true) ?: value
                    }
                }

                Card(
                    onClick = { context.openUrl(url) },
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Favicon(
                            url = url,
                            modifier = Modifier.size(24.dp),
                        )
                        Column {
                            Text(text = title, maxLines = 1)
                            if (dateLabel != null) {
                                Text(
                                    text = dateLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = url,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        } else {
            item {
                CodeHighlightText(
                    code = JsonInstantPretty.encodeToString(content),
                    language = "json",
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun ScrapeWebPreview(content: JsonElement) {
    val urls = content.jsonObject["urls"]?.jsonArray ?: emptyList()

    LazyColumn(
        modifier =
            Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text =
                    stringResource(
                        R.string.chat_message_tool_scrape_prefix,
                        urls.joinToString(", ") { it.getStringContent("url") ?: "" },
                    ),
            )
        }

        items(urls) { url ->
            val urlObject = url.jsonObject
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = urlObject["url"]?.jsonPrimitive?.content ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth(),
                )
                Card {
                    MarkdownBlock(
                        content = urlObject["content"]?.jsonPrimitive?.content ?: "",
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                    )
                }
            }
        }
    }
}

