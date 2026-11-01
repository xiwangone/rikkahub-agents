package me.rerere.rikkahub.ui.components.richtext

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalWorkspaceId
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.toDp
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import org.koin.compose.koinInject
import android.os.SystemClock
import me.rerere.rikkahub.data.log.AppLog

private const val TAG = "Markdown"

private val flavour by lazy {
    GFMFlavourDescriptor(
        makeHttpsAutoLinks = true,
        useSafeLinks = true,
    )
}

private val parser by lazy {
    MarkdownParser(flavour)
}

private val INLINE_LATEX_REGEX = Regex("\\\\\\((.+?)\\\\\\)")
private val BLOCK_LATEX_REGEX = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
val THINKING_REGEX = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)
private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)
internal val BREAK_LINE_REGEX = Regex("(?i)<br\\s*/?>")
private val LATEX_BLOCK_LINE_BREAK_REGEX = Regex("""[ \t]*\r?\n[ \t]*""")

// 预处理markdown内容
private fun preProcess(content: String): String {
    // 先找出所有代码块的位置
    val codeBlocks = mutableListOf<IntRange>()
    CODE_BLOCK_REGEX.findAll(content).forEach { match ->
        codeBlocks.add(match.range)
    }

    // 检查位置是否在代码块内
    fun isInCodeBlock(position: Int): Boolean = codeBlocks.any { range -> position in range }

    // 替换行内公式 \( ... \) 到 $ ... $，但跳过代码块内的内容
    var result =
        INLINE_LATEX_REGEX.replace(content) { matchResult ->
            if (isInCodeBlock(matchResult.range.first)) {
                matchResult.value // 保持原样
            } else {
                "$" + matchResult.groupValues[1] + "$"
            }
        }

    // 替换块级公式 \[ ... \] 到 $$ ... $$，但跳过代码块内的内容
    result =
        BLOCK_LATEX_REGEX.replace(result) { matchResult ->
            if (isInCodeBlock(matchResult.range.first)) {
                matchResult.value // 保持原样
            } else {
                val formula =
                    matchResult.groupValues[1]
                        .trim()
                        .replace(LATEX_BLOCK_LINE_BREAK_REGEX, " ")
                "$$" + formula + "$$"
            }
        }

    return result
}

@Preview(showBackground = true)
@Composable
private fun MarkdownPreview() {
    MaterialTheme {
        CompositionLocalProvider(LocalSettings provides Settings()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MarkdownBlock(
                    content = "Hi there!",
                    modifier = Modifier.background(Color.Red),
                )
                MarkdownBlock(
                    content =
                        """
                        ### 🌍 This is Markdown Test This Markdown Test
                        1. How many roads must a man walk down
                            * the slings and arrows of outrageous fortune, Or to take arms against a sea of troubles,
                            * by opposing end them.
                                * How many times must a man look up, Before he can see the sky?
                                * How many times $ f(x) = \sum_{n=0}^{\infty} \frac{f^{(n)}(a)}{n!}(x-a)^n$
                        2. How many times must a man look up, Before he can see the sky?

                        * [ ] Before they're allowed to be free? Yes, 'n' how many times can a man turn his head
                        * [x] Before they're allowed to be free? Yes, 'n' how many times can a man turn his head

                        4. For in that sleep of death what dreams may come [citation](1)

                        This is Markdown Test, This <br/> is Markdown Test.
                        ha<br/>ha

                        ***
                        This is Markdown Test, This is Markdown Test.

                        | Name | Age | Address | Email | Job | Homepage |
                        | ---- | --- | ------- | ----- | --- | -------- |
                        | John | 25  | New York | john@example.com | Software Engineer | john.com |
                        | Jane | 26  | London   | jane@example.com | Data Scientist | jane.com |

                        ## HTML Escaping
                        This is a &gt;  test
                        """.trimIndent(),
                )
            }
        }
    }
}

private data class MarkdownParseResult(
    val preprocessed: String,
    val astTree: ASTNode,
    val hasHtml: Boolean,
)

private fun ASTNode.containsHtml(): Boolean {
    if (type == MarkdownElementTypes.HTML_BLOCK || type == MarkdownTokenTypes.HTML_TAG) return true
    return children.any { it.containsHtml() }
}

/** 首帧同步解析的文本上限（字符）：超过则先留空、交后台线程生成。
 *  8000（而非 2000）：2000 时一条普通长回复就会触发“先留空 → 下一帧补全文”的跳变，
 *  看起来就像界面刷新一下；8000 能在保留“长文不卡首帧”收益的同时，把触发面压到极少。 */
private const val SYNC_PARSE_MAX_CHARS = 8_000

/** 渲染性能日志的 TAG（I 级才会落盘，见 AppLog：D 级只留内存）。 */
private const val RENDER_PERF_TAG = "RenderPerf"

/** 渲染性能日志门槛：长度超过它、或解析耗时超过 [PERF_LOG_SLOW_MS] 才记一条（避免刷屏）。 */
private const val PERF_LOG_MIN_CHARS = 4_000
private const val PERF_LOG_SLOW_MS = 8L

private fun parseMarkdown(content: String): MarkdownParseResult {
    val preprocessed = preProcess(content)
    val astTree = parser.buildMarkdownTreeFromString(preprocessed)
    return MarkdownParseResult(preprocessed, astTree, astTree.containsHtml())
}

@Composable
fun MarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {},
) {
    // 首帧不同步解析长文：markdown 解析在组合期同步执行是长消息卡顿的来源之一（后续更新本就
    // 走后台线程）。短文本保持同步以免首帧闪烁；长文先留空，下一帧由下面的后台管线补上。
    val initialParse =
        remember {
            val started = SystemClock.uptimeMillis()
            val parsed = if (content.length <= SYNC_PARSE_MAX_CHARS) parseMarkdown(content) else null
            Triple(parsed, SystemClock.uptimeMillis() - started, content.length)
        }
    var (data, setData) = remember { mutableStateOf(initialParse.first) }
    // 渲染性能埋点：只记一次、且只在长文/慢时记（I 级会落盘 → 重启后仍可回看）
    LaunchedEffect(Unit) {
        val (parsed, costMs, len) = initialParse
        if (len > PERF_LOG_MIN_CHARS || costMs >= PERF_LOG_SLOW_MS) {
            AppLog.i(
                RENDER_PERF_TAG,
                "markdown 首帧 len=$len 同步=${parsed != null} 耗时=${costMs}ms 节点=${parsed?.astTree?.children?.size ?: 0}",
            )
        }
    }

    // 监听内容变化，重新解析AST树
    // 这里在后台线程解析AST树, 防止频繁更新的时候掉帧
    val updatedContent by rememberUpdatedState(content)
    LaunchedEffect(Unit) {
        snapshotFlow { updatedContent }
            .distinctUntilChanged()
            // 串行解析并丢弃积压的中间值：避免反复取消已在进行的解析，
            // 同时保证最新内容一定被解析（不会停在旧结果上）
            .conflate()
            .map { parseMarkdown(it) }
            .catch { exception -> AppLog.e(TAG, "MarkdownBlock: failed to parse markdown", exception) }
            .flowOn(Dispatchers.Default)
            .collect { setData(it) }
    }

    val parsed = data ?: return
    if (parsed.hasHtml) {
        MarkdownNew(
            content = content,
            modifier = modifier,
            style = style,
            onClickCitation = onClickCitation,
        )
    } else {
        ProvideTextStyle(style) {
            Column(
                modifier = modifier.padding(horizontal = 4.dp),
            ) {
                parsed.astTree.children.fastForEach { child ->
                    MarkdownNode(
                        node = child,
                        content = parsed.preprocessed,
                        onClickCitation = onClickCitation,
                    )
                }
            }
        }
    }
}

/**
 * 把 Rootfs 内的绝对路径（如 `/workspace/out.png`）换成宿主机上的图片地址。
 *
 * 只在「当前内容绑定了工作区」且路径是单斜杠开头的绝对路径时介入，其余（http/https、
 * `//` 协议相对、`file:`、data:）原样交给 Coil；解析期间返回 null，由 Coil 显示占位图。
 * 路径里的百分号编码（空格、中文等）在这里解码，同时丢掉 query/fragment。
 */
@Composable
internal fun rememberWorkspaceImageUrl(url: String): String? {
    val workspaceId = LocalWorkspaceId.current
    if (workspaceId == null || !url.startsWith("/") || url.startsWith("//")) return url
    val path = remember(url) { url.toUri().path } ?: return url
    val workspaceRepository: WorkspaceRepository = koinInject()
    val resolved by produceState<String?>(initialValue = null, workspaceId, path) {
        value = workspaceRepository.resolveAbsoluteFile(workspaceId, path)
            ?.let { Uri.fromFile(it).toString() }
            ?: url
    }
    return resolved
}

object HeaderStyle {
    private const val LINE_HEIGHT_RATIO = 1.25f

    fun fromLevel(
        level: Int,
        fontSizeRatio: Float,
    ): TextStyle {
        val fontSize =
            when (level) {
                1 -> 24.sp
                2 -> 22.sp
                3 -> 20.sp
                4 -> 18.sp
                5 -> 16.sp
                else -> 14.sp
            } * fontSizeRatio

        return TextStyle(
            fontStyle = FontStyle.Normal,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            lineHeight = fontSize * LINE_HEIGHT_RATIO,
        )
    }

    fun verticalPadding(level: Int) =
        when (level) {
            1 -> 16.dp
            2 -> 14.dp
            3 -> 12.dp
            4 -> 10.dp
            5 -> 8.dp
            else -> 6.dp
        }

    fun fromMarkdownType(
        type: IElementType,
        fontSizeRatio: Float,
    ): TextStyle =
        fromLevel(
            level =
                when (type) {
                    MarkdownElementTypes.ATX_1 -> 1
                    MarkdownElementTypes.ATX_2 -> 2
                    MarkdownElementTypes.ATX_3 -> 3
                    MarkdownElementTypes.ATX_4 -> 4
                    MarkdownElementTypes.ATX_5 -> 5
                    MarkdownElementTypes.ATX_6 -> 6
                    else -> 6
                },
            fontSizeRatio = fontSizeRatio,
        )

    fun verticalPadding(type: IElementType) =
        verticalPadding(
            level =
                when (type) {
                    MarkdownElementTypes.ATX_1 -> 1
                    MarkdownElementTypes.ATX_2 -> 2
                    MarkdownElementTypes.ATX_3 -> 3
                    MarkdownElementTypes.ATX_4 -> 4
                    MarkdownElementTypes.ATX_5 -> 5
                    else -> 6
                },
        )
}

/** Locate paired quote spans, ignoring fenced and inline code. Unclosed quotes are ignored. */
@Suppress("CyclomaticComplexMethod", "NestedBlockDepth") // 行级状态机扫描引号配对（跳代码块与行内码），拆开会割裂与渲染端的行号对齐
internal fun quotedTextRanges(source: String): List<IntRange> {
    val pairs = mapOf('"' to '"', '“' to '”', '「' to '」', '『' to '』')
    val result = mutableListOf<IntRange>()
    var fence: Char? = null
    var lineStart = 0
    while (lineStart <= source.length) {
        val lineEnd = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
        val fenceMarker = Regex("^[ \\t]{0,3}(```+|~~~+)").find(source.substring(lineStart, lineEnd))?.groupValues?.get(1)
        if (fence != null) {
            if (fenceMarker != null && fenceMarker.first() == fence) fence = null
            lineStart = lineEnd + 1
            if (lineEnd == source.length) break
            continue
        }
        if (fenceMarker != null) {
            fence = fenceMarker.first()
            lineStart = lineEnd + 1
            if (lineEnd == source.length) break
            continue
        }
        var i = lineStart
        var ticks = 0
        var quoteOpen: Char? = null
        var quoteStart = -1
        while (i < lineEnd) {
            val c = source[i]
            if (c == '`' && (i == 0 || source[i - 1] != '\\')) {
                var j = i + 1
                while (j < lineEnd && source[j] == '`') j++
                val count = j - i
                if (ticks == 0) ticks = count else if (ticks == count) ticks = 0
                i = j
                continue
            }
            if (ticks == 0 && (i == 0 || source[i - 1] != '\\')) {
                if (quoteOpen == null && c in pairs) {
                    quoteOpen = c
                    quoteStart = i
                } else if (quoteOpen != null && c == pairs[quoteOpen]) {
                    if (i > quoteStart + 1) result.add((quoteStart + 1)..(i - 1))
                    quoteOpen = null
                    quoteStart = -1
                }
            }
            i++
        }
        lineStart = lineEnd + 1
        if (lineEnd == source.length) break
    }
    return result
}

@Composable
internal fun MarkdownNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    listLevel: Int = 0,
) {
    when (node.type) {
        // 文件根节点
        MarkdownElementTypes.MARKDOWN_FILE -> {
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child,
                    content = content,
                    modifier = modifier,
                    onClickCitation = onClickCitation,
                )
            }
        }

        // 段落
        MarkdownElementTypes.PARAGRAPH -> {
            Paragraph(
                node = node,
                content = content,
                modifier = modifier,
                onClickCitation = onClickCitation,
            )
        }

        // 标题
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3, MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> {
            val style =
                HeaderStyle.fromMarkdownType(
                    type = node.type,
                    fontSizeRatio = LocalSettings.current.displaySetting.fontSizeRatio,
                )
            val headingPadding = HeaderStyle.verticalPadding(node.type)
            ProvideTextStyle(value = LocalTextStyle.current.merge(style)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.children.fastForEach { node ->
                        if (node.type == MarkdownTokenTypes.ATX_CONTENT) {
                            Paragraph(
                                node = node,
                                content = content,
                                onClickCitation = onClickCitation,
                                modifier = modifier.padding(vertical = headingPadding),
                                trim = true,
                            )
                        }
                    }
                }
            }
        }

        // 列表
        MarkdownElementTypes.UNORDERED_LIST -> {
            UnorderedListNode(
                node = node,
                content = content,
                modifier = modifier,
                onClickCitation = onClickCitation,
                level = listLevel,
            )
        }

        MarkdownElementTypes.ORDERED_LIST -> {
            OrderedListNode(
                node = node,
                content = content,
                modifier = modifier,
                onClickCitation = onClickCitation,
                level = listLevel,
            )
        }

        // Checkbox
        GFMTokenTypes.CHECK_BOX -> {
            val isChecked = node.getTextInNode(content).trim() == "[x]"
            Surface(
                shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = modifier,
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
        }

        // 引用块
        MarkdownElementTypes.BLOCK_QUOTE -> {
            val quoteHighlightEnabled = LocalSettings.current.displaySetting.enableQuoteHighlight
            val quoteHighlightColor = MaterialTheme.colorScheme.tertiary
            ProvideTextStyle(
                LocalTextStyle.current.copy(
                    fontStyle = FontStyle.Italic,
                    color = if (quoteHighlightEnabled) quoteHighlightColor else LocalTextStyle.current.color,
                ),
            ) {
                val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                Column(
                    modifier =
                        Modifier
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    color = bgColor,
                                    size = size,
                                )
                                drawRect(
                                    color = borderColor,
                                    size = Size(10f, size.height),
                                )
                            }.padding(8.dp),
                ) {
                    node.children.fastForEach { child ->
                        MarkdownNode(
                            node = child,
                            content = content,
                            onClickCitation = onClickCitation,
                        )
                    }
                }
            }
        }

        // 链接
        MarkdownElementTypes.INLINE_LINK -> {
            val linkText =
                node
                    .findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)
                    ?.findChildOfTypeRecursive(GFMTokenTypes.GFM_AUTOLINK, MarkdownTokenTypes.TEXT)
                    ?.getTextInNode(content)
                    ?: ""
            val linkDest =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            val context = LocalContext.current
            Text(
                text = linkText,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier =
                    modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, linkDest.toUri())
                        context.startActivity(intent)
                    },
            )
        }

        // 加粗和斜体
        MarkdownElementTypes.EMPH -> {
            ProvideTextStyle(TextStyle(fontStyle = FontStyle.Italic)) {
                node.children.fastForEach { child ->
                    MarkdownNode(
                        node = child,
                        content = content,
                        modifier = modifier,
                        onClickCitation = onClickCitation,
                    )
                }
            }
        }

        MarkdownElementTypes.STRONG -> {
            ProvideTextStyle(TextStyle(fontWeight = FontWeight.Bold)) {
                node.children.fastForEach { child ->
                    MarkdownNode(
                        node = child,
                        content = content,
                        modifier = modifier,
                        onClickCitation = onClickCitation,
                    )
                }
            }
        }

        // GFM 特殊元素
        GFMElementTypes.STRIKETHROUGH -> {
            Text(
                text = node.getTextInNode(content),
                textDecoration = TextDecoration.LineThrough,
                modifier = modifier,
            )
        }

        GFMElementTypes.TABLE -> {
            TableNode(node = node, content = content, modifier = modifier)
        }

        MarkdownTokenTypes.HORIZONTAL_RULE -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                thickness = 0.5.dp,
            )
        }

        // 图片
        MarkdownElementTypes.IMAGE -> {
            val altText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content) ?: ""
            val imageUrl =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 这里可以使用Coil等图片加载库加载图片
                ZoomableAsyncImage(
                    model = rememberWorkspaceImageUrl(imageUrl),
                    contentDescription = altText,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .widthIn(min = 120.dp)
                            .heightIn(min = 120.dp),
                )
            }
        }

        GFMElementTypes.INLINE_MATH -> {
            val formula = node.getTextInNode(content)
            val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
            if (enableLatexRendering) {
                MathInline(
                    formula,
                    modifier = modifier.padding(horizontal = 1.dp),
                    fontSize = LocalTextStyle.current.fontSize,
                )
            } else {
                Text(
                    text = formula,
                    fontFamily = FontFamily.Monospace,
                    modifier = modifier.padding(horizontal = 1.dp),
                )
            }
        }

        GFMElementTypes.BLOCK_MATH -> {
            val formula = node.getTextInNode(content)
            val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
            if (enableLatexRendering) {
                MathBlock(
                    formula,
                    modifier =
                        modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    fontSize = LocalTextStyle.current.fontSize,
                )
            } else {
                Text(
                    text = formula,
                    fontFamily = FontFamily.Monospace,
                    modifier =
                        modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                )
            }
        }

        MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).trim('`')
            Text(
                text = code,
                fontFamily = JetbrainsMono,
                style = LocalTextStyle.current.copy(fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0"),
                modifier = modifier,
            )
        }

        MarkdownElementTypes.CODE_BLOCK -> {
            val code = node.getTextInNode(content)
            Text(
                text = code,
                modifier = modifier,
            )
        }

        // 代码块
        MarkdownElementTypes.CODE_FENCE -> {
            val fence = extractCodeFence(node, content) ?: return

            HighlightCodeBlock(
                code = fence.code,
                language = fence.language,
                modifier =
                    Modifier
                        .padding(bottom = 4.dp)
                        .fillMaxWidth(),
                completeCodeBlock = fence.closed,
            )
        }

        MarkdownTokenTypes.TEXT -> {
            val text = node.getTextInNode(content)
            val highlightQuotes = LocalSettings.current.displaySetting.enableQuoteHighlight
            val quoteColor = MaterialTheme.colorScheme.tertiary
            val annotatedText = remember(content, node.startOffset, node.endOffset, highlightQuotes, quoteColor) {
                buildAnnotatedString {
                    append(text)
                    if (highlightQuotes) {
                        val nodeStart = node.startOffset
                        quotedTextRanges(content).forEach { range ->
                            val start = maxOf(range.first, nodeStart)
                            val endExclusive = minOf(range.last + 1, node.endOffset)
                            if (start < endExclusive) {
                                addStyle(SpanStyle(color = quoteColor), start - nodeStart, endExclusive - nodeStart)
                            }
                        }
                    }
                }
            }
            Text(text = annotatedText, modifier = modifier)
        }

        MarkdownElementTypes.HTML_BLOCK -> {
            val text = node.getTextInNode(content)
            SimpleHtmlBlock(
                html = text,
                modifier = modifier,
            )
        }

        // 其他类型的节点，递归处理子节点
        else -> {
            // 递归处理其他节点的子节点
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child,
                    content = content,
                    modifier = modifier,
                    onClickCitation = onClickCitation,
                )
            }
        }
    }
}
