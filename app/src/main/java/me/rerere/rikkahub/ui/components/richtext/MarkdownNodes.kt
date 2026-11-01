package me.rerere.rikkahub.ui.components.richtext

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.rikkahub.ui.components.table.DataTable
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.toDp
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import java.io.IOException
import kotlin.time.Clock
import me.rerere.rikkahub.R

@Composable
internal fun UnorderedListNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0,
) {
    val bulletStyle =
        when (level % 3) {
            0 -> "• "
            1 -> "◦ "
            else -> "▪ "
        }

    Column(
        modifier = modifier.padding(start = (level * 8).dp),
    ) {
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = bulletStyle,
                    onClickCitation = onClickCitation,
                    level = level,
                )
            }
        }
    }
}

@Composable
internal fun OrderedListNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0,
) {
    Column(modifier.padding(start = (level * 8).dp)) {
        var index = 1
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                val numberText =
                    child.findChildOfTypeRecursive(MarkdownTokenTypes.LIST_NUMBER)?.getTextInNode(content) ?: "$index. "
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = numberText,
                    onClickCitation = onClickCitation,
                    level = level,
                )
                index++
            }
        }
    }
}

@Composable
private fun ListItemNode(
    node: ASTNode,
    content: String,
    bulletText: String,
    onClickCitation: (String) -> Unit = {},
    level: Int,
) {
    Column {
        // 分离列表项的直接内容和嵌套列表
        val (directContent, nestedLists) = separateContentAndLists(node)
        // directContent 渲染处理
        if (directContent.isNotEmpty()) {
            Row {
                Text(
                    text = bulletText,
                    modifier = Modifier.alignByBaseline(),
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    directContent.fastForEach { contentChild ->
                        MarkdownNode(
                            node = contentChild,
                            content = content,
                            onClickCitation = onClickCitation,
                            listLevel = level,
                        )
                    }
                }
            }
        }
        // nestedLists 渲染处理
        nestedLists.fastForEach { nestedList ->
            MarkdownNode(
                node = nestedList,
                content = content,
                onClickCitation = onClickCitation,
                listLevel = level + 1, // 增加层级
            )
        }
    }
}

// 分离列表项的直接内容和嵌套列表
private fun separateContentAndLists(listItemNode: ASTNode): Pair<List<ASTNode>, List<ASTNode>> {
    val directContent = mutableListOf<ASTNode>()
    val nestedLists = mutableListOf<ASTNode>()
    listItemNode.children.fastForEach { child ->
        when (child.type) {
            MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> {
                nestedLists.add(child)
            }

            else -> {
                directContent.add(child)
            }
        }
    }
    return directContent to nestedLists
}

@Composable
internal fun Paragraph(
    node: ASTNode,
    content: String,
    trim: Boolean = false,
    onClickCitation: (String) -> Unit = {},
    modifier: Modifier,
) {
    // dumpAst(node, content)
    if (node.findChildOfTypeRecursive(MarkdownElementTypes.IMAGE, GFMElementTypes.BLOCK_MATH) != null) {
        FlowRow(modifier = modifier) {
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child,
                    content = content,
                    onClickCitation = onClickCitation,
                )
            }
        }
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    val inlineContents =
        remember {
            mutableStateMapOf<String, InlineTextContent>()
        }
    val hasInlineMath =
        remember(node) {
            node.findChildOfTypeRecursive(GFMElementTypes.INLINE_MATH) != null
        }
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering

    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current
    // Per-paragraph direction: an Arabic/Hebrew paragraph renders RTL, a Latin
    // one stays LTR, even within the same message. Derived from the first strong
    // directional character of the paragraph's plain text.
    val textDirection =
        remember(node, content) {
            resolveTextDirection(node.getTextInNode(content))
        }
    val latexColorArgb = LocalContentColor.current.toArgb()
    FlowRow(
        modifier =
            modifier.then(
                if (node.nextSibling() != null) {
                    Modifier.padding(bottom = LocalTextStyle.current.fontSize.toDp())
                } else {
                    Modifier
                },
            ),
    ) {
        val annotatedString =
            remember(content, enableLatexRendering, latexColorArgb) {
                buildAnnotatedString {
                    node.children.fastForEach { child ->
                        appendMarkdownNodeWithEnv(
                            node = child,
                            trim = trim,
                            env = MarkdownInlineEnv(
                                content = content,
                                inlineContents = inlineContents,
                                colorScheme = colorScheme,
                                density = density,
                                style = textStyle,
                                enableLatexRendering = enableLatexRendering,
                                latexColorArgb = latexColorArgb,
                                onClickCitation = onClickCitation,
                            ),
                        )
                    }
                }
            }
        Text(
            text = annotatedString,
            modifier = Modifier,
            inlineContent = inlineContents,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style =
                LocalTextStyle.current.copy(
                    lineHeight =
                        if (hasInlineMath &&
                            enableLatexRendering
                        ) {
                            TextUnit.Unspecified
                        } else {
                            LocalTextStyle.current.lineHeight
                        },
                    textDirection = textDirection,
                ),
        )
    }
}

@Composable
internal fun TableNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
) {
    // 提取表格的标题行和数据行
    val headerNode = node.children.find { it.type == GFMElementTypes.HEADER }
    val rowNodes = node.children.filter { it.type == GFMElementTypes.ROW }

    // 计算列数（从标题行获取）
    val columnCount = headerNode?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0

    // 检查是否有足够的列来显示表格
    if (columnCount == 0) return

    // 提取表头单元格文本
    val headerCells =
        headerNode?.children?.filter { it.type == GFMTokenTypes.CELL }?.map { it.getTextInNode(content).trim() }
            ?: emptyList()

    // 提取所有行的数据
    val rows =
        rowNodes.map { rowNode ->
            rowNode.children.filter { it.type == GFMTokenTypes.CELL }.map { it.getTextInNode(content).trim() }
        }

    // 创建表头composable列表
    val headers =
        List(columnCount) { columnIndex ->
            @Composable {
                MarkdownBlock(
                    content = if (columnIndex < headerCells.size) headerCells[columnIndex] else "",
                )
            }
        }

    // 创建行数据composable列表
    val rowComposables =
        rows.map { rowData ->
            List(columnCount) { columnIndex ->
                @Composable {
                    MarkdownBlock(
                        content = if (columnIndex < rowData.size) rowData[columnIndex] else "",
                    )
                }
            }
        }

    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 表格原始markdown文本（用于复制）和CSV内容（用于下载）
    val tableMarkdown = remember(node, content) { node.getTextInNode(content).trim() }
    val tableCsv = remember(headerCells, rows) { buildTableCsv(headerCells, rows) }

    val createDocumentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            uri?.let {
                scope.launch {
                    try {
                        context.contentResolver.openOutputStream(it)?.use { outputStream ->
                            outputStream.write(tableCsv.toByteArray())
                        }
                    } catch (e: IOException) {
                        // openOutputStream/write 仅声明 IOException；收窄后 CancellationException 不再被吞
                        e.printStackTrace()
                    }
                }
            }
        }

    // 渲染表格卡片（工具栏 + 表格）
    Column(
        modifier =
            modifier
                .padding(vertical = 8.dp)
                .clip(MaterialTheme.shapes.large)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.richtext_table_title),
                fontSize = 12.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val iconSize = 16.dp
                val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

                Icon(
                    imageVector = HugeIcons.Copy01,
                    contentDescription = stringResource(R.string.accessibility_copy_table),
                    tint = iconTint,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .onClick {
                                scope.launch {
                                    clipboardManager.setClipEntry(
                                        ClipEntry(ClipData.newPlainText("table", tableMarkdown)),
                                    )
                                }
                            }.padding(4.dp)
                            .size(iconSize),
                )

                Icon(
                    imageVector = HugeIcons.Download04,
                    contentDescription = stringResource(R.string.accessibility_download_table),
                    tint = iconTint,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .onClick {
                                createDocumentLauncher.launch(
                                    "table_${
                                        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                                    }.csv",
                                )
                            }.padding(4.dp)
                            .size(iconSize),
                )
            }
        }
        DataTable(
            headers = headers,
            rows = rowComposables,
            columnMinWidths = List(columnCount) { 80.dp },
            columnMaxWidths = List(columnCount) { 200.dp },
            outerBorder = null,
            shape = RectangleShape,
        )
    }
}

// 构建CSV内容，对包含逗号/引号/换行的字段进行转义
private fun buildTableCsv(
    headerCells: List<String>,
    rows: List<List<String>>,
): String {
    fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    return buildString {
        appendLine(headerCells.joinToString(",") { escape(it) })
        rows.forEach { row ->
            appendLine(row.joinToString(",") { escape(it) })
        }
    }
}

private data class MarkdownInlineEnv(
    val content: String,
    val inlineContents: MutableMap<String, InlineTextContent>,
    val colorScheme: ColorScheme,
    val density: Density,
    val style: TextStyle,
    val enableLatexRendering: Boolean = true,
    val latexColorArgb: Int = 0,
    val onClickCitation: (String) -> Unit = {},
)

private fun AnnotatedString.Builder.appendMarkdownNodeWithEnv(
    node: ASTNode,
    trim: Boolean = false,
    env: MarkdownInlineEnv,
) {
    val content = env.content
    when {
        node.type == MarkdownTokenTypes.BLOCK_QUOTE -> {}

        node.type == GFMTokenTypes.GFM_AUTOLINK -> {
            val link = node.getTextInNode(content)
            withLink(LinkAnnotation.Url(link)) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(link) }
            }
        }

        node is LeafASTNode -> {
            val text = (if (trim) node.getTextInNode(content).trim() else node.getTextInNode(content))
                .replace(BREAK_LINE_REGEX, "\n")
            append(text = text)
        }

        node.type == MarkdownElementTypes.EMPH -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                node.children.trim(MarkdownTokenTypes.EMPH, 1).fastForEach {
                    appendMarkdownNodeWithEnv(it, env = env)
                }
            }
        }

        node.type == MarkdownElementTypes.STRONG -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                node.children.trim(MarkdownTokenTypes.EMPH, 2).fastForEach {
                    appendMarkdownNodeWithEnv(it, env = env)
                }
            }
        }

        node.type == GFMElementTypes.STRIKETHROUGH -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                node.children.trim(GFMTokenTypes.TILDE, 2).fastForEach {
                    appendMarkdownNodeWithEnv(it, env = env)
                }
            }
        }

        node.type == MarkdownElementTypes.INLINE_LINK -> appendMarkdownInlineLink(node, env)

        node.type == MarkdownElementTypes.AUTOLINK -> {
            val links = node.children.trim(MarkdownTokenTypes.LT, 1).trim(MarkdownTokenTypes.GT, 1)
            links.fastForEach { link ->
                val url = link.getTextInNode(content)
                withLink(LinkAnnotation.Url(url)) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(url) }
                }
            }
        }

        node.type == MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).trim('`')
            withStyle(
                SpanStyle(
                    fontFamily = JetbrainsMono,
                    fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0",
                    fontSize = 0.9.em,
                    color = env.colorScheme.primary,
                ),
            ) {
                append(' ')
                append(code)
                append(' ')
            }
        }

        node.type == GFMElementTypes.INLINE_MATH -> appendMarkdownInlineMath(node, env)

        node.type == MarkdownElementTypes.IMAGE -> appendMarkdownInlineImage(node, env)

        else -> {
            node.children.fastForEach {
                appendMarkdownNodeWithEnv(it, trim = false, env = env)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdownInlineLink(
    node: ASTNode,
    env: MarkdownInlineEnv,
) {
    val content = env.content
    val linkDest =
        node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
    val linkText =
        node
            .findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)
            ?.getTextInNode(content)
            ?.trim { it == '[' || it == ']' } ?: linkDest
    if (linkText.startsWith("citation,")) {
        val domain = linkText.substringAfter("citation,")
        val id = linkDest
        if (id.length == 6) {
            env.inlineContents.putIfAbsent(
                "citation:$linkDest",
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
                                    .clickable { env.onClickCitation(id.trim()) }
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(env.colorScheme.tertiaryContainer.copy(0.2f)),
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
                                        color = env.colorScheme.onTertiaryContainer,
                                        fontWeight = FontWeight.Thin,
                                    ),
                            )
                        }
                    },
                ),
            )
            appendInlineContent("citation:$linkDest")
        }
    } else {
        withLink(LinkAnnotation.Url(linkDest)) {
            withStyle(
                SpanStyle(
                    color = env.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
            ) {
                append(linkText)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdownInlineMath(
    node: ASTNode,
    env: MarkdownInlineEnv,
) {
    val formula = node.getTextInNode(env.content)
    if (!env.enableLatexRendering) {
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.95.em)) {
            append(formula)
        }
        return
    }
    val fontSizePx = with(env.density) { env.style.fontSize.toPx() }
    val drawables =
        splitLatex(
            latex = formula,
            maxWidthPx = fontSizePx * 2,
            fontSize = fontSizePx,
            color = env.latexColorArgb,
        )
    if (drawables.isEmpty()) {
        appendInlineContent(formula, "[Latex]")
        val (width, height) =
            with(env.density) {
                assumeLatexSize(latex = formula, fontSize = fontSizePx).let {
                    it.width().toSp() to it.height().toSp()
                }
            }
        env.inlineContents.putIfAbsent(
            formula,
            InlineTextContent(
                placeholder =
                    Placeholder(
                        width = width,
                        height = height,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                children = { MathInline(latex = formula, modifier = Modifier) },
            ),
        )
    } else {
        drawables.forEachIndexed { index, drawable ->
            if (index > 0) append('\u200B')
            val key = "latex:${formula.hashCode()}:$index"
            appendInlineContent(key, "[Latex]")
            val (width, height) =
                with(env.density) {
                    drawable.bounds.width().toSp() to drawable.bounds.height().toSp()
                }
            env.inlineContents.putIfAbsent(
                key,
                InlineTextContent(
                    placeholder =
                        Placeholder(
                            width = width,
                            height = height,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        ),
                    children = { LatexDrawable(drawable = drawable) },
                ),
            )
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdownInlineImage(
    node: ASTNode,
    env: MarkdownInlineEnv,
) {
    val content = env.content
    val linkNode = node.findChildOfTypeRecursive(MarkdownElementTypes.INLINE_LINK) ?: return
    val imageUrl =
        linkNode.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content)
            ?: return
    appendInlineContent(imageUrl, imageUrl)
    env.inlineContents.putIfAbsent(
        imageUrl,
        InlineTextContent(
            placeholder =
                Placeholder(
                    width = 60.sp,
                    height = 60.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Bottom,
                ),
            children = {
                ZoomableAsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        ),
    )
}

internal fun ASTNode.getTextInNode(text: String): String = text.substring(startOffset, endOffset)

private fun ASTNode.nextSibling(): ASTNode? {
    val brother = this.parent?.children ?: return null
    for (i in brother.indices) {
        if (brother[i] == this) {
            if (i + 1 < brother.size) {
                return brother[i + 1]
            }
        }
    }
    return null
}

internal fun ASTNode.findChildOfTypeRecursive(vararg types: IElementType): ASTNode? {
    if (this.type in types) return this
    for (child in children) {
        val result = child.findChildOfTypeRecursive(*types)
        if (result != null) return result
    }
    return null
}

private fun List<ASTNode>.trim(
    type: IElementType,
    size: Int,
): List<ASTNode> {
    if (this.isEmpty() || size <= 0) return this
    var start = 0
    var end = this.size
    var trimmed = 0
    while (start < end && trimmed < size && this[start].type == type) {
        start++
        trimmed++
    }
    trimmed = 0
    while (end > start && trimmed < size && this[end - 1].type == type) {
        end--
        trimmed++
    }
    return this.subList(start, end)
}
