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
import java.util.concurrent.atomic.AtomicLong

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
    private val hits = AtomicLong()
    private val writes = AtomicLong()
    private val builds = AtomicLong()

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
     * 本高亮器实际注册的语言名集合（语法名 + 全部别名，小写）。
     *
     * 供 HTML 输出侧（WebView 预览页）对齐语言覆盖面：只有这里列出的语言才可能有色，
     * 其余两条路径一律降级为纯文本，不做"一边有色、一边没有"。
     */
    fun supportedLanguages(): Set<String> = engine.supportedLanguages()

    /**
     * 带 LRU 的高亮：同一段代码（同语言、同调色板）的重复请求直接复用，
     * 避免滚动/重组时反复分词。超长代码块不缓存（见 [HIGHLIGHT_CACHE_MAX_CHARS]）。
     *
     * [cacheWrite] 为 false 时只读不写：流式中间帧每次都不同，写进去既无复用价值，
     * 又会把有限容量的 LRU 灌满，让真正会被回看的定稿内容反而失去缓存。
     */
    fun highlightAnnotated(
        code: String,
        language: String,
        colors: HighlightTextColorPalette,
        cacheWrite: Boolean = true,
    ): AnnotatedString {
        if (code.length > HIGHLIGHT_CACHE_MAX_CHARS) {
            builds.incrementAndGet()
            return annotate(code, language, colors)
        }
        val key = HighlightCacheKey(language.trim().lowercase(), colors, code)
        synchronized(cacheLock) {
            annotatedCache[key]?.let {
                hits.incrementAndGet()
                return it
            }
        }
        val built = annotate(code, language, colors)
        builds.incrementAndGet()
        if (!cacheWrite) return built
        synchronized(cacheLock) {
            // 双检：并发构建同一 key 时以先到者为准，避免后写的等价结果挤掉访问计数
            annotatedCache[key]?.let {
                hits.incrementAndGet()
                return it
            }
            annotatedCache[key] = built
            val limit = highlightCacheCapacity.coerceIn(1, HIGHLIGHT_CACHE_MAX_ENTRIES)
            val excess = annotatedCache.size - limit
            if (excess > 0) {
                // accessOrder = true：排在前面的是最久未访问的
                annotatedCache.keys.take(excess).forEach { annotatedCache.remove(it) }
            }
            writes.incrementAndGet()
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
        synchronized(cacheLock) {
            val cached = annotatedCache[key]
            if (cached != null) hits.incrementAndGet()
            return cached
        }
    }

    /**
     * 观测计数快照（命中 / 写入 / 实际分词次数）：只为断言缓存行为而存在，
     * 不参与任何渲染决策，也不落日志。
     */
    internal fun cacheStats(): HighlightCacheStats =
        HighlightCacheStats(
            hits = hits.get(),
            writes = writes.get(),
            builds = builds.get(),
        )

    /** 当前缓存条目数：用于断言中间帧没有把 LRU 灌满。 */
    internal fun cacheEntryCount(): Int = synchronized(cacheLock) { annotatedCache.size }

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

/** [CodeHighlighter.cacheStats] 的快照类型。 */
internal data class HighlightCacheStats(
    val hits: Long,
    val writes: Long,
    val builds: Long,
)

/**
 * 组合期内的高亮状态：短代码同步出结果，长代码先显示纯文本、后台追平；
 * 命中 LRU 时长代码同样首帧即高亮（滚动回看不再闪一次纯文本）。
 *
 * [cacheWrite] 为 false 时读缓存照旧、但不写入（流式中间帧用），详见 [CodeHighlighter.highlightAnnotated]。
 * 它同时是重组键之一：闭合围栏可能只补在正文之后（提取出的代码内容不变），若不作为键，
 * 定稿那一帧就不会再走一次写入。
 */
@Composable
fun rememberHighlightedCode(
    code: String,
    language: String,
    colors: HighlightTextColorPalette,
    cacheWrite: Boolean = true,
): AnnotatedString {
    val highlighter = LocalCodeHighlighter.current
    return when (highlightStrategyFor(code.length)) {
        HighlightStrategy.Skip -> remember(code) { AnnotatedString(code) }
        HighlightStrategy.Synchronous -> remember(code, language, colors, highlighter, cacheWrite) {
            highlighter.highlightAnnotated(code, language, colors, cacheWrite)
        }

        HighlightStrategy.Asynchronous -> {
            val cached = remember(code, language, colors) {
                highlighter.cachedHighlightOrNull(code, language, colors)
            }
            val plain = remember(code) { AnnotatedString(code) }
            produceState(initialValue = cached ?: plain, code, language, colors, highlighter, cacheWrite) {
                if (cached == null) {
                    // 先让显示文本始终跟随最新（流式增长的）代码，高亮结果后台追平
                    value = plain
                    value = withContext(Dispatchers.Default) {
                        highlighter.highlightAnnotated(code, language, colors, cacheWrite)
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
    cacheWrite: Boolean = true,
) {
    val annotatedString = rememberHighlightedCode(code = code, language = language, colors = colors, cacheWrite = cacheWrite)

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
