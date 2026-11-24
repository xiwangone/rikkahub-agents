package me.rerere.rikkahub.service

import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [findLastUserMessage]：错误卡片「重试」的锚点查找。纯函数，不碰 ChatService。
 */
class RetryLastGenerationTest {
    private fun user(text: String) =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun assistant(text: String) =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    private fun conversation(vararg nodes: MessageNode) =
        Conversation(assistantId = Uuid.random(), messageNodes = nodes.toList())

    @Test
    fun `single user message is returned`() {
        val message = user("hello")
        assertEquals(message, findLastUserMessage(conversation(MessageNode.of(message))))
    }

    @Test
    fun `assistant reply after user message does not shadow it`() {
        val message = user("hello")
        val found = findLastUserMessage(
            conversation(MessageNode.of(message), MessageNode.of(assistant("hi"))),
        )
        assertEquals(message, found)
    }

    @Test
    fun `last user message wins among several turns`() {
        val first = user("first")
        val second = user("second")
        val found = findLastUserMessage(
            conversation(
                MessageNode.of(first),
                MessageNode.of(assistant("reply 1")),
                MessageNode.of(second),
                MessageNode.of(assistant("reply 2")),
            ),
        )
        assertEquals(second, found)
    }

    @Test
    fun `no user message returns null`() {
        assertNull(
            findLastUserMessage(conversation(MessageNode.of(assistant("hi")))),
        )
    }

    @Test
    fun `empty conversation returns null`() {
        assertNull(findLastUserMessage(conversation()))
    }

    @Test
    fun `empty node is skipped instead of throwing`() {
        val message = user("hello")
        val found = findLastUserMessage(
            conversation(MessageNode(messages = emptyList()), MessageNode.of(message)),
        )
        assertEquals(message, found)
    }
}
