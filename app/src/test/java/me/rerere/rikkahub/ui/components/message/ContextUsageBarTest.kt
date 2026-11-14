package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the usage-bar math extracted as pure helpers:
 * - [contextBarSegments]: the nerd-line bar split (cached / uncached input).
 * - [contextUsageCapacity]: the usage-details capacity / remaining computation.
 *
 * Both guard regressions where the bar segments no longer add up to the width
 * the bar claims to represent.
 */
class ContextUsageBarTest {

    @Test
    fun `segments split prompt into cached and uncached`() {
        assertEquals(2_000L to 8_000L, contextBarSegments(cachedTokens = 2_000L, promptTokens = 10_000L))
    }

    @Test
    fun `segments with no cache are all uncached`() {
        assertEquals(0L to 10_000L, contextBarSegments(cachedTokens = 0L, promptTokens = 10_000L))
    }

    @Test
    fun `segments with full cache have no uncached part`() {
        assertEquals(10_000L to 0L, contextBarSegments(cachedTokens = 10_000L, promptTokens = 10_000L))
    }

    @Test
    fun `negative cached tokens clamp to zero`() {
        assertEquals(0L to 10_000L, contextBarSegments(cachedTokens = -5L, promptTokens = 10_000L))
    }

    @Test
    fun `cached over prompt clamps uncached to zero`() {
        assertEquals(12_000L to 0L, contextBarSegments(cachedTokens = 12_000L, promptTokens = 10_000L))
    }

    @Test
    fun `capacity is the window when it covers prompt plus output`() {
        assertEquals(
            100_000L to 60_000L,
            contextUsageCapacity(windowTokens = 100_000L, promptTokens = 30_000L, outputTokens = 10_000L),
        )
    }

    @Test
    fun `capacity grows to prompt plus output when it exceeds the window`() {
        // The regression: the old capacity ignored output, so the three segments
        // plus the remaining spacer summed to more than the capacity and the
        // Row normalized every weight down.
        assertEquals(
            40_000L to 0L,
            contextUsageCapacity(windowTokens = 30_000L, promptTokens = 30_000L, outputTokens = 10_000L),
        )
    }

    @Test
    fun `capacity without a window is prompt plus output`() {
        assertEquals(
            40_000L to 0L,
            contextUsageCapacity(windowTokens = null, promptTokens = 30_000L, outputTokens = 10_000L),
        )
    }

    @Test
    fun `capacity with no output keeps the old behaviour`() {
        assertEquals(
            100_000L to 70_000L,
            contextUsageCapacity(windowTokens = 100_000L, promptTokens = 30_000L, outputTokens = 0L),
        )
    }

    @Test
    fun `capacity never drops below one`() {
        assertEquals(
            1L to 1L,
            contextUsageCapacity(windowTokens = null, promptTokens = 0L, outputTokens = 0L),
        )
    }

    @Test
    fun `segments plus remaining always sum to capacity`() {
        val (capacity, remaining) =
            contextUsageCapacity(windowTokens = 50_000L, promptTokens = 30_000L, outputTokens = 10_000L)
        assertEquals(50_000L, capacity)
        assertEquals(10_000L, remaining)
        assertEquals(capacity, 30_000L + 10_000L + remaining)
    }
}
