package me.rerere.rikkahub.ui.hooks

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 长文本输入回归：输入状态到发送链路不得截断用户文本。
 *
 * 覆盖的回归场景：约 430 字符的消息在模拟器上经 adb input 注入后只剩约 200 字符。
 * 已确认丢失发生在输入事件注入环节（系统侧丢弃过期的注入按键事件），App 内
 * 输入框、发送与存储链路不设字符上限；本测试把 App 侧的约定锁死——
 * 输入状态往返后全文必须保持一致，任一环节引入截断都会在这里失败。
 */
class ChatInputStateLongTextTest {

    companion object {
        /** 复测用的 415 字符消息（b6-trunc-msg.txt 全文）。 */
        const val LONG_MESSAGE =
            "Please repeat this message back to me exactly as written, then count its characters. " +
                "The quick brown fox jumps over the lazy dog near the river bank at sunset while the birds " +
                "sing songs of spring and the wind blows gently through the tall green trees standing by the " +
                "old stone bridge. This is a test of long message delivery. If you can see this full text, " +
                "repeat it and end with the code TRUNC-OK-B6. END-MARKER-B6"
    }

    private fun ChatInputState.sentText(): String {
        val parts = getContents()
        val texts = parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
        assertEquals("long text must stay in a single text part", 1, texts.size)
        return texts.single()
    }

    @Test
    fun `setMessageText keeps 415-char message intact`() {
        assertEquals(415, LONG_MESSAGE.length)
        val state = ChatInputState()
        state.setMessageText(LONG_MESSAGE)
        val sent = state.sentText()
        assertEquals(415, sent.length)
        assertEquals(LONG_MESSAGE, sent)
        assertTrue(sent.endsWith("END-MARKER-B6"))
    }

    @Test
    fun `appendText keeps long text intact across chunks`() {
        val state = ChatInputState()
        state.setMessageText(LONG_MESSAGE.take(200))
        state.appendText(LONG_MESSAGE.drop(200))
        val sent = state.sentText()
        assertEquals(415, sent.length)
        assertEquals(LONG_MESSAGE, sent)
    }

    @Test
    fun `very long text survives`() {
        val text = "A".repeat(5000) + " END-MARKER-B6"
        val state = ChatInputState()
        state.setMessageText(text)
        val sent = state.sentText()
        assertEquals(text.length, sent.length)
        assertEquals(text, sent)
    }

    @Test
    fun `multi-line long text survives`() {
        val text = (1..50).joinToString("\n") { "line $it: the quick brown fox jumps over the lazy dog" }
        val state = ChatInputState()
        state.setMessageText(text)
        val sent = state.sentText()
        assertEquals(text, sent)
    }

    @Test
    fun `setContents round-trip keeps long text`() {
        val state = ChatInputState()
        state.setContents(listOf(UIMessagePart.Text(LONG_MESSAGE)))
        val sent = state.sentText()
        assertEquals(LONG_MESSAGE, sent)
    }

    @Test
    fun `edit path keeps long text`() {
        val state = ChatInputState()
        state.setContents(listOf(UIMessagePart.Text(LONG_MESSAGE)))
        state.editingMessage = Uuid.random()
        val sent = state.sentText()
        assertEquals(LONG_MESSAGE, sent)
    }
}
