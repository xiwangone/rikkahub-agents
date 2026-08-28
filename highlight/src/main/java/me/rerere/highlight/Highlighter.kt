package me.rerere.highlight

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import me.rerere.highlight.core.HighlightEngine
import me.rerere.highlight.languages.builtinLanguages

/** 低于此长度同步高亮（开销小，不必切线程）。 */
private const val SYNC_HIGHLIGHT_LENGTH = 4096

/** 超大代码块的硬上限（数十万字符级，再高也不值得耗 CPU）。 */
private const val MAX_HIGHLIGHT_LENGTH = 200_000

/** 代码块高亮策略：同步 / 异步 / 跳过。 */
internal sealed interface HighlightStrategy {
    data object Synchronous : HighlightStrategy
    data object Asynchronous : HighlightStrategy
    data object Skip : HighlightStrategy
}

internal fun highlightStrategyFor(codeLength: Int): HighlightStrategy = when {
    codeLength > MAX_HIGHLIGHT_LENGTH -> HighlightStrategy.Skip
    codeLength > SYNC_HIGHLIGHT_LENGTH -> HighlightStrategy.Asynchronous
    else -> HighlightStrategy.Synchronous
}

val LocalCodeHighlighter = staticCompositionLocalOf { CodeHighlighter() }

/**
 * A pure Kotlin syntax highlighter.
 *
 * Grammars are ported from highlight.js 11.11.1 and run on [HighlightEngine], a port of its mode
 * stack parser. An unsupported language is returned unhighlighted.
 */
class CodeHighlighter {
    private val engine = HighlightEngine(builtinLanguages())

    fun highlight(
        code: String,
        language: String,
    ): List<HighlightToken> {
        if (code.isEmpty()) return emptyList()

        return engine.highlight(code, language)
            ?: listOf(HighlightToken.Plain(code))
    }

    fun supports(language: String): Boolean = engine.supports(language)
}

@Composable
fun CodeHighlightText(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    colors: HighlightTextColorPalette = HighlightTextColorPalette.Default,
    fontSize: TextUnit = 12.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    fontStyle: FontStyle = FontStyle.Normal,
    fontWeight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val highlighter = LocalCodeHighlighter.current

    fun highlight() = buildAnnotatedString {
        highlighter.highlight(code, language).forEach { token ->
            buildHighlightText(token, colors)
        }
    }

    val annotatedString = when (highlightStrategyFor(code.length)) {
        HighlightStrategy.Skip -> remember(code) { AnnotatedString(code) }
        HighlightStrategy.Synchronous -> remember(code, language, colors, highlighter) { highlight() }
        HighlightStrategy.Asynchronous -> {
            val plain = remember(code) { AnnotatedString(code) }
            produceState(initialValue = plain, code, language, colors, highlighter) {
                // 先让显示文本始终跟随最新（流式增长的）代码，高亮结果后台追平
                value = plain
                value = withContext(Dispatchers.Default) { highlight() }
            }.value
        }
    }

    Text(
        modifier = modifier,
        text = annotatedString,
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}
