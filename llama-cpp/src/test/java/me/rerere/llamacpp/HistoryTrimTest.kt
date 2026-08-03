package me.rerere.llamacpp

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTrimTest {

    private fun conversation(turns: Int) = (1..turns).map { UIMessage.user("message number $it") }

    @Test
    fun `a conversation inside the budget is untouched`() {
        val messages = conversation(3)
        assertEquals(messages, ChatRequestMapper.trimToBudget(messages, budgetBytes = 100_000))
    }

    @Test
    fun `oldest turns are dropped first when the budget is exceeded`() {
        val messages = conversation(50)
        val trimmed = ChatRequestMapper.trimToBudget(messages, budgetBytes = 200)
        assertTrue("must drop something", trimmed.size < messages.size)
        assertTrue("the newest turn must survive", trimmed.last() == messages.last())
        assertTrue("the oldest turn must be gone", !trimmed.contains(messages.first()))
    }

    @Test
    fun `the newest turn is never dropped even if it alone exceeds the budget`() {
        // Dropping it would send an empty conversation, which is worse than a
        // prompt that overflows and produces a readable error.
        val messages = conversation(5)
        val trimmed = ChatRequestMapper.trimToBudget(messages, budgetBytes = 1)
        assertEquals(1, trimmed.size)
        assertEquals(messages.last(), trimmed.first())
    }
}
