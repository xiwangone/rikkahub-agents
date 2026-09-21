package me.rerere.rikkahub.costguards

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [accumulateLifetimeUsage] —— 事件驱动累加：**每次请求各计一次**，口径同平台账单。
 *
 * 关键性质：
 * - 一轮里的多步工具调用（多次请求）**各计一次**，不会被合并成一次；
 * - 命中量始终收敛到输入量（命中率不可能 >100%）；
 * - `turns` 每次请求 +1。
 */
class LifetimeUsageTest {

    private fun req(input: Long, cached: Long = 0, output: Long = 0) =
        MessageUsageSnapshot(input = input, cached = cached, output = output)

    @Test
    fun `each request accumulates once`() {
        // 一轮里 3 步工具调用 = 3 次请求，各自按自己的完整输入计一次
        var total = accumulateLifetimeUsage(LifetimeUsage(), req(100, 80, 10))
        total = accumulateLifetimeUsage(total, req(200, 160, 20))
        total = accumulateLifetimeUsage(total, req(300, 250, 30))
        assertEquals(600L, total.inputTokens)
        assertEquals(490L, total.cachedTokens)
        assertEquals(60L, total.outputTokens)
        assertEquals(3, total.turns)
    }

    @Test
    fun `cached is clamped to input so hit rate never exceeds 100 percent`() {
        val total = accumulateLifetimeUsage(LifetimeUsage(), req(100, 130, 5))
        assertEquals(100L, total.inputTokens)
        assertEquals(100L, total.cachedTokens)
    }

    @Test
    fun `turns counts every request`() {
        val total = accumulateLifetimeUsage(LifetimeUsage(turns = 7), req(10, 5, 1))
        assertEquals(8, total.turns)
    }

    @Test
    fun `cost accumulates when the provider reports it`() {
        var total = accumulateLifetimeUsage(LifetimeUsage(), req(10, 5, 1))
        total = accumulateLifetimeUsage(
            total,
            MessageUsageSnapshot(input = 10, cached = 5, output = 1, cost = 0.25),
        )
        assertEquals(0.25, total.costUsd, 1e-9)
    }
}
