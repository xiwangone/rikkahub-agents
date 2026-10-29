package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 增量合并的别名行为：`coalesceStreamDeltas` 的 flush 会用 `buffer.clear()` 回收缓冲，
 * 因此 `mergeAdjacentDeltas` 绝不能把入参列表原样返回。
 *
 * 只有一条 delta 的窗口（段首 / 流结束前的最后一条）是最容易单独成批的形态，
 * 返回值一旦与入参是同一对象，那条 delta 会被 clear 掉、一个字符都发不出去。
 */
class MergeAdjacentDeltasTest {

    private fun delta(id: String, text: String) = StreamChunk.ReasoningDelta(id = id, text = text)

    @Test
    fun `single delta returns a new list so the caller can clear the source`() {
        val source = mutableListOf<StreamChunk>(delta("r1", "kot"))
        val merged = mergeAdjacentDeltas(source)

        assertNotSame("只有一条时不能返回入参本身", source, merged)
        assertEquals(1, merged.size)

        // 模拟 flush() 的下一步：清空缓冲后，已取出的内容必须还在
        source.clear()
        assertEquals("kot", (merged.single() as StreamChunk.ReasoningDelta).text)
    }

    @Test
    fun `adjacent deltas of the same id are concatenated`() {
        val merged = mergeAdjacentDeltas(listOf(delta("r1", "kot"), delta("r1", "lin")))

        assertEquals(1, merged.size)
        assertEquals("kotlin", (merged.single() as StreamChunk.ReasoningDelta).text)
    }

    @Test
    fun `deltas with different ids stay separate`() {
        val first = delta("r1", "a")
        val second = delta("r2", "b")
        val merged = mergeAdjacentDeltas(listOf(first, second))

        assertEquals(2, merged.size)
        assertSame(first, merged[0])
        assertSame(second, merged[1])
    }
}
