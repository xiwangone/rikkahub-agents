package me.rerere.highlight

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式期间的缓存卫生：中间帧只读不写、定稿结果不被挤出、折叠切换不产生新键。
 *
 * 计数通过 [CodeHighlighter.cacheStats] / [CodeHighlighter.cacheEntryCount] 观察，不依赖日志。
 */
class StreamingCacheHygieneTest {
    private val highlighter = CodeHighlighter()
    private val colors = HighlightTextColorPalette.Default

    @After
    fun tearDown() {
        highlightCacheCapacity = 16
    }

    @Test
    fun `streaming frames without cache writes leave the cache empty`() {
        val final = "fun main() {\n    val answer = 42\n}\n"

        // 逐字符的中间帧（未闭合，判据为不写）
        (1..final.length).forEach { size ->
            highlighter.highlightAnnotated(final.substring(0, size), "kotlin", colors, cacheWrite = false)
        }

        val stats = highlighter.cacheStats()
        assertEquals(0L, stats.writes)
        assertEquals(0, highlighter.cacheEntryCount())
        assertNull(highlighter.cachedHighlightOrNull(final, "kotlin", colors))
        // 每一帧确实重算了（没有偷用缓存），这正是「不写」的代价
        assertEquals(final.length.toLong(), stats.builds)
    }

    @Test
    fun `finalized block keeps its entry after many intermediate frames`() {
        highlightCacheCapacity = 16
        val finalized = "fun main() {\n    println(\"hi\")\n}\n"

        // 远超容量的中间帧灌不进缓存，定稿帧因此仍能占位并在后续复用
        repeat(60) { index ->
            highlighter.highlightAnnotated("val x$index = $index", "kotlin", colors, cacheWrite = false)
        }
        val first = highlighter.highlightAnnotated(finalized, "kotlin", colors, cacheWrite = true)
        val buildsAtFinalize = highlighter.cacheStats().builds

        val second = highlighter.highlightAnnotated(finalized, "kotlin", colors, cacheWrite = true)

        assertSame(first, second)
        assertEquals(1, highlighter.cacheEntryCount())
        assertEquals(1L, highlighter.cacheStats().writes)
        // 复用不再重算
        assertEquals(buildsAtFinalize, highlighter.cacheStats().builds)
        assertTrue(highlighter.cacheStats().hits >= 1L)
    }

    @Test
    fun `closure of a block whose content did not change still warms the cache`() {
        // 闭合围栏补在正文之后：提取出的代码内容与最后一帧中间帧完全一致
        val code = "val a = 1"

        val intermediate = highlighter.highlightAnnotated(code, "kotlin", colors, cacheWrite = false)
        assertEquals(0, highlighter.cacheEntryCount())

        val finalized = highlighter.highlightAnnotated(code, "kotlin", colors, cacheWrite = true)

        assertEquals(1, highlighter.cacheEntryCount())
        assertSame(finalized, highlighter.highlightAnnotated(code, "kotlin", colors, cacheWrite = true))
        // 定稿写入的实例与中间帧那次构建等价（内容相同 → 视觉一致）
        assertEquals(intermediate.text, finalized.text)
        assertEquals(2L, highlighter.cacheStats().builds)
    }

    @Test
    fun `collapsed and expanded views of one finalized block share a single entry`() {
        val code = (1..30).joinToString("\n") { "val v$it = $it" }
        val collapsedText = code.lines().take(10).joinToString("\n")

        val full = highlighter.highlightAnnotated(code, "kotlin", colors, cacheWrite = true)
        val shownCollapsed = full.takeLines(10)
        val shownExpanded = full.takeLines(30)

        // 折叠显示由「完整结果切前 N 行」得到，与按可见文本再请求一次高亮等价
        assertEquals(collapsedText, shownCollapsed.text)
        assertEquals(code, shownExpanded.text)
        // 折叠 ↔ 展开来回切换不新增条目、也不触发重算
        repeat(5) {
            assertSame(full, highlighter.highlightAnnotated(code, "kotlin", colors, cacheWrite = true))
        }
        assertEquals(1, highlighter.cacheEntryCount())
        assertEquals(1L, highlighter.cacheStats().writes)
        assertEquals(1L, highlighter.cacheStats().builds)
    }

    @Test
    fun `unclosed block rebuilds on every request`() {
        val code = "fun main() {\n    val a = 1"

        val first = highlighter.highlightAnnotated(code, "kotlin", colors, cacheWrite = false)
        val second = highlighter.highlightAnnotated(code, "kotlin", colors, cacheWrite = false)

        // 已知代价：未闭合块不进缓存，重复请求就重复分词
        assertNotSame(first, second)
        assertEquals(2L, highlighter.cacheStats().builds)
        assertEquals(0L, highlighter.cacheStats().writes)
    }

    @Test
    fun `takeLines keeps spans and line semantics`() {
        val code = "fun main() {\n    // note\n    println(1)\n}"
        val highlighted = highlighter.highlightAnnotated(code, "kotlin", colors, cacheWrite = true)

        val lines = highlighted.takeLines(2)

        assertEquals("fun main() {\n    // note", lines.text)
        assertTrue(lines.spanStyles.isNotEmpty())
        // 行数超出全文时原样返回，不额外拷贝
        assertSame(highlighted, highlighted.takeLines(4))
        assertSame(highlighted, highlighted.takeLines(100))
        assertEquals("", highlighted.takeLines(0).text)
    }
}
