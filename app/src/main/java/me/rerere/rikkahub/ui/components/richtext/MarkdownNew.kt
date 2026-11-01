package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.ui.components.table.DataTable
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.toDp
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import android.os.SystemClock
import me.rerere.rikkahub.data.log.AppLog

// ---- Preprocessing (mirrors Markdown.kt logic) ----

private const val TAG = "MarkdownNew"

private val INLINE_LATEX_REGEX = Regex("\\\\\\((.+?)\\\\\\)")
private val BLOCK_LATEX_REGEX = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)

private fun preProcess(content: String): String {
    val codeBlocks = mutableListOf<IntRange>()
    CODE_BLOCK_REGEX.findAll(content).forEach { codeBlocks.add(it.range) }

    fun isInCodeBlock(pos: Int) = codeBlocks.any { pos in it }

    var result =
        INLINE_LATEX_REGEX.replace(content) { m ->
            if (isInCodeBlock(m.range.first)) m.value else "$" + m.groupValues[1] + "$"
        }
    result =
        BLOCK_LATEX_REGEX.replace(result) { m ->
            if (isInCodeBlock(m.range.first)) m.value else "$$" + m.groupValues[1] + "$$"
        }
    return result
}

// ---- HTML generation ----

private val flavour by lazy {
    GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
}

private val parser by lazy { MarkdownParser(flavour) }

/** 首帧同步生成 HTML 的文本上限（字符）：超过则先留空，由后台管线下一帧补上。
 *  与 MarkdownBlock 的阈值保持一致（8000），避免两者不等时出现“一半同步一半留空”的额外跳变。 */
private const val SYNC_HTML_MAX_CHARS = 8_000

/** 渲染性能日志的 TAG（I 级才会落盘，见 AppLog：D 级只留内存）。 */
private const val RENDER_PERF_TAG = "RenderPerf"

/** 渲染性能日志门槛：长度超过它、或生成耗时超过 [PERF_LOG_SLOW_MS] 才记一条（避免刷屏）。 */
private const val PERF_LOG_MIN_CHARS = 4_000
private const val PERF_LOG_SLOW_MS = 8L

private fun generateMarkdownHtml(content: String): String {
    val preprocessed = preProcess(content)
    val tree = parser.buildMarkdownTreeFromString(preprocessed)
    return HtmlGenerator(preprocessed, tree, flavour).generateHtml()
}

// ---- Main composable ----

@Composable
fun MarkdownNew(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {},
) {
    // 首帧不为主线程做长文的 markdown→HTML（同 MarkdownBlock 的理由）：长文先留空，
    // 下一帧由下面的后台管线补上；短文本保持同步以免闪烁。
    val initialHtml =
        remember {
            val started = SystemClock.uptimeMillis()
            val generated = if (content.length <= SYNC_HTML_MAX_CHARS) generateMarkdownHtml(content) else ""
            Triple(generated, SystemClock.uptimeMillis() - started, content.length)
        }
    var html by remember { mutableStateOf(initialHtml.first) }
    // 渲染性能埋点：只记一次、且只在长文/慢时记（I 级会落盘 → 重启后仍可回看）
    LaunchedEffect(Unit) {
        val (_, costMs, len) = initialHtml
        if (len > PERF_LOG_MIN_CHARS || costMs >= PERF_LOG_SLOW_MS) {
            AppLog.i(RENDER_PERF_TAG, "markdown→html 首帧 len=$len 耗时=${costMs}ms")
        }
    }

    val updatedContent by rememberUpdatedState(content)
    LaunchedEffect(Unit) {
        snapshotFlow { updatedContent }
            .distinctUntilChanged()
            // 串行生成并丢弃积压的中间值：避免反复取消已在进行的生成，
            // 同时保证最新内容一定被处理（不会停在旧结果上）
            .conflate()
            .map { generateMarkdownHtml(it) }
            .catch { AppLog.e(TAG, "MarkdownNew: failed to generate markdown HTML", it) }
            .flowOn(Dispatchers.Default)
            .collect { html = it }
    }

    // HTML 解析放到后台线程：文档较长时 Jsoup.parse 在组合期同步执行会拖慢首帧
    val documentState =
        produceState<org.jsoup.nodes.Document?>(initialValue = null, html) {
            value =
                withContext(Dispatchers.Default) {
                    runCatching { Jsoup.parse(html) }.getOrElse { Jsoup.parse("") }
                }
        }
    val document = documentState.value ?: return

    ProvideTextStyle(style) {
        Column(modifier = modifier.padding(start = 4.dp)) {
            document.body().childNodes().fastForEach { node ->
                HtmlBodyNode(node = node, onClickCitation = onClickCitation)
            }
        }
    }
}

// ---- Node dispatching ----

@Composable
internal fun HtmlStyledElement(
    element: Element,
    content: @Composable () -> Unit,
) {
    val baseTextStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val elementStyle =
        remember(element.attr("style"), density, baseTextStyle) {
            element.attr("style").takeIf { it.isNotBlank() }?.let {
                parseBlockTextStyle(
                    style = it,
                    density = density,
                    baseTextStyle = baseTextStyle,
                )
            }
        }

    if (elementStyle != null) {
        ProvideTextStyle(baseTextStyle.merge(elementStyle), content)
    } else {
        content()
    }
}

@Composable
internal fun HtmlBodyNode(
    node: Node,
    onClickCitation: (String) -> Unit,
) {
    when (node) {
        is Element -> {
            HtmlBlockElement(element = node, onClickCitation = onClickCitation)
        }

        is TextNode -> {
            val text = node.text().trim()
            if (text.isNotEmpty()) Text(text = text)
        }
    }
}

@Composable
private fun HtmlBlockElement(
    element: Element,
    onClickCitation: (String) -> Unit,
    listLevel: Int = 0,
) {
    when (element.tagName().lowercase()) {
        "p" -> {
            HtmlParagraph(
                element = element,
                onClickCitation = onClickCitation,
                modifier =
                    if (element.nextElementSibling() != null) {
                        Modifier.padding(bottom = LocalTextStyle.current.fontSize.toDp())
                    } else {
                        Modifier
                    },
            )
        }

        "h1", "h2", "h3", "h4", "h5", "h6" -> {
            HtmlHeading(
                element = element,
                onClickCitation = onClickCitation,
            )
        }

        "ul" -> {
            HtmlList(
                element = element,
                ordered = false,
                onClickCitation = onClickCitation,
                level = listLevel,
            )
        }

        "ol" -> {
            HtmlList(
                element = element,
                ordered = true,
                onClickCitation = onClickCitation,
                level = listLevel,
            )
        }

        "pre" -> {
            HtmlCodeBlock(element = element)
        }

        "blockquote" -> {
            HtmlStyledElement(element = element) {
                HtmlBlockquote(element = element, onClickCitation = onClickCitation)
            }
        }

        "table" -> {
            HtmlStyledElement(element = element) {
                HtmlTable(element = element, onClickCitation = onClickCitation)
            }
        }

        "hr" -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                thickness = 0.5.dp,
            )
        }

        "img" -> {
            val src = element.attr("src")
            val alt = element.attr("alt")
            if (src.isNotEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ZoomableAsyncImage(
                        model = rememberWorkspaceImageUrl(src),
                        contentDescription = alt.takeIf { it.isNotEmpty() },
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .widthIn(min = 120.dp)
                                .heightIn(min = 120.dp),
                    )
                }
            }
        }

        "span" -> {
            // Block-level math span emitted directly into body
            if (element.hasClass("math") && element.attr("inline") != "true") {
                HtmlMathBlock(formula = element.text())
            } else {
                HtmlInlineGroup(nodes = listOf(element), onClickCitation = onClickCitation)
            }
        }

        "details" -> {
            HtmlStyledElement(element = element) {
                HtmlDetails(element = element, onClickCitation = onClickCitation)
            }
        }

        "progress" -> {
            HtmlProgress(element = element)
        }

        "div" -> {
            HtmlStyledElement(element = element) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    element.childNodes().fastForEach { HtmlBodyNode(it, onClickCitation) }
                }
            }
        }

        else -> {
            HtmlStyledElement(element = element) {
                // Generic fallback: recurse into children
                element.childNodes().forEach { HtmlBodyNode(it, onClickCitation) }
            }
        }
    }
}

// ---- Block renderers ----

@Composable
private fun HtmlParagraph(
    element: Element,
    onClickCitation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseTextStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val paragraphStyle =
        remember(element.attr("style"), density, baseTextStyle) {
            element.attr("style").takeIf { it.isNotBlank() }?.let {
                parseBlockTextStyle(
                    style = it,
                    density = density,
                    baseTextStyle = baseTextStyle,
                )
            }
        }

    if (paragraphStyle != null) {
        ProvideTextStyle(baseTextStyle.merge(paragraphStyle)) {
            HtmlParagraphContent(
                element = element,
                onClickCitation = onClickCitation,
                density = density,
                modifier = modifier,
            )
        }
    } else {
        HtmlParagraphContent(
            element = element,
            onClickCitation = onClickCitation,
            density = density,
            modifier = modifier,
        )
    }
}

@Composable
private fun HtmlParagraphContent(
    element: Element,
    onClickCitation: (String) -> Unit,
    density: Density,
    modifier: Modifier = Modifier,
) {
    val hasImages = element.select("img").isNotEmpty()
    // A span.math with inline != "true" is a block math element
    val hasBlockMath = element.select("span.math").any { it.attr("inline") != "true" }

    if (hasImages || hasBlockMath) {
        // Mixed block content: render children individually in a FlowRow
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            element.childNodes().fastForEach { child ->
                HtmlInlineAsComposable(node = child, onClickCitation = onClickCitation)
            }
        }
        return
    }

    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    val hasInlineMath = element.select("span.math").any { it.attr("inline") == "true" }
    val colorScheme = MaterialTheme.colorScheme
    val textStyle = LocalTextStyle.current
    // Per-paragraph direction from the first strong directional character so an
    // Arabic paragraph renders RTL and an English one stays LTR in one message.
    val textDirection =
        remember(element.outerHtml()) {
            resolveTextDirection(element.text())
        }

    val (annotatedString, inlineContents) =
        remember(
            element.outerHtml(),
            enableLatexRendering,
            colorScheme,
            density,
            textStyle,
            onClickCitation,
        ) {
            val contents = mutableMapOf<String, InlineTextContent>()
            val text =
                buildAnnotatedString {
                    element.childNodes().forEach { child ->
                        appendHtmlInlineNode(
                            node = child,
                            colorScheme = colorScheme,
                            inlineContents = contents,
                            density = density,
                            style = textStyle,
                            enableLatexRendering = enableLatexRendering,
                            onClickCitation = onClickCitation,
                        )
                    }
                }
            text to contents
        }

    Text(
        text = annotatedString,
        inlineContent = inlineContents,
        softWrap = true,
        overflow = TextOverflow.Visible,
        modifier = modifier.fillMaxWidth(),
        style =
            textStyle.copy(
                lineHeight =
                    if (hasInlineMath && enableLatexRendering) {
                        TextUnit.Unspecified
                    } else {
                        textStyle.lineHeight
                    },
                textDirection = textDirection,
            ),
    )
}

@Composable
private fun HtmlHeading(
    element: Element,
    onClickCitation: (String) -> Unit,
) {
    val level = element.tagName().removePrefix("h").toIntOrNull() ?: 1
    val headingStyle =
        HeaderStyle.fromLevel(
            level = level,
            fontSizeRatio = LocalSettings.current.displaySetting.fontSizeRatio,
        )
    val verticalPadding = HeaderStyle.verticalPadding(level)
    ProvideTextStyle(LocalTextStyle.current.merge(headingStyle)) {
        Box(modifier = Modifier.padding(vertical = verticalPadding)) {
            HtmlParagraph(element = element, onClickCitation = onClickCitation)
        }
    }
}

@Composable
private fun HtmlList(
    element: Element,
    ordered: Boolean,
    onClickCitation: (String) -> Unit,
    level: Int,
) {
    HtmlStyledElement(element = element) {
        Column(modifier = Modifier.padding(start = (level * 8).dp, top = 4.dp, bottom = 4.dp)) {
            val bulletBase =
                when (level % 3) {
                    0 -> "•"
                    1 -> "◦"
                    else -> "▪"
                }
            var orderedIndex = 1
            element.children().fastForEach { item ->
                if (item.tagName().lowercase() == "li") {
                    val bullet = if (ordered) "${orderedIndex++}. " else "$bulletBase "
                    HtmlListItem(
                        item = item,
                        bulletText = bullet,
                        onClickCitation = onClickCitation,
                        level = level,
                    )
                }
            }
        }
    }
}

@Composable
private fun HtmlListItem(
    item: Element,
    bulletText: String,
    onClickCitation: (String) -> Unit,
    level: Int,
) {
    val isTaskItem = item.hasClass("task-list-item")
    val checkboxInput = item.selectFirst("input[type=checkbox]")
    val isChecked = checkboxInput?.hasAttr("checked") == true

    HtmlStyledElement(element = item) {
        Column {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                if (isTaskItem && checkboxInput != null) {
                    // Checkbox indicator
                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        modifier = Modifier.padding(end = 4.dp, top = 2.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .padding(2.dp)
                                    .size(LocalTextStyle.current.fontSize.toDp() * 0.8f),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isChecked) {
                                Icon(
                                    imageVector = HugeIcons.Tick01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = bulletText,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.alignByBaseline(),
                    )
                }

                // Item inline content (excluding nested lists and the checkbox input)
                Column(modifier = Modifier.weight(1f)) {
                    val directContentNodes =
                        item.childNodes().filter { node ->
                            !(
                                node is Element &&
                                    (
                                        node.tagName().lowercase() in listOf("ul", "ol") ||
                                            (node.tagName().lowercase() == "input" && node.attr("type") == "checkbox")
                                    )
                            )
                        }
                    // Group consecutive inline nodes and render as a single paragraph
                    val groups = mutableListOf<MutableList<Node>>()
                    directContentNodes.fastForEach { node ->
                        if (node is Element && node.tagName().lowercase() == "p") {
                            groups.add(mutableListOf(node))
                        } else {
                            val last = groups.lastOrNull()
                            if (last != null &&
                                last.none {
                                    it is Element && it.tagName().lowercase() == "p"
                                }
                            ) {
                                last.add(node)
                            } else {
                                groups.add(mutableListOf(node))
                            }
                        }
                    }
                    groups.fastForEach { group ->
                        val first = group.firstOrNull()
                        if (first is Element && first.tagName().lowercase() == "p") {
                            HtmlParagraph(element = first, onClickCitation = onClickCitation)
                        } else {
                            HtmlInlineGroup(nodes = group, onClickCitation = onClickCitation)
                        }
                    }
                }
            }

            // Nested lists
            item.children().fastForEach { child ->
                val tag = child.tagName().lowercase()
                if (tag == "ul" || tag == "ol") {
                    HtmlList(
                        element = child,
                        ordered = tag == "ol",
                        onClickCitation = onClickCitation,
                        level = level + 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun HtmlCodeBlock(element: Element) {
    val codeElement = element.selectFirst("code")
    val language =
        codeElement
            ?.classNames()
            ?.find { it.startsWith("language-") }
            ?.removePrefix("language-")
            ?: "plaintext"
    val code = codeElement?.wholeText()?.trimEnd('\n') ?: element.wholeText().trimEnd('\n')

    HighlightCodeBlock(
        code = code,
        language = language,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
        completeCodeBlock = true,
    )
}

@Composable
private fun HtmlBlockquote(
    element: Element,
    onClickCitation: (String) -> Unit,
) {
    ProvideTextStyle(LocalTextStyle.current.copy(fontStyle = FontStyle.Italic)) {
        val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        Column(
            modifier =
                Modifier
                    .drawWithContent {
                        drawContent()
                        drawRect(color = bgColor, size = size)
                        drawRect(color = borderColor, size = Size(10f, size.height))
                    }.padding(8.dp),
        ) {
            element.childNodes().fastForEach { HtmlBodyNode(it, onClickCitation) }
        }
    }
}

@Composable
internal fun HtmlMathBlock(formula: String) {
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    if (enableLatexRendering) {
        MathBlock(
            latex = formula,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
        )
    } else {
        Text(
            text = formula,
            fontFamily = FontFamily.Monospace,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun HtmlTable(
    element: Element,
    onClickCitation: (String) -> Unit,
) {
    val headerElements = element.select("thead tr th")
    val columnCount =
        headerElements.size.takeIf { it > 0 }
            ?: element.select("tbody tr:first-child td").size
    if (columnCount == 0) return

    val headers =
        List(columnCount) { col ->
            @Composable {
                if (col < headerElements.size) {
                    HtmlStyledElement(element = headerElements[col]) {
                        HtmlInlineGroup(
                            nodes = headerElements[col].childNodes(),
                            onClickCitation = onClickCitation,
                        )
                    }
                }
            }
        }

    val bodyRows = element.select("tbody tr")
    val rows =
        bodyRows.map { tr ->
            val cellElements = tr.select("td")
            List(columnCount) { col ->
                @Composable {
                    if (col < cellElements.size) {
                        HtmlStyledElement(element = cellElements[col]) {
                            HtmlInlineGroup(
                                nodes = cellElements[col].childNodes(),
                                onClickCitation = onClickCitation,
                            )
                        }
                    }
                }
            }
        }

    DataTable(
        headers = headers,
        rows = rows,
        modifier = Modifier.padding(vertical = 8.dp),
        columnMinWidths = List(columnCount) { 80.dp },
        columnMaxWidths = List(columnCount) { 200.dp },
    )
}

@Composable
private fun HtmlDetails(
    element: Element,
    onClickCitation: (String) -> Unit,
) {
    // Delegate to the existing SimpleHtmlBlock details renderer via a mini-document
    val summaryElement = element.children().find { it.tagName().lowercase() == "summary" }
    val summaryText = summaryElement?.text() ?: "Details"

    var expanded by remember { mutableStateOf(element.hasAttr("open")) }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = if (expanded) "▼ " else "▶ ")
            Text(text = summaryText, fontWeight = FontWeight.Medium)
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 16.dp)) {
                element.childNodes().fastForEach { child ->
                    if (!(child is Element && child.tagName().lowercase() == "summary")) {
                        HtmlBodyNode(child, onClickCitation)
                    }
                }
            }
        }
    }
}

@Composable
private fun HtmlProgress(element: Element) {
    val value = element.attr("value").toFloatOrNull() ?: 0f
    val max = element.attr("max").toFloatOrNull()?.takeIf { it > 0 } ?: 100f
    val progress = (value / max).coerceIn(0f, 1f)

    val style = element.attr("style")
    val widthValue = parseCssDeclarations(style)["width"] ?: element.attr("width")

    val widthModifier =
        when {
            widthValue.endsWith("%") -> {
                widthValue
                    .removeSuffix("%")
                    .toFloatOrNull()
                    ?.let { Modifier.fillMaxWidth(it / 100f) } ?: Modifier.fillMaxWidth()
            }

            widthValue.endsWith("px") -> {
                widthValue
                    .removeSuffix("px")
                    .toIntOrNull()
                    ?.let { Modifier.width(it.dp) } ?: Modifier.fillMaxWidth()
            }

            widthValue.isNotEmpty() -> {
                widthValue
                    .toIntOrNull()
                    ?.let { Modifier.width(it.dp) } ?: Modifier.fillMaxWidth()
            }

            else -> {
                Modifier.fillMaxWidth()
            }
        }

    androidx.compose.material3.LinearProgressIndicator(
        progress = { progress },
        modifier = widthModifier.padding(vertical = 4.dp),
    )
}
