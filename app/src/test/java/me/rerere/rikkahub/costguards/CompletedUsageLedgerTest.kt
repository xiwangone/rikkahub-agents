package me.rerere.rikkahub.costguards

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompletedUsageLedgerTest {
    private fun usage(input: Int, output: Int, cost: Double? = null) = TokenUsage(
        promptTokens = input, completionTokens = output, totalTokens = input + output, cost = cost,
    )

    private fun assistant(tokens: TokenUsage) = UIMessage(
        role = MessageRole.ASSISTANT, parts = emptyList(), usage = tokens,
        lastUsage = tokens, stepCount = 1,
    )

    @Test fun `history before new user turn is never billed again`() {
        val historical = assistant(usage(100, 20, 0.5))
        val ledger = CompletedUsageLedger(listOf(historical, UIMessage.user("synthetic new turn")))
        assertNull(ledger.takeDelta(historical))
        val current = assistant(usage(11, 3))
        assertEquals(usage(11, 3), ledger.takeDelta(current))
        assertNull(ledger.takeDelta(historical))
        assertNull(ledger.takeDelta(current))
    }

    @Test fun `internally continued requests bill cumulative delta not latest request only`() {
        val ledger = CompletedUsageLedger(emptyList())
        val first = assistant(usage(11, 3, 0.1))
        assertEquals(first.usage, ledger.takeDelta(first))
        val completed = first.copy(
            usage = usage(60, 16, 0.4), lastUsage = usage(31, 9, 0.2), stepCount = 3,
        )
        val delta = ledger.takeDelta(completed)!!
        assertEquals(49, delta.promptTokens)
        assertEquals(13, delta.completionTokens)
        assertEquals(62, delta.totalTokens)
        assertEquals(0.3, delta.cost!!, 0.000001)
        assertNull(ledger.takeDelta(completed))
    }

    @Test fun `resuming persisted assistant bills only newly committed requests`() {
        val persisted = assistant(usage(11, 3))
        val ledger = CompletedUsageLedger(listOf(persisted))
        assertNull(ledger.takeDelta(persisted))
        val partialPreview = persisted.copy(parts = emptyList())
        assertNull(ledger.takeDelta(partialPreview))
        assertEquals(usage(18, 4), ledger.takeDelta(persisted.copy(usage = usage(29, 7), stepCount = 2)))
    }

    @Test fun `stale snapshots cannot lower accounting watermark`() {
        val old = assistant(usage(11, 3, 0.1))
        val ledger = CompletedUsageLedger(emptyList())
        val latest = old.copy(usage = usage(60, 16, 0.4), stepCount = 3)
        assertEquals(latest.usage, ledger.takeDelta(latest))
        assertNull(ledger.takeDelta(old))
        assertNull(ledger.takeDelta(latest))
        val next = latest.copy(usage = usage(70, 18, 0.5), stepCount = 4)
        assertEquals(10, ledger.takeDelta(next)!!.promptTokens)
    }

    @Test fun `legacy missing total token fields produce normalized request deltas`() {
        val persisted = assistant(TokenUsage(promptTokens = 10, completionTokens = 2))
        val ledger = CompletedUsageLedger(listOf(persisted))
        val updated = persisted.copy(
            usage = TokenUsage(promptTokens = 15, completionTokens = 5),
            stepCount = 2,
        )

        val delta = ledger.takeDelta(updated)!!
        assertEquals(5, delta.promptTokens)
        assertEquals(3, delta.completionTokens)
        assertEquals(8, delta.totalTokens)
    }

    @Test fun `cost-only increases are accounted without replaying token counters`() {
        val persisted = assistant(usage(10, 2, 0.1))
        val ledger = CompletedUsageLedger(listOf(persisted))
        val updated = persisted.copy(usage = usage(10, 2, 0.15), stepCount = 2)

        val delta = ledger.takeDelta(updated)!!
        assertEquals(0, delta.promptTokens)
        assertEquals(0, delta.completionTokens)
        assertEquals(0.05, delta.cost!!, 0.000001)
    }
}
