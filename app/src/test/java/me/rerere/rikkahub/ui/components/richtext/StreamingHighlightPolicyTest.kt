package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式期间缓存卫生的判据（纯 JVM）：
 * 走真实 markdown 解析器，逐帧检查「块是否闭合」以及由它派生的写缓存 / 缓存输入决策。
 */
class StreamingHighlightPolicyTest {
    private val parser = MarkdownParser(GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true))

    private val doc =
        "先看这段：\n\n" +
            "```kotlin\nfun main() {\n    val answer = 42\n}\n```\n\n" +
            "结束语。"

    private fun findCodeFence(node: ASTNode): ASTNode? {
        if (node.type == MarkdownElementTypes.CODE_FENCE) return node
        node.children.forEach { child ->
            val found = findCodeFence(child)
            if (found != null) return found
        }
        return null
    }

    private fun fenceOf(markdown: String): CodeFenceFragment? {
        val node = findCodeFence(parser.buildMarkdownTreeFromString(markdown)) ?: return null
        // 正文尚未成形（例如还没有换行）时提取不到内容：此时 UI 不渲染代码块，也就不写缓存
        return extractCodeFence(node, markdown)
    }

    private fun writesCacheFor(markdown: String): Boolean =
        HighlightStreamingPolicy.writesCache(blockClosed = fenceOf(markdown)?.closed ?: false)

    @Test
    fun `closed fence is treated as finalized`() {
        val fence = checkNotNull(fenceOf(doc))

        assertTrue(fence.closed)
        assertEquals("kotlin", fence.language)
        assertEquals("fun main() {\n    val answer = 42\n}", fence.code)
        assertTrue(writesCacheFor(doc))
    }

    @Test
    fun `unclosed fence is never finalized`() {
        // 未闭合：结构判据为 false，这类块不进缓存（已知取舍：重复请求重复分词）
        val fence = checkNotNull(fenceOf("```kotlin\nfun main() {\n    val answer = 42\n}"))

        assertFalse(fence.closed)
        assertFalse(writesCacheFor("```kotlin\nfun main() {\n    val answer = 42\n}"))
        assertEquals("fun main() {\n    val answer = 42", fence.code)
    }

    @Test
    fun `streaming frames write the cache only after the closing fence arrives`() {
        val writes = (1..doc.length).map { writesCacheFor(doc.substring(0, it)) }
        val firstClosed = writes.indexOf(true)

        assertTrue("始终未出现定稿帧: $firstClosed", firstClosed >= 0)
        // 定稿之前的所有中间帧一律不写
        assertTrue(writes.subList(0, firstClosed).none { it })
        assertEquals(doc.length - firstClosed, writes.count { it })
        // 定稿之后正文不再变化 → 缓存侧只需要一条（写入点由 :highlight 的去重保证）
        val codes =
            ((firstClosed + 1)..doc.length)
                .map { fenceOf(doc.substring(0, it))?.code }
                .distinct()
        assertEquals(1, codes.size)
    }

    @Test
    fun `frames without a usable fence never write the cache`() {
        assertNull(fenceOf("`"))
        assertNull(fenceOf("``"))
        listOf("```", "```kotlin", "```kotlin\nval a = 1", "```\nval a = 1").forEach { frame ->
            assertFalse("该帧不应写缓存: $frame", writesCacheFor(frame))
        }
    }

    @Test
    fun `cache input for a closed block ignores the collapsed view`() {
        val full = (1..30).joinToString("\n") { "val v$it = $it" }
        val collapsed = full.lines().take(10).joinToString("\n")

        // 折叠 ↔ 展开：可见文本不同，但定稿块的缓存输入始终是完整代码 → 同一个键
        assertEquals(full, HighlightStreamingPolicy.cacheSource(true, full, collapsed))
        assertEquals(full, HighlightStreamingPolicy.cacheSource(true, full, full))
        // 未定稿块不写缓存，缓存输入取可见部分（只为省 CPU，不进 LRU）
        assertEquals(collapsed, HighlightStreamingPolicy.cacheSource(false, full, collapsed))
    }

    @Test
    fun `fence body extraction drops the trailing newline only`() {
        val fence = checkNotNull(fenceOf("```kotlin\nval a = 1\n```"))

        assertTrue(fence.closed)
        assertEquals("val a = 1", fence.code)
    }
}
