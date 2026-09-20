package me.rerere.rikkahub.costguards

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class TokenBudgetTrackerTest {

    private fun mkMessage(prompt: Int, completion: Int, total: Int = 0): UIMessage {
        val effectiveTotal = if (total > 0) total else prompt + completion
        return UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            usage = TokenUsage(
                promptTokens = prompt,
                completionTokens = completion,
                totalTokens = effectiveTotal,
            ),
        )
    }

    private fun mkConversation(messages: List<UIMessage>): Conversation {
        val nodes = messages.map { msg ->
            MessageNode(
                id = Uuid.random(),
                messages = listOf(msg),
                selectIndex = 0,
            )
        }
        return Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "test",
            messageNodes = nodes,
        )
    }

    @Test fun `aggregate sums prompt and completion tokens across messages`() {
        val conv = mkConversation(listOf(
            mkMessage(prompt = 100, completion = 50),
            mkMessage(prompt = 200, completion = 100),
            mkMessage(prompt = 50, completion = 25),
        ))
        val totals = TokenBudgetTracker.aggregate(conv)
        assertEquals(350L, totals.inputTokens)
        assertEquals(175L, totals.outputTokens)
        assertEquals(525L, totals.totalTokens)
        assertEquals(3, totals.messageCount)
    }

    @Test fun `aggregate ignores messages without usage`() {
        val conv = mkConversation(listOf(
            mkMessage(prompt = 100, completion = 50),
            UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = emptyList()),
            mkMessage(prompt = 200, completion = 100),
        ))
        val totals = TokenBudgetTracker.aggregate(conv)
        assertEquals(300L, totals.inputTokens)
        assertEquals(150L, totals.outputTokens)
        assertEquals(2, totals.messageCount)
    }

    @Test fun `perMessageMax tracks largest single message`() {
        val conv = mkConversation(listOf(
            mkMessage(prompt = 100, completion = 50),
            mkMessage(prompt = 1000, completion = 500),
            mkMessage(prompt = 200, completion = 100),
        ))
        val totals = TokenBudgetTracker.aggregate(conv)
        assertEquals(1500L, totals.perMessageMax)
    }

    @Test fun `classify NO_BUDGET when both caps null`() {
        val totals = TokenBudgetTracker.Totals(
            inputTokens = 0, outputTokens = 0, cachedTokens = 0,
            totalTokens = 0, perMessageMax = 0, messageCount = 0,
        )
        assertEquals(TokenBudgetTracker.BudgetStatus.NO_BUDGET,
            TokenBudgetTracker.classify(totals, null, null))
    }

    @Test fun `classify UNDER_SOFT when perMessageMax below soft cap`() {
        val totals = TokenBudgetTracker.Totals(
            inputTokens = 0, outputTokens = 0, cachedTokens = 0,
            totalTokens = 999_999, // inflated by context overlap, ignored
            perMessageMax = 5_000, messageCount = 1,
        )
        assertEquals(TokenBudgetTracker.BudgetStatus.UNDER_SOFT,
            TokenBudgetTracker.classify(totals, softCap = 50_000, hardCap = 200_000))
    }

    @Test fun `classify WARN when perMessageMax above soft below hard`() {
        val totals = TokenBudgetTracker.Totals(
            inputTokens = 0, outputTokens = 0, cachedTokens = 0,
            totalTokens = 0, // totalTokens ignored for classification
            perMessageMax = 75_000, messageCount = 1,
        )
        assertEquals(TokenBudgetTracker.BudgetStatus.WARN,
            TokenBudgetTracker.classify(totals, softCap = 50_000, hardCap = 200_000))
    }

    @Test fun `classify OVER_HARD when perMessageMax at or above hard`() {
        val atCap = TokenBudgetTracker.Totals(
            inputTokens = 0, outputTokens = 0, cachedTokens = 0,
            totalTokens = 0, perMessageMax = 200_000, messageCount = 1,
        )
        val overCap = TokenBudgetTracker.Totals(
            inputTokens = 0, outputTokens = 0, cachedTokens = 0,
            totalTokens = 0, perMessageMax = 250_000, messageCount = 1,
        )
        assertEquals(TokenBudgetTracker.BudgetStatus.OVER_HARD,
            TokenBudgetTracker.classify(atCap, softCap = 50_000, hardCap = 200_000))
        assertEquals(TokenBudgetTracker.BudgetStatus.OVER_HARD,
            TokenBudgetTracker.classify(overCap, softCap = 50_000, hardCap = 200_000))
    }

    @Test fun `classify with only hard cap configured`() {
        val totals = TokenBudgetTracker.Totals(
            inputTokens = 0, outputTokens = 0, cachedTokens = 0,
            totalTokens = 0, perMessageMax = 100_000, messageCount = 1,
        )
        assertEquals(TokenBudgetTracker.BudgetStatus.UNDER_SOFT,
            TokenBudgetTracker.classify(totals, softCap = null, hardCap = 200_000))
    }

    @Test fun `classify ignores inflated totalTokens — uses perMessageMax`() {
        // totalTokens = 500_000 (inflated by context overlap) but perMessageMax = 30_000 (real window).
        // hard=200_000, so perMessageMax=30k → UNDER_SOFT (not OVER_HARD).
        val totals = TokenBudgetTracker.Totals(
            inputTokens = 0, outputTokens = 0, cachedTokens = 0,
            totalTokens = 500_000, perMessageMax = 30_000, messageCount = 10,
        )
        assertEquals(TokenBudgetTracker.BudgetStatus.UNDER_SOFT,
            TokenBudgetTracker.classify(totals, softCap = 50_000, hardCap = 200_000))
    }

    private fun mkMessageWithCache(prompt: Int, cached: Int): UIMessage =
        UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            usage = TokenUsage(
                promptTokens = prompt,
                completionTokens = 1,
                cachedTokens = cached,
                totalTokens = prompt + 1,
            ),
        )

    @Test fun `aggregate sums cached tokens`() {
        val conv = mkConversation(listOf(
            mkMessageWithCache(prompt = 100, cached = 80),
            mkMessageWithCache(prompt = 200, cached = 150),
        ))
        val totals = TokenBudgetTracker.aggregate(conv)
        assertEquals(300L, totals.inputTokens)
        assertEquals(230L, totals.cachedTokens)
    }

    @Test fun `aggregate coerces cached to prompt so hit rate never exceeds 100 percent`() {
        // Some providers / relays report cached > prompt; the aggregate must keep cached <= prompt,
        // otherwise "hit rate = cached / prompt" renders above 100%.
        val conv = mkConversation(listOf(
            mkMessageWithCache(prompt = 100, cached = 130),
            mkMessageWithCache(prompt = 50, cached = 50),
        ))
        val totals = TokenBudgetTracker.aggregate(conv)
        assertEquals(150L, totals.inputTokens)
        assertEquals(150L, totals.cachedTokens)
    }
}
