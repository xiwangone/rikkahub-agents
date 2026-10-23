package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageUsagePresentationTest {
    @Test
    fun multiRequestMessageSeparatesCumulativeAndLatestUsage() {
        val total = TokenUsage(promptTokens = 900, completionTokens = 180, totalTokens = 1080)
        val latest = TokenUsage(promptTokens = 300, completionTokens = 60, totalTokens = 360)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("answer")),
            usage = total,
            lastUsage = latest,
            stepCount = 3,
        )

        val presentation = message.messageUsagePresentation()
        assertEquals(total, presentation.messageTotal)
        assertEquals(latest, presentation.latestRequest)
        assertEquals(3, presentation.multipleModelRequestCount)
    }

    @Test
    fun missingLatestUsageOnMultiRequestMessageDoesNotMislabelTotalAsLatest() {
        val total = TokenUsage(promptTokens = 900, completionTokens = 180)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("answer")),
            usage = total,
            lastUsage = null,
            stepCount = 3,
        )

        val presentation = message.messageUsagePresentation()
        assertEquals(total, presentation.messageTotal)
        assertNull(presentation.latestRequest)
        assertEquals(3, presentation.multipleModelRequestCount)
    }

    @Test
    fun oneModelRequestDoesNotShowRedundantRequestCount() {
        val latest = TokenUsage(promptTokens = 120, completionTokens = 24)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("answer")),
            usage = latest,
            lastUsage = latest,
            stepCount = 1,
        )

        val presentation = message.messageUsagePresentation()
        assertEquals(latest, presentation.messageTotal)
        assertEquals(latest, presentation.latestRequest)
        assertNull(presentation.multipleModelRequestCount)
    }

    @Test
    fun legacySingleRequestUsageCanRepresentLatestRequest() {
        val legacy = TokenUsage(promptTokens = 45, completionTokens = 9)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("old answer")),
            usage = legacy,
            stepCount = 0,
        )

        assertEquals(legacy, message.messageUsagePresentation().latestRequest)
    }

    @Test
    fun toolCountDoesNotBecomeModelRequestCount() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(toolCallId = "one", toolName = "synthetic", input = "{}"),
                UIMessagePart.Tool(toolCallId = "two", toolName = "synthetic", input = "{}"),
            ),
            stepCount = 1,
        )

        assertNull(message.messageUsagePresentation().multipleModelRequestCount)
    }
}
