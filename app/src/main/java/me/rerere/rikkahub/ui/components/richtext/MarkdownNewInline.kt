package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode


// ---- Inline group rendering (for list items with mixed inline nodes) ----

/**
 * Renders a list of inline Jsoup nodes as a single Text composable with AnnotatedString.
 * This prevents inline siblings (e.g. <strong>A</strong>和<strong>B</strong>) from being
 * rendered on separate lines.
 */
@Composable
internal fun HtmlInlineGroup(
    nodes: List<Node>,
    onClickCitation: (String) -> Unit,
) {
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    val colorScheme = MaterialTheme.colorScheme
    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current

    val key = remember(nodes) { nodes.joinToString("") { if (it is Element) it.outerHtml() else it.toString() } }
    // Per-block direction so RTL list items / table cells render right-aligned.
    val textDirection =
        remember(key) {
            resolveTextDirection(
                nodes.joinToString("") { if (it is Element) it.text() else it.toString() },
            )
        }
    val (annotatedString, inlineContents) =
        remember(
            key,
            enableLatexRendering,
            colorScheme,
            density,
            textStyle,
            onClickCitation,
        ) {
            val contents = mutableMapOf<String, InlineTextContent>()
            val text =
                buildAnnotatedString {
                    nodes.fastForEach { node ->
                        appendHtmlInlineNode(
                            node = node,
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

    if (annotatedString.isNotEmpty()) {
        Text(
            text = annotatedString,
            inlineContent = inlineContents,
            style = LocalTextStyle.current.copy(textDirection = textDirection),
        )
    }
}

// ---- Inline-as-Composable rendering (for FlowRow mixed content) ----

/**
 * Renders an individual Jsoup node as a standalone Composable.
 * Used inside FlowRow for paragraphs that mix images, math, and text.
 */
@Composable
internal fun HtmlInlineAsComposable(
    node: Node,
    onClickCitation: (String) -> Unit,
) {
    when (node) {
        is TextNode -> {
            val text = node.text()
            if (text.isNotEmpty()) Text(text = text)
        }

        is Element -> {
            val tag = node.tagName().lowercase()
            when {
                tag == "img" -> {
                    val src = node.attr("src")
                    val alt = node.attr("alt")
                    if (src.isNotEmpty()) {
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

                tag == "span" && node.hasClass("math") && node.attr("inline") != "true" -> {
                    HtmlMathBlock(formula = node.text())
                }

                tag == "br" -> {
                    // handled by inline text
                }

                else -> {
                    // Render as an inline text segment
                    val colorScheme = MaterialTheme.colorScheme
                    val textStyle = LocalTextStyle.current
                    val density = LocalDensity.current
                    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
                    val (annotated, inlineContents) =
                        remember(
                            node.outerHtml(),
                            enableLatexRendering,
                            colorScheme,
                            density,
                            textStyle,
                            onClickCitation,
                        ) {
                            val contents = mutableMapOf<String, InlineTextContent>()
                            val text =
                                buildAnnotatedString {
                                    appendHtmlInlineElement(
                                        element = node,
                                        colorScheme = colorScheme,
                                        inlineContents = contents,
                                        density = density,
                                        style = textStyle,
                                        enableLatexRendering = enableLatexRendering,
                                        onClickCitation = onClickCitation,
                                    )
                                }
                            text to contents
                        }
                    Text(text = annotated, inlineContent = inlineContents)
                }
            }
        }
    }
}

// ---- Inline AnnotatedString building ----

internal fun AnnotatedString.Builder.appendHtmlInlineNode(
    node: Node,
    colorScheme: androidx.compose.material3.ColorScheme,
    inlineContents: MutableMap<String, InlineTextContent>,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean,
    onClickCitation: (String) -> Unit,
) {
    when (node) {
        is TextNode -> {
            append(node.text())
        }

        is Element -> {
            appendHtmlInlineElement(
                element = node,
                colorScheme = colorScheme,
                inlineContents = inlineContents,
                density = density,
                style = style,
                enableLatexRendering = enableLatexRendering,
                onClickCitation = onClickCitation,
            )
        }
    }
}

private fun AnnotatedString.Builder.appendInlineCodeTag(
    text: String,
    primaryColor: androidx.compose.ui.graphics.Color,
    cssStyle: SpanStyle?,
) {
    val base = SpanStyle(
        fontFamily = JetbrainsMono,
        fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0",
        fontSize = 0.9.em,
        color = primaryColor,
    )
    withStyle(if (cssStyle != null) base.merge(cssStyle) else base) {
        append(' ')
        append(text)
        append(' ')
    }
}

private fun AnnotatedString.Builder.appendHtmlInlineElement(
    element: Element,
    colorScheme: androidx.compose.material3.ColorScheme,
    inlineContents: MutableMap<String, InlineTextContent>,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean,
    onClickCitation: (String) -> Unit,
) {
    val cssStyle =
        element.attr("style").takeIf { it.isNotBlank() }?.let {
            parseInlineSpanStyle(
                style = it,
                density = density,
                baseFontSize = style.fontSize,
            )
        }

    fun recurseChildren(
        el: Element,
        inheritedStyle: TextStyle = style,
    ) = el.childNodes().fastForEach {
        appendHtmlInlineNode(
            node = it,
            colorScheme = colorScheme,
            inlineContents = inlineContents,
            density = density,
            style = inheritedStyle,
            enableLatexRendering = enableLatexRendering,
            onClickCitation = onClickCitation,
        )
    }

    fun appendStyledChildren(spanStyle: SpanStyle) =
        withStyle(spanStyle) {
            recurseChildren(element, style.merge(spanStyle.asTextStyle()))
        }

    fun appendElementChildren(tagStyle: SpanStyle = SpanStyle()) {
        val elementStyle = tagStyle.merge(cssStyle ?: SpanStyle())
        if (elementStyle == SpanStyle()) {
            recurseChildren(element)
        } else {
            appendStyledChildren(elementStyle)
        }
    }

    val simpleTagStyle = simpleInlineTagSpanStyle(element.tagName().lowercase())
    if (simpleTagStyle != null) {
        appendElementChildren(simpleTagStyle)
        return
    }
    when (element.tagName().lowercase()) {

        "code" -> appendInlineCodeTag(element.text(), colorScheme.primary, cssStyle)

        "a" -> {
            val href = element.attr("href")
            val text = element.text()
            when {
                text.startsWith("citation,") -> {
                    appendHtmlCitationBadge(
                        domain = text.substringAfter("citation,"),
                        id = href,
                        colorScheme = colorScheme,
                        inlineContents = inlineContents,
                        onClickCitation = onClickCitation,
                    )
                }

                href.isNotEmpty() -> {
                    val linkStyle =
                        SpanStyle(
                            color = colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        ).merge(cssStyle ?: SpanStyle())
                    val (cleanHref, trailing) = splitAutolink(href)
                    if (text == href && trailing.isNotEmpty()) {
                        // 自动链接生成的 <a>：显示文本与 href 相同，按同一规则切分显示，
                        // 切掉的尾巴以普通文本输出，不丢弃
                        withLink(LinkAnnotation.Url(cleanHref)) {
                            withStyle(linkStyle) { append(cleanHref) }
                        }
                        append(trailing)
                    } else {
                        withLink(LinkAnnotation.Url(cleanHref)) {
                            withStyle(linkStyle) {
                                recurseChildren(element, style.merge(linkStyle.asTextStyle()))
                            }
                        }
                    }
                }

                else -> {
                    appendElementChildren()
                }
            }
        }

        "span" -> {
            if (element.hasClass("math") && element.attr("inline") == "true") {
                appendInlineMathSpan(
                    formula = element.text(),
                    density = density,
                    style = style,
                    enableLatexRendering = enableLatexRendering,
                    inlineContents = inlineContents,
                )
            } else {
                appendElementChildren()
            }
        }



        "br" -> {
            append("\n")
        }

        else -> {
            appendElementChildren()
        }
    }
}

private fun AnnotatedString.Builder.appendInlineMathSpan(
    formula: String,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean,
    inlineContents: MutableMap<String, InlineTextContent>,
) {
    if (enableLatexRendering) {
        appendInlineContent(formula, "[Latex]")
        val (width, height) =
            with(density) {
                assumeLatexSize(latex = formula, fontSize = style.fontSize.toPx()).let {
                    it.width().toSp() to it.height().toSp()
                }
            }
        inlineContents.putIfAbsent(
            formula,
            InlineTextContent(
                placeholder =
                    Placeholder(
                        width = width,
                        height = height,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                children = {
                    MathInline(
                        latex = formula,
                        modifier = Modifier,
                    )
                },
            ),
        )
    } else {
        withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 0.95.em,
            ),
        ) {
            append(formula)
        }
    }
}

private fun AnnotatedString.Builder.appendHtmlCitationBadge(
    domain: String,
    id: String,
    colorScheme: androidx.compose.material3.ColorScheme,
    inlineContents: MutableMap<String, InlineTextContent>,
    onClickCitation: (String) -> Unit,
) {
    if (id.length != 6) return
    inlineContents.putIfAbsent(
        "citation:$id",
        InlineTextContent(
            placeholder =
                Placeholder(
                    width = (domain.length * 7).sp,
                    height = 1.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            children = {
                Box(
                    modifier =
                        Modifier
                            .clickable { onClickCitation(id.trim()) }
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(colorScheme.tertiaryContainer.copy(0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = domain,
                        modifier = Modifier.wrapContentSize(),
                        style =
                            TextStyle(
                                fontSize = 10.sp,
                                lineHeight = 10.sp,
                                fontFamily = JetbrainsMono,
                                color = colorScheme.onTertiaryContainer,
                                fontWeight = FontWeight.Thin,
                            ),
                    )
                }
            },
        ),
    )
    appendInlineContent("citation:$id")
}

private fun simpleInlineTagSpanStyle(tag: String): SpanStyle? =
    when (tag) {
        "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
        "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
        "del", "s", "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
        "sub" -> SpanStyle(fontSize = 0.8.em, baselineShift = BaselineShift.Subscript)
        "sup" -> SpanStyle(fontSize = 0.8.em, baselineShift = BaselineShift.Superscript)
        else -> null
    }
