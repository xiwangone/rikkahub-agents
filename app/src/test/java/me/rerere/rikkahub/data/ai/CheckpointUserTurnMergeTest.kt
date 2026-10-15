package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩检查点的「连续 user」折叠只作用于请求组装：折叠后压缩点处必须只剩一条用户回合，
 * 且**不含检查点的列表逐字节不变**（未压缩会话的请求体不受本次改动影响）。
 */
class CheckpointUserTurnMergeTest {

    private fun checkpoint(text: String) = UIMessage.user(text).copy(isContextCheckpoint = true)

    private fun roles(messages: List<UIMessage>) = messages.map { it.role.name }

    @Test
    fun `folds the checkpoint and the following user message into one turn`() {
        val head = checkpoint("summary body")
        val out = listOf(head, UIMessage.user("then what is next")).mergeCheckpointIntoUserTurn()

        assertEquals(listOf("USER"), roles(out))
        assertEquals("summary body\n\nthen what is next", out[0].toText())
        // 身份沿用检查点：摘要文本固定 ⇒ 请求前缀稳定，不额外破提示词缓存
        assertEquals(head.id, out[0].id)
        assertEquals(head.createdAt, out[0].createdAt)
        assertTrue(out[0].isContextCheckpoint)
    }

    @Test
    fun `folds every consecutive user message after the checkpoint and keeps their order`() {
        val out = listOf(
            checkpoint("first half"),
            UIMessage.user("second half"),
            UIMessage.user("kept user message"),
            UIMessage.assistant("reply"),
        ).mergeCheckpointIntoUserTurn()

        assertEquals(listOf("USER", "ASSISTANT"), roles(out))
        assertEquals("first half\n\nsecond half\n\nkept user message", out[0].toText())
        assertEquals("reply", out[1].toText())
    }

    @Test
    fun `returns the same list when the checkpoint is not followed by a user message`() {
        val messages = listOf(checkpoint("summary"), UIMessage.assistant("reply"))

        assertSame(messages, messages.mergeCheckpointIntoUserTurn())
    }

    @Test
    fun `returns the same list when there is no checkpoint at all`() {
        val messages = listOf(UIMessage.user("a"), UIMessage.user("b"))

        assertSame(messages, messages.mergeCheckpointIntoUserTurn())
    }

    @Test
    fun `never folds a message that carries tool parts`() {
        val toolBearing = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Tool(toolCallId = "call_1", toolName = "read_file", input = "{}")),
        )

        val out = listOf(checkpoint("summary"), UIMessage.user("follow up"), toolBearing)
            .mergeCheckpointIntoUserTurn()

        // 工具所在回合是断点：折叠只吃到它前一条为止，配对结构原样保留
        assertEquals(listOf("USER", "USER"), roles(out))
        assertSame(toolBearing, out[1])
    }

    @Test
    fun `keeps non-text parts in order and does not coalesce across them`() {
        val out = listOf(
            checkpoint("summary"),
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Image("file://a.png"), UIMessagePart.Text("look at this")),
            ),
        ).mergeCheckpointIntoUserTurn()

        assertEquals(1, out.size)
        assertEquals(3, out[0].parts.size)
        assertEquals("summary", (out[0].parts[0] as UIMessagePart.Text).text)
        assertEquals("file://a.png", (out[0].parts[1] as UIMessagePart.Image).url)
        assertEquals("look at this", (out[0].parts[2] as UIMessagePart.Text).text)
    }

    @Test
    fun `dropping blank text blocks leaves no trailing separator`() {
        val out = listOf(checkpoint("summary"), UIMessage.user("   ")).mergeCheckpointIntoUserTurn()

        assertEquals("summary", out[0].toText())
    }

    @Test
    fun `folding is idempotent`() {
        val once = listOf(checkpoint("summary"), UIMessage.user("next")).mergeCheckpointIntoUserTurn()

        assertSame(once, once.mergeCheckpointIntoUserTurn())
    }

    @Test
    fun `calibration note stays glued to the summary while the user instruction ends the turn`() {
        val out = listOf(checkpoint("summary"), UIMessage.user("do it now"))
            .withCheckpointCalibration()
            .mergeCheckpointIntoUserTurn()

        val text = out[0].toText()
        assertEquals(1, out.size)
        assertTrue(text.startsWith("summary"))
        assertTrue(text.contains("执行提示"))
        // 折叠成一条后仍是**一个**文本块，provider 侧 content 保持纯字符串形态
        assertEquals(1, out[0].parts.size)
    }
}
