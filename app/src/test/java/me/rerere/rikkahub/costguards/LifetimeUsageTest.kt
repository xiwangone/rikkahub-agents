package me.rerere.rikkahub.costguards

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [settleLifetimeUsage] / [baselineLifetimeUsage] — the per-message settlement that
 * replaced the old "aggregate delta + max(0, Δ)" accumulation.
 *
 * Contract:
 * - first sight  -> every message counted once (baseline, no double counting later)
 * - new message  -> only the newcomer is added
 * - rewritten    -> only the difference is settled (a rewrite never double counts)
 * - fluctuation  -> up/down/up settles to the final value
 * - compaction   -> a vanished message keeps its already-counted usage (totals never shrink)
 * - cached       -> always clamped to input (hit rate can never exceed 100%)
 */
class LifetimeUsageTest {

    private fun snap(input: Long, cached: Long = 0, output: Long = 0) =
        MessageUsageSnapshot(input = input, cached = cached, output = output)

    @Test
    fun `first sight records every message once`() {
        val baseline = mapOf("a" to snap(100, 80, 10), "b" to snap(200, 150, 20))
        val total = baselineLifetimeUsage(baseline)
        assertEquals(300L, total.inputTokens)
        assertEquals(230L, total.cachedTokens)
        assertEquals(30L, total.outputTokens)
        assertEquals(2, total.turns)
        assertEquals(true, total.hasBaseline)
    }

    @Test
    fun `appending a message adds only the newcomer`() {
        val first = baselineLifetimeUsage(mapOf("a" to snap(100, 80, 10)))
        val second = settleLifetimeUsage(
            first,
            baseline = mapOf("a" to snap(100, 80, 10)),
            current = mapOf("a" to snap(100, 80, 10), "b" to snap(50, 40, 5)),
        )
        assertEquals(150L, second.inputTokens)
        assertEquals(120L, second.cachedTokens)
        assertEquals(15L, second.outputTokens)
        assertEquals(2, second.turns)
    }

    @Test
    fun `rewritten usage settles only the difference`() {
        val first = baselineLifetimeUsage(mapOf("a" to snap(100, 80, 1000)))
        // Same message, usage updated later (stream finished / retried)
        val second = settleLifetimeUsage(
            first,
            baseline = mapOf("a" to snap(100, 80, 1000)),
            current = mapOf("a" to snap(100, 80, 1200)),
        )
        assertEquals(100L, second.inputTokens)
        assertEquals(80L, second.cachedTokens)
        assertEquals(1200L, second.outputTokens) // not 2200
    }

    @Test
    fun `fluctuating values never double count`() {
        // Regression: the old aggregate-delta implementation double counted here.
        var total = baselineLifetimeUsage(mapOf("a" to snap(100, 80, 1000)))
        var baseline = mapOf("a" to snap(100, 80, 1000))
        listOf(
            mapOf("a" to snap(120, 90, 1100)), // up
            mapOf("a" to snap(120, 90, 900)), // down (rewrite / retry)
            mapOf("a" to snap(120, 90, 1500)), // up again
        ).forEach { current ->
            total = settleLifetimeUsage(total, baseline, current)
            baseline = current
        }
        assertEquals(120L, total.inputTokens)
        assertEquals(90L, total.cachedTokens)
        assertEquals(1500L, total.outputTokens)
    }

    @Test
    fun `compaction drops the snapshot but keeps the total`() {
        val baseline = mapOf("a" to snap(100, 80, 10), "b" to snap(200, 150, 20))
        val total = baselineLifetimeUsage(baseline)
        // Compaction folds "a" away and adds a summary message "c"
        val after = settleLifetimeUsage(
            total,
            baseline = baseline,
            current = mapOf("b" to snap(200, 150, 20), "c" to snap(30, 20, 2)),
        )
        assertEquals(330L, after.inputTokens) // 300 + 30, never shrinks
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
    fun `unchanged input returns the same instance`() {
        val baseline = mapOf("a" to snap(100, 80, 10))
        val total = baselineLifetimeUsage(baseline)
        assertEquals(total, settleLifetimeUsage(total, baseline, baseline))
    }
}
