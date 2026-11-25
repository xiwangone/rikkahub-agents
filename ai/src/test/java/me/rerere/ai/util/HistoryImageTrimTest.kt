package me.rerere.ai.util

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryImageTrimTest {

    @Test
    fun `retainedHistoryImages keeps only newest N images across messages and tool outputs`() {
        val img1 = UIMessagePart.Image("file:///1.jpg")
        val img2 = UIMessagePart.Image("file:///2.jpg")
        val img3 = UIMessagePart.Image("file:///3.jpg")
        val img4 = UIMessagePart.Image("file:///4.jpg")
        val img5 = UIMessagePart.Image("file:///5.jpg")

        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(img1)),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "t1",
                        toolName = "phone_use",
                        input = "{}",
                        output = listOf(img2, UIMessagePart.Text("step 2"), img3),
                    ),
                    UIMessagePart.Tool(
                        toolCallId = "t2",
                        toolName = "phone_use",
                        input = "{}",
                        output = listOf(img4),
                    ),
                ),
            ),
            UIMessage(role = MessageRole.USER, parts = listOf(img5)),
        )

        val retained = retainedHistoryImages(messages, maxImages = 3)
        assertEquals(3, retained.size)
        assertFalse(img1 in retained)
        assertFalse(img2 in retained)
        assertTrue(img3 in retained)
        assertTrue(img4 in retained)
        assertTrue(img5 in retained)
    }

    @Test
    fun `retainedHistoryImages with zero limit returns empty set`() {
        val img = UIMessagePart.Image("file:///1.jpg")
        val retained = retainedHistoryImages(
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(img))),
            maxImages = 0,
        )
        assertTrue(retained.isEmpty())
    }
}
