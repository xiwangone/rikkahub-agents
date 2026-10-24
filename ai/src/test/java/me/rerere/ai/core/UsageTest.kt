package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageTest {
    @Test
    fun `merge carries cost from the incoming chunk`() {
        val merged = (null as TokenUsage?).merge(
            TokenUsage(promptTokens = 10, completionTokens = 5, cost = 0.0012)
        )
        assertEquals(0.0012, merged.cost!!, 1e-9)
    }

    @Test
    fun `merge keeps prior cost when the incoming chunk has none`() {
        // Streaming: an early chunk reported cost; a later token-only delta must not wipe it.
        val merged = TokenUsage(cost = 0.0034).merge(TokenUsage(completionTokens = 3))
        assertEquals(0.0034, merged.cost!!, 1e-9)
    }

    @Test
    fun `merge prefers the newest cost`() {
        val merged = TokenUsage(cost = 0.001).merge(TokenUsage(cost = 0.002))
        assertEquals(0.002, merged.cost!!, 1e-9)
    }

    @Test
    fun `merge leaves cost null when neither side reports it`() {
        val merged = TokenUsage(promptTokens = 1).merge(TokenUsage(completionTokens = 1))
        assertNull(merged.cost)
    }

    @Test
    fun `sum adds distinct requests while merge remains a snapshot update`() {
        val total = TokenUsage(promptTokens = 100, completionTokens = 10, cachedTokens = 20, cost = 0.1)
            .sum(TokenUsage(promptTokens = 200, completionTokens = 20, cachedTokens = 30, cost = 0.2))

        assertEquals(300, total?.promptTokens)
        assertEquals(30, total?.completionTokens)
        assertEquals(50, total?.cachedTokens)
        assertEquals(330, total?.totalTokens)
        assertEquals(0.3, total?.cost!!, 1e-9)
    }

    @Test
    fun `sum preserves null when neither request reports usage`() {
        assertNull((null as TokenUsage?).sum(null))
    }
}
