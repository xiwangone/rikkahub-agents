package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UIMessage.abandonUnexecutedTools] 的改写范围与「保留消息」语义。
 *
 * 这条路径在生成开始前的 `checkInvalidMessages` 里被调用：此前它会**整条删除**含未执行工具的消息，
 * 连带丢掉本轮已生成的 AI 文本与工具调用记录（用户与模型都失去上下文）。改为补一个「未执行（中断）」
 * 的合成结果后保留消息。
 *
 * 与 [FinishPendingToolsTest] 的分工：`finishPendingTools` 只改写用户已表态的 Pending/Approved 并
 * 刻意放过 Auto；本函数覆盖**所有未执行工具**（含自动批准后被中断的 Auto）。
 */
class AbandonUnexecutedToolsTest {
    private val reason =
        "interrupted_before_execution: the turn was interrupted before this tool ran; it did NOT execute."

    private fun tool(
        state: ToolApprovalState,
        executed: Boolean = false,
    ) = UIMessagePart.Tool(
        toolCallId = "call_1",
        toolName = "shizuku_exec",
        input = "{}",
        output = if (executed) listOf(UIMessagePart.Text(text = "ok")) else emptyList(),
        approvalState = state,
    )

    private fun after(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val updated = message.abandonUnexecutedTools(reason)
        return updated.parts.filterIsInstance<UIMessagePart.Tool>().single()
    }

    @Test
    fun `auto unexecuted tool is terminated`() {
        val out = after(tool(ToolApprovalState.Auto))
        assertEquals(ToolApprovalState.Denied(reason), out.approvalState)
        assertTrue("synthetic output must mark the tool as executed", out.isExecuted)
    }

    @Test
    fun `pending unexecuted tool is terminated`() {
        val out = after(tool(ToolApprovalState.Pending))
        assertEquals(ToolApprovalState.Denied(reason), out.approvalState)
        assertTrue(out.isExecuted)
    }

    @Test
    fun `approved unexecuted tool is terminated`() {
        val out = after(tool(ToolApprovalState.Approved))
        assertEquals(ToolApprovalState.Denied(reason), out.approvalState)
        assertTrue(out.isExecuted)
    }

    @Test
    fun `executed tool is left untouched`() {
        val executed = tool(ToolApprovalState.Auto, executed = true)
        val updated = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(executed)).abandonUnexecutedTools(reason)
        assertSame(executed, updated.parts.filterIsInstance<UIMessagePart.Tool>().single())
    }

    @Test
    fun `message without tools is returned unchanged`() {
        val message =
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hello")))
        assertSame(message, message.abandonUnexecutedTools(reason))
    }

    @Test
    fun `text parts survive and only unexecuted tools are terminated`() {
        val text = UIMessagePart.Text("I will run the command now")
        val running = tool(ToolApprovalState.Auto)
        val done = tool(ToolApprovalState.Approved, executed = true)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(text, running, done))

        val updated = message.abandonUnexecutedTools(reason)

        assertEquals(3, updated.parts.size)
        assertSame(text, updated.parts[0])
        val tools = updated.parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(ToolApprovalState.Denied(reason), tools[0].approvalState)
        assertTrue(tools[0].isExecuted)
        assertSame(done, tools[1])
    }
}
