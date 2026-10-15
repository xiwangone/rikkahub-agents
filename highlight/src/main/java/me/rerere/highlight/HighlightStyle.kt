package me.rerere.highlight

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

fun AnnotatedString.Builder.buildHighlightText(
    token: HighlightToken,
    colors: HighlightTextColorPalette,
) {
    when (token) {
        is HighlightToken.Plain -> {
            append(token.content)
        }

        is HighlightToken.Styled -> {
            withStyle(highlightScopeStyle(token.type, colors)) {
                append(token.content)
            }
        }
    }
}

/**
 * 按行拆分高亮结果，并保留每行原有的 span 样式。
 *
 * 行号模式需要逐行渲染以对齐行号列，但逐行独立高亮会丢失跨行语法
 * （块注释、模板字符串）的延续——第二行起就没有注释色了。
 * 因此先整段高亮、再按行拆分，样式随行走。
 *
 * 换行语义与 [String.lines] 严格一致（含结尾换行产生的空行）：
 * 调用方的行号列按 `code.lines()` 计数，两边行数必须对齐。
 */
@Suppress("NestedBlockDepth") // 行切分与跳行 span 重入本就是两层循环，拆开反而削弱与 lines() 的行号对齐可读性
fun AnnotatedString.splitByLines(): List<AnnotatedString> {
    val text = this.text
    if (text.isEmpty()) return listOf(AnnotatedString(""))
    val lineTexts = text.split('\n')
    val spans = spanStyles.sortedBy { it.start }
    val result = ArrayList<AnnotatedString>(lineTexts.size)
    var cursor = 0
    var spanIndex = 0
    for (lineText in lineTexts) {
        val lineEnd = cursor + lineText.length
        val builder = AnnotatedString.Builder()
        var pos = cursor
        // 与本行相交的 span 依次落入；跨行的 span 在后续行重入（spanIndex 不前移）
        while (spanIndex < spans.size && spans[spanIndex].start < lineEnd) {
            val span = spans[spanIndex]
            val start = maxOf(span.start, cursor)
            val end = minOf(span.end, lineEnd)
            if (end > start) {
                if (start > pos) builder.append(text, pos, start)
                builder.withStyle(span.item) { append(text, start, end) }
                pos = end
            }
            if (span.end <= lineEnd) spanIndex++ else break
        }
        if (pos < lineEnd) builder.append(text, pos, lineEnd)
        result.add(builder.toAnnotatedString())
        cursor = lineEnd + 1
    }
    return result
}

data class HighlightTextColorPalette(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val function: Color,
    val operator: Color,
    val punctuation: Color,
    val className: Color,
    val property: Color,
    val boolean: Color,
    val variable: Color,
    val tag: Color,
    val attrName: Color,
    val attrValue: Color,
    val fallback: Color,
) {
    companion object {
        val Default =
            HighlightTextColorPalette(
                keyword = Color(0xFFC678DD),
                string = Color(0xFF98C379),
                number = Color(0xFFD19A66),
                comment = Color(0xFF5C6370),
                function = Color(0xFF61AFEF),
                operator = Color(0xFF56B6C2),
                punctuation = Color(0xFFABB2BF),
                className = Color(0xFFE5C07B),
                property = Color(0xFFE06C75),
                boolean = Color(0xFFD19A66),
                variable = Color(0xFFE06C75),
                tag = Color(0xFFE06C75),
                attrName = Color(0xFFD19A66),
                attrValue = Color(0xFF98C379),
                fallback = Color(0xFFABB2BF),
            )
    }
}

/**
 * Resolves a token scope to a span style.
 *
 * Scopes follow the `highlight.js` vocabulary and can be tiered, such as `title.function` or
 * `char.escape`. An unknown tier falls back to its parent scope, which is what the upstream CSS
 * themes do by emitting one class per tier.
 *
 * 对外可见：Compose 正文块与 WebView 预览页（HTML 输出）共用这一处解析，
 * 两条路径的颜色只由 [HighlightTextColorPalette] 决定，不在别处再映射一遍。
 */
fun highlightScopeStyle(
    type: String,
    colors: HighlightTextColorPalette,
): SpanStyle {
    var scope = type
    while (true) {
        styleForScope(scope, colors)?.let { return it }
        val separator = scope.lastIndexOf('.')
        if (separator == -1) return SpanStyle(color = colors.fallback)
        scope = scope.substring(0, separator)
    }
}

/**
 * Colours follow the Atom One theme, the same palette the upstream stylesheet of that name uses,
 * so several scopes deliberately share a slot.
 */
private fun styleForScope(
    scope: String,
    colors: HighlightTextColorPalette,
): SpanStyle? =
    when (scope) {
        "comment", "quote" -> {
            SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
        }

        "keyword", "doctag", "formula" -> {
            SpanStyle(color = colors.keyword)
        }

        "string", "regexp", "addition" -> {
            SpanStyle(color = colors.string)
        }

        "attribute" -> {
            SpanStyle(color = colors.attrValue)
        }

        "attr", "template-variable" -> {
            SpanStyle(color = colors.attrName)
        }

        "number", "type", "selector-class", "selector-attr", "selector-pseudo" -> {
            SpanStyle(color = colors.number)
        }

        "literal", "char", "operator" -> {
            SpanStyle(color = colors.operator)
        }

        "built_in" -> {
            SpanStyle(color = colors.className)
        }

        "title", "function", "symbol", "bullet", "link", "meta", "selector-id" -> {
            SpanStyle(color = colors.function)
        }

        "section", "name", "selector-tag", "deletion", "subst", "property" -> {
            SpanStyle(color = colors.property)
        }

        "tag" -> {
            SpanStyle(color = colors.tag)
        }

        "variable" -> {
            SpanStyle(color = colors.variable)
        }

        "punctuation", "params" -> {
            SpanStyle(color = colors.punctuation)
        }

        "emphasis" -> {
            SpanStyle(color = colors.fallback, fontStyle = FontStyle.Italic)
        }

        "strong" -> {
            SpanStyle(color = colors.fallback, fontWeight = FontWeight.Bold)
        }

        else -> {
            null
        }
    }
