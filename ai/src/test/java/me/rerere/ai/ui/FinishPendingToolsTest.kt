package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [UIMessage.finishPendingTools] 的改写范围。
 *
 * 这条路径在「用户发送下一条消息 / 停止生成」时被调用，用于把悬而未决的工具定案。
 * 它必须只改写「用户已表态但没跑完」的工具：
 * - `Pending`（等待批准时用户继续对话 = 放弃本次批准窗口）；
 * - `Approved`（批准了但被 `/stop` 打断）。
 *
 * 刻意**不**改写 `Auto` —— Auto 表示用户从未被问到（例如状态被过期快照回退），
 * 把它写成 `Denied("cancelled by user")` 是把「没问过」冤判成「用户拒绝」。
 */
class FinishPendingToolsTest {
    private val cancelled = ToolApprovalState.Denied("Generation cancelled by user")

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

    private fun finish(tool: UIMessagePart.Tool): ToolApprovalState {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val updated = message.finishPendingTools { it.copy(approvalState = cancelled) }
        return updated.parts.filterIsInstance<UIMessagePart.Tool>().single().approvalState
    }

    @Test
    fun `pending tool is marked cancelled`() {
        assertEquals(cancelled, finish(tool(ToolApprovalState.Pending)))
    }

    @Test
    fun `approved but unexecuted tool is marked cancelled`() {
        assertEquals(cancelled, finish(tool(ToolApprovalState.Approved)))
    }

    @Test
    fun `auto tool is left untouched`() {
        assertSame(ToolApprovalState.Auto, finish(tool(ToolApprovalState.Auto)))
    }

    @Test
    fun `denied tool keeps its original reason`() {
        val blocked = ToolApprovalState.Denied("blocked by safety floor (hardline)")
        assertEquals(blocked, finish(tool(blocked)))
    }

    @Test
    fun `answered tool is left untouched`() {
        val answered = ToolApprovalState.Answered("""{"answers":{"q1":"yes"}}""")
        assertEquals(answered, finish(tool(answered)))
    }

    @Test
    fun `executed tool is left untouched even when pending`() {
        assertEquals(ToolApprovalState.Pending, finish(tool(ToolApprovalState.Pending, executed = true)))
    }
}
