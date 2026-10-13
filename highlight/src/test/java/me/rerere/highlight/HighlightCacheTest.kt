package me.rerere.highlight

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高亮缓存与按行拆分。
 *
 * 缓存覆盖「滚动/重组/页面切换后回到同一代码块」的复用路径；
 * 拆分覆盖行号模式下跨行语法（块注释、模板字符串）的样式延续。
 */
class HighlightCacheTest {
    private val highlighter = CodeHighlighter()
    private val colors = HighlightTextColorPalette.Default

    @After
    fun tearDown() {
        highlightCacheCapacity = 16
    }

    @Test
    fun `annotated highlight reuses the cache for identical input`() {
        val first = highlighter.highlightAnnotated("val a = 1", "kotlin", colors)
        val second = highlighter.highlightAnnotated("val a = 1", "kotlin", colors)

        assertSame(first, second)
    }

    @Test
    fun `cache misses on different language or palette`() {
        val first = highlighter.highlightAnnotated("val a = 1", "kotlin", colors)
        val otherLanguage = highlighter.highlightAnnotated("val a = 1", "java", colors)

        assertNotSame(first, otherLanguage)
    }

    @Test
    fun `cache lookup returns null before first build and the instance after`() {
        assertNull(highlighter.cachedHighlightOrNull("val a = 1", "kotlin", colors))

        val built = highlighter.highlightAnnotated("val a = 1", "kotlin", colors)

        assertSame(built, highlighter.cachedHighlightOrNull("val a = 1", "kotlin", colors))
    }

    @Test
    fun `capacity one evicts the previous entry`() {
        highlightCacheCapacity = 1

        val first = highlighter.highlightAnnotated("val a = 1", "kotlin", colors)
        highlighter.highlightAnnotated("val b = 2", "kotlin", colors)
        val firstAgain = highlighter.highlightAnnotated("val a = 1", "kotlin", colors)

        assertNotSame(first, firstAgain)
    }

    @Test
    fun `oversized code is never cached`() {
        val code = "a".repeat(20_001)

        val first = highlighter.highlightAnnotated(code, "kotlin", colors)
        val second = highlighter.highlightAnnotated(code, "kotlin", colors)

        assertNotSame(first, second)
    }

    @Test
    fun `splitByLines keeps plain text intact`() {
        val lines = AnnotatedString("a\nbb\nccc").splitByLines()

        assertEquals(listOf("a", "bb", "ccc"), lines.map { it.text })
    }

    @Test
    fun `splitByLines matches String lines semantics`() {
        assertEquals(
            AnnotatedString("a\nbb").splitByLines().map { it.text },
            "a\nbb".lines(),
        )
        // 与 lines() 逐字节对齐：结尾换行产生空行，行间连续换行保留空行
        assertEquals(
            AnnotatedString("a\n\nb\n").splitByLines().map { it.text },
            "a\n\nb\n".lines(),
        )
        assertEquals(listOf(""), AnnotatedString("").splitByLines().map { it.text })
    }

    @Test
    fun `splitByLines carries a single-line span into its line`() {
        val annotated = buildAnnotatedString {
            append("plain ")
            withStyle(SpanStyle(color = Color.Red)) {
                append("red")
            }
            append("\nnext")
        }

        val lines = annotated.splitByLines()

        assertEquals(listOf("plain red", "next"), lines.map { it.text })
        assertTrue(lines[0].spanStyles.any { it.item.color == Color.Red })
        assertTrue(lines[1].spanStyles.isEmpty())
    }

    @Test
    fun `splitByLines carries a multi-line span into every line it covers`() {
        val red = SpanStyle(color = Color.Red)
        val annotated = buildAnnotatedString {
            append("head\n")
            withStyle(red) {
                append("/* start\nmiddle\nend */")
            }
            append("\ntail")
        }

        val lines = annotated.splitByLines()

        assertEquals(listOf("head", "/* start", "middle", "end */", "tail"), lines.map { it.text })
        assertTrue(lines[0].spanStyles.isEmpty())
        // 跨行 span 每行都带样式，且文字不错位
        lines.drop(1).dropLast(1).forEach { line ->
            assertEquals(1, line.spanStyles.size)
            assertEquals(red, line.spanStyles.single().item)
        }
        assertTrue(lines.last().spanStyles.isEmpty())
    }

    @Test
    fun `splitByLines handles spans adjacent to line boundaries`() {
        val red = SpanStyle(color = Color.Red)
        val annotated = buildAnnotatedString {
            withStyle(red) { append("ab\ncd") }
            append("\nef")
        }

        val lines = annotated.splitByLines()

        assertEquals(listOf("ab", "cd", "ef"), lines.map { it.text })
        assertEquals(listOf(red), lines[0].spanStyles.map { it.item })
        assertEquals(listOf(red), lines[1].spanStyles.map { it.item })
        assertTrue(lines[2].spanStyles.isEmpty())
    }

    @Test
    fun `splitByLines round-trips highlighted kotlin code`() {
        val code = "fun main() {\n    // note\n    val x = \"s\"\n}\n"
        val highlighted = highlighter.highlightAnnotated(code, "kotlin", colors)

        val lines = highlighted.splitByLines()

        assertEquals(code.lines(), lines.map { it.text })
        // 注释色（斜体）落在注释行上
        val commentLine = lines.single { it.text.contains("// note") }
        assertTrue(commentLine.spanStyles.any { it.item.fontStyle != null })
        assertTrue(lines.first { it.text.contains("\"s\"") }.spanStyles.isNotEmpty())
    }
}
