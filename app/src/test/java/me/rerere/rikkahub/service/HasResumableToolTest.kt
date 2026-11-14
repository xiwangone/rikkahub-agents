package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1-7：[hasResumableTool] 只认真正能产出执行的态（Approved / Answered）。
 *
 * [ToolApprovalState.Denied] 是终态、不产出执行：循环里要给它补拒绝输出（那是
 * [me.rerere.ai.ui.canResumeToolExecution] 返回 true 的原因），但清理侧不能让它
 * 为同节点 Pending 挡清理——否则 `[Denied(未执行) + Pending(未执行)]` 的节点被整条
 * 保留，Pending 兄弟在请求副本里已被写成「已中断、终态」，持久化状态却还是「等用户审批」。
 */
class HasResumableToolTest {

    private fun tool(id: String, state: ToolApprovalState, executed: Boolean = false) =
        UIMessagePart.Tool(
            toolCallId = id,
            toolName = "tool_$id",
            input = "{}",
            output = if (executed) listOf(UIMessagePart.Text("done")) else emptyList(),
            approvalState = state,
        )

    private fun message(vararg tools: UIMessagePart.Tool) =
        UIMessage(role = MessageRole.ASSISTANT, parts = tools.toList())

    @Test
    fun `denied plus pending does not keep the node`() {
        assertFalse(
            hasResumableTool(
                message(
                    tool("t1", ToolApprovalState.Denied("user said no")),
                    tool("t2", ToolApprovalState.Pending),
                ),
            ),
        )
    }

    @Test
    fun `denied alone does not keep the node`() {
        assertFalse(hasResumableTool(message(tool("t1", ToolApprovalState.Denied("user said no")))))
    }

    @Test
    fun `approved plus pending keeps the node`() {
        assertTrue(
            hasResumableTool(
                message(
                    tool("t1", ToolApprovalState.Approved),
                    tool("t2", ToolApprovalState.Pending),
                ),
            ),
        )
    }

    @Test
    fun `answered keeps the node`() {
        assertTrue(hasResumableTool(message(tool("t1", ToolApprovalState.Answered("yes")))))
    }

    @Test
    fun `pending alone does not keep the node`() {
        assertFalse(hasResumableTool(message(tool("t1", ToolApprovalState.Pending))))
    }

    @Test
    fun `auto alone does not keep the node`() {
        assertFalse(hasResumableTool(message(tool("t1", ToolApprovalState.Auto))))
    }

    @Test
    fun `executed approved does not keep the node`() {
        assertFalse(
            hasResumableTool(
                message(
                    tool("t1", ToolApprovalState.Approved, executed = true),
                    tool("t2", ToolApprovalState.Pending),
                ),
            ),
        )
    }
}
