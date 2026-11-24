package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * [Conversation.finalizeInterruptedPendingTools] 必须走遍全部节点：
 * 分支 / regenerate 之后 Pending 工具可能落在非末尾节点上，只处理末尾节点
 * 会让那些工具在历史里永远挂着 Pending（深挖-S2）。
 */
class FinishInterruptedPendingToolsTest {

    private fun tool(id: String, state: ToolApprovalState) =
        UIMessagePart.Tool(
            toolCallId = id,
            toolName = "tool_$id",
            input = "{}",
            approvalState = state,
        )

    private fun message(vararg tools: UIMessagePart.Tool) =
        UIMessage(role = MessageRole.ASSISTANT, parts = tools.toList())

    /** 与生产侧 cancelToolByUser 同款口径：Pending/Approved 改写成 Denied。 */
    private val finalize: (UIMessagePart.Tool) -> UIMessagePart.Tool = { part ->
        part.copy(approvalState = ToolApprovalState.Denied("cancelled by user"))
    }

    private fun conversation(vararg nodes: UIMessage) =
        Conversation(
            assistantId = Uuid.random(),
            messageNodes = nodes.map { it.toMessageNode() },
        )

    private fun UIMessage.tools() = parts.filterIsInstance<UIMessagePart.Tool>()

    @Test
    fun `pending tool on a non-last node is finalized`() {
        val conv =
            conversation(
                message(tool("t1", ToolApprovalState.Pending)),
                message(tool("t2", ToolApprovalState.Pending)),
            )
        val updated = conv.finalizeInterruptedPendingTools(finalize)
        assertNotNull("non-last node must be finalized too", updated)
        val states =
            updated!!.messageNodes.flatMap { it.currentMessage.tools() }.map { it.approvalState }
        assertEquals(2, states.size)
        assertTrue(states.all { it is ToolApprovalState.Denied })
    }

    @Test
    fun `approved tool on a non-last node is finalized`() {
        val conv =
            conversation(
                message(tool("t1", ToolApprovalState.Approved())),
                message(),
            )
        val updated = conv.finalizeInterruptedPendingTools(finalize)
        assertNotNull(updated)
        assertTrue(
            updated!!.messageNodes[0].currentMessage.tools().single().approvalState is ToolApprovalState.Denied,
        )
        assertTrue(updated.messageNodes[1].currentMessage.parts.isEmpty())
    }

    @Test
    fun `no pending tools returns null so the caller skips the save`() {
        val conv = conversation(message(tool("t1", ToolApprovalState.Denied("no"))))
        assertNull(conv.finalizeInterruptedPendingTools(finalize))
    }

    @Test
    fun `denied and auto tools are left untouched`() {
        val conv =
            conversation(
                message(
                    tool("t1", ToolApprovalState.Denied("blocked")),
                    tool("t2", ToolApprovalState.Auto),
                ),
            )
        assertNull(conv.finalizeInterruptedPendingTools(finalize))
    }
}
