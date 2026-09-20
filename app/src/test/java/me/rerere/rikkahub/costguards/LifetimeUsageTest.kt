package me.rerere.rikkahub.costguards

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [settleLifetimeUsage] / [baselineLifetimeUsage] / [usageFingerprint].
 *
 * 口径：累计 = 「应用实际收到的 usage 之和」——每一次请求按它的**完整输入**各计一次，对齐平台账单。
 *
 * 关键性质：
 * - 首次见到会话 → 当前用量整体登记为基线（不漏计历史，也不重复计入）
 * - 同一条消息上的多步请求（工具调用循环）→ **每一步都计入**（usage 被覆盖式写入，指纹不同）
 * - 同一次请求被重复观察（流式多次上报同一 usage）→ 指纹相同 → 去重
 * - 消息被压缩 / 删除 → 已计入部分保留，累计不缩水
 * - 命中量始终收敛到输入量（命中率不可能 >100%）
 */
class LifetimeUsageTest {

    private fun snap(input: Long, cached: Long = 0, output: Long = 0) =
        MessageUsageSnapshot(input = input, cached = cached, output = output)

    private fun seenOf(vararg entries: Pair<String, MessageUsageSnapshot>): MutableSet<String> =
        entries.mapTo(mutableSetOf()) { (id, s) -> usageFingerprint(id, s) }

    @Test
    fun `first sight counts every message once`() {
        val baseline = mapOf("a" to snap(100, 80, 10), "b" to snap(200, 150, 20))
        val total = baselineLifetimeUsage(baseline)
        assertEquals(300L, total.inputTokens)
        assertEquals(230L, total.cachedTokens)
        assertEquals(30L, total.outputTokens)
        assertEquals(2, total.turns)
        assertEquals(true, total.hasBaseline)
    }

    @Test
    fun `every step of one reply counts separately`() {
        // 一条消息上跑了 3 步工具调用：usage 被逐步覆盖，账单口径要求三步都算
        var total = baselineLifetimeUsage(mapOf("m" to snap(100, 80, 10)))
        val seen = seenOf("m" to snap(100, 80, 10))

        total = settleLifetimeUsage(total, seen, mapOf("m" to snap(200, 160, 20)))
        val seen2 = seen + seenOf("m" to snap(200, 160, 20))
        total = settleLifetimeUsage(total, seen2, mapOf("m" to snap(300, 250, 30)))

        assertEquals(600L, total.inputTokens) // 100 + 200 + 300
        assertEquals(490L, total.cachedTokens) // 80 + 160 + 250
        assertEquals(60L, total.outputTokens) // 10 + 20 + 30
        assertEquals(3, total.turns)
    }

    @Test
    fun `re-observing the same usage is deduped`() {
        val usage = snap(100, 80, 10)
        val total = baselineLifetimeUsage(mapOf("m" to usage))
        // 同一次请求被再次观察到（流式多次上报）→ 不该再计入
        val again = settleLifetimeUsage(total, seenOf("m" to usage), mapOf("m" to usage))
        assertEquals(100L, again.inputTokens)
        assertEquals(80L, again.cachedTokens)
        assertEquals(1, again.turns)
    }

    @Test
    fun `a later message adds only its own usage`() {
        val first = baselineLifetimeUsage(mapOf("a" to snap(100, 80, 10)))
        val second = settleLifetimeUsage(
            first,
            seenOf("a" to snap(100, 80, 10)),
            mapOf("a" to snap(100, 80, 10), "b" to snap(50, 40, 5)),
        )
        assertEquals(150L, second.inputTokens)
        assertEquals(120L, second.cachedTokens)
        assertEquals(15L, second.outputTokens)
        assertEquals(2, second.turns)
    }

    @Test
    fun `compaction drops the message but keeps the total`() {
        val baseline = mapOf("a" to snap(100, 80, 10), "b" to snap(200, 150, 20))
        val total = baselineLifetimeUsage(baseline)
        // 压缩：a 被折叠掉，只剩 b（+ 新的摘要消息 c）
        val after = settleLifetimeUsage(
            total,
            seenOf("a" to snap(100, 80, 10), "b" to snap(200, 150, 20)),
            mapOf("b" to snap(200, 150, 20), "c" to snap(30, 20, 2)),
        )
        assertEquals(330L, after.inputTokens) // 300 + 30，不缩水
        assertEquals(250L, after.cachedTokens)
        assertEquals(32L, after.outputTokens)
    }

    @Test
    fun `cached is clamped to input so hit rate stays below 100 percent`() {
        val total = baselineLifetimeUsage(mapOf("a" to snap(100, 130, 5)))
        assertEquals(100L, total.inputTokens)
        assertEquals(100L, total.cachedTokens)
    }

    @Test
    fun `no new fingerprint returns the same instance`() {
        val usage = snap(100, 80, 10)
        val total = baselineLifetimeUsage(mapOf("m" to usage))
        assertEquals(total, settleLifetimeUsage(total, seenOf("m" to usage), mapOf("m" to usage)))
    }
}
