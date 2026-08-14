package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.AutoCompactionThresholdMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `token threshold is used as explicit compression context ceiling`() {
        val model = Model(modelId = "codex-model", contextLength = null)
        val settings = Settings(
            autoCompactionThresholdMode = AutoCompactionThresholdMode.TOKENS,
            autoCompactionThresholdTokensK = 372,
        )

        assertEquals(372_000, compactionContextLength(settings, model))
    }

    @Test
    fun `percent threshold keeps advertised compression context`() {
        val model = Model(modelId = "model", contextLength = 128_000)
        val settings = Settings(
            autoCompactionThresholdMode = AutoCompactionThresholdMode.PERCENT,
            autoCompactionThresholdTokensK = 372,
        )

        assertEquals(128_000, compactionContextLength(settings, model))
    }

    @Test
    fun `findToolCallPart locates a tool call in the currently selected branch`() {
        val target = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "search_web",
            input = "{}",
            output = listOf(UIMessagePart.Text("result")),
        )
        val node = MessageNode(
            messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(target)))
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))

        val found = findToolCallPart(conversation, "call-1")

        assertEquals(target, found)
    }

    @Test
    fun `findToolCallPart locates a tool call in a non-selected branch`() {
        val target = UIMessagePart.Tool(
            toolCallId = "call-2",
            toolName = "search_web",
            input = "{}",
            output = listOf(UIMessagePart.Text("result")),
        )
        val node = MessageNode(
            messages = listOf(
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(target)),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("regenerated"))),
            ),
            selectIndex = 1, // the branch containing "call-2" is NOT the selected one
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))

        val found = findToolCallPart(conversation, "call-2")

        assertEquals(target, found)
    }

    @Test
    fun `findToolCallPart returns null when no tool call matches`() {
        val node = MessageNode(
            messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hi"))))
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))

        assertNull(findToolCallPart(conversation, "missing-call"))
    }

    @Test
    fun `replaceToolCallPart replaces only the matching part and leaves the rest untouched`() {
        val original = UIMessagePart.Tool(
            toolCallId = "call-3",
            toolName = "search_web",
            input = "{\"q\":\"weather\"}",
            output = listOf(UIMessagePart.Text("old result")),
            executionStartedAt = 1_000L,
        )
        val otherText = UIMessagePart.Text("keep me")
        val node = MessageNode(
            messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(otherText, original)))
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))

        val updated = replaceToolCallPart(conversation, "call-3") {
            it.copy(output = listOf(UIMessagePart.Text("new result")), executionStartedAt = 2_000L)
        }

        val updatedTool = updated.messageNodes.single().messages.single().parts
            .filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("new result", (updatedTool.output.single() as UIMessagePart.Text).text)
        assertEquals(2_000L, updatedTool.executionStartedAt)
        // Unrelated parts are unaffected.
        assertTrue(updated.messageNodes.single().messages.single().parts.contains(otherText))
    }

    @Test
    fun `replaceToolCallPart is a no-op when the tool call is not found`() {
        val node = MessageNode(
            messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hi"))))
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))

        val result = replaceToolCallPart(conversation, "missing-call") { it }

        assertSame(conversation, result)
    }

    @Test
    fun `isStalledTurn is true on failure regardless of the last message`() {
        assertTrue(isStalledTurn(succeeded = false, lastMessage = null))
    }

    @Test
    fun `isStalledTurn is true on failure with an empty message list`() {
        // Mirrors handleMessageComplete calling this against currentMessages.lastOrNull()
        // when the conversation has no assistant message at all.
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())

        assertTrue(isStalledTurn(succeeded = false, lastMessage = conversation.currentMessages.lastOrNull()))
    }

    @Test
    fun `isStalledTurn is false on success when the last assistant message has text`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hello there")))

        assertTrue(!isStalledTurn(succeeded = true, lastMessage = message))
    }

    @Test
    fun `isStalledTurn is true on success when the last message is reasoning and tool parts only`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking..."),
                UIMessagePart.Tool(
                    toolCallId = "call-4",
                    toolName = "search_web",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("result")),
                ),
            ),
        )

        assertTrue(isStalledTurn(succeeded = true, lastMessage = message))
    }

    @Test
    fun `isStalledTurn is true on success when the only text part is blank`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("   ")))

        assertTrue(isStalledTurn(succeeded = true, lastMessage = message))
    }
}
