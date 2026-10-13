package me.rerere.highlight

import androidx.compose.material3.LocalTextStyle
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
private const val SYNC_HIGHLIGHT_LENGTH = 1024

/** 超大代码块的硬上限（数十万字符级，再高也不值得耗 CPU）。 */
private const val MAX_HIGHLIGHT_LENGTH = 200_000

/**
 * 超过此长度的代码块不进 LRU：分词结果可能比代码本身还大，
 * 长期驻留内存不如现算现扔（超大块本来也多见于折叠/工具输出）。
 */
private const val HIGHLIGHT_CACHE_MAX_CHARS = 20_000

private const val HIGHLIGHT_CACHE_DEFAULT_ENTRIES = 16

/** 缓存容量钳制范围，与 RenderProfile.highlightCacheEntries 的钳制保持一致。 */
private const val HIGHLIGHT_CACHE_MAX_ENTRIES = 64

/**
 * 高亮结果缓存的条目容量，由 app 层按渲染档位设置
 * （见 data/perf/RenderProfile.kt 的 highlightCacheEntries）。
 *
 * 档位只改变缓存条数，不改变高亮结果。
 */
@Volatile
var highlightCacheCapacity: Int = HIGHLIGHT_CACHE_DEFAULT_ENTRIES

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

    // 跨重组周期的高亮缓存：列表滚动、折叠展开、页面切换都会让同一个代码块
    // 反复重组，remember 只在组合存活期内有效，没有这层缓存就会反复重跑分词器。
    private val cacheLock = Any()
    private val annotatedCache =
        LinkedHashMap<HighlightCacheKey, AnnotatedString>(HIGHLIGHT_CACHE_DEFAULT_ENTRIES, 0.75f, true)

    fun highlight(
        code: String,
        language: String,
    ): List<HighlightToken> {
        if (code.isEmpty()) return emptyList()

        return engine.highlight(code, language)
            ?: listOf(HighlightToken.Plain(code))
    }

    fun supports(language: String): Boolean = engine.supports(language)

    /**
     * 带 LRU 的高亮：同一段代码（同语言、同调色板）的重复请求直接复用，
     * 避免滚动/重组时反复分词。超长代码块不缓存（见 [HIGHLIGHT_CACHE_MAX_CHARS]）。
     */
    fun highlightAnnotated(
        code: String,
        language: String,
        colors: HighlightTextColorPalette,
    ): AnnotatedString {
        if (code.length > HIGHLIGHT_CACHE_MAX_CHARS) return annotate(code, language, colors)
        val key = HighlightCacheKey(language.trim().lowercase(), colors, code)
        synchronized(cacheLock) { annotatedCache[key]?.let { return it } }
        val built = annotate(code, language, colors)
        synchronized(cacheLock) {
            annotatedCache[key] = built
            val limit = highlightCacheCapacity.coerceIn(1, HIGHLIGHT_CACHE_MAX_ENTRIES)
            val excess = annotatedCache.size - limit
            if (excess > 0) {
                // accessOrder = true：排在前面的是最久未访问的
                annotatedCache.keys.take(excess).forEach { annotatedCache.remove(it) }
            }
        }
        return built
    }

    /** 缓存查询（未命中返回 null）：供异步路径在落到纯文本首帧前尝试命中。 */
    fun cachedHighlightOrNull(
        code: String,
        language: String,
        colors: HighlightTextColorPalette,
    ): AnnotatedString? {
        if (code.length > HIGHLIGHT_CACHE_MAX_CHARS) return null
        val key = HighlightCacheKey(language.trim().lowercase(), colors, code)
        synchronized(cacheLock) { return annotatedCache[key] }
    }

    private fun annotate(
        code: String,
        language: String,
        colors: HighlightTextColorPalette,
    ) = buildAnnotatedString {
        highlight(code, language).forEach { token ->
            buildHighlightText(token, colors)
        }
    }

    private data class HighlightCacheKey(
        val language: String,
        val colors: HighlightTextColorPalette,
        val code: String,
    )
}

/**
 * 组合期内的高亮状态：短代码同步出结果，长代码先显示纯文本、后台追平；
 * 命中 LRU 时长代码同样首帧即高亮（滚动回看不再闪一次纯文本）。
 */
@Composable
fun rememberHighlightedCode(
    code: String,
    language: String,
    colors: HighlightTextColorPalette,
): AnnotatedString {
    val highlighter = LocalCodeHighlighter.current
    return when (highlightStrategyFor(code.length)) {
        HighlightStrategy.Skip -> remember(code) { AnnotatedString(code) }
        HighlightStrategy.Synchronous -> remember(code, language, colors, highlighter) {
            highlighter.highlightAnnotated(code, language, colors)
        }

        HighlightStrategy.Asynchronous -> {
            val cached = remember(code, language, colors) {
                highlighter.cachedHighlightOrNull(code, language, colors)
            }
            val plain = remember(code) { AnnotatedString(code) }
            produceState(initialValue = cached ?: plain, code, language, colors, highlighter) {
                if (cached == null) {
                    // 先让显示文本始终跟随最新（流式增长的）代码，高亮结果后台追平
                    value = plain
                    value = withContext(Dispatchers.Default) {
                        highlighter.highlightAnnotated(code, language, colors)
                    }
                }
            }.value
        }
    }
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
    val annotatedString = rememberHighlightedCode(code = code, language = language, colors = colors)

    Text(
        modifier = modifier,
        text = annotatedString,
        style = LocalTextStyle.current.copy(fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0"),
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
