package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPhaseTest {
    private fun tool(name: String, pending: Boolean = true) = UIMessagePart.Tool(
        toolCallId = "synthetic-call",
        toolName = name,
        input = "{}",
        approvalState = if (pending) ToolApprovalState.Pending else ToolApprovalState.Approved,
    )

    @Test
    fun emptyReplyWaits() {
        assertEquals(GenerationPhase.Waiting, emptyList<UIMessagePart>().generationPhase())
    }

    @Test
    fun approvalIsNotReportedAsExecution() {
        val phase = listOf(tool("shell")).generationPhase()
        assertEquals(GenerationPhase.AwaitingApproval("shell", "{}"), phase)
    }

    @Test
    fun askUserIsAnAnswerRequestNotAnApproval() {
        assertTrue(listOf(tool("ask_user")).generationPhase() is GenerationPhase.AwaitingAnswer)
    }

    @Test
    fun approvedToolCanBeReportedAsRunning() {
        assertEquals(GenerationPhase.RunningTool("shell", "{}"), listOf(tool("shell", false)).generationPhase())
    }

    @Test
    fun mcpNameDropsOnlyServerPrefix() {
        assertEquals(
            GenerationPhase.AwaitingApproval("search", "{}"),
            listOf(tool("mcp__synthetic_server__search")).generationPhase(),
        )
        assertEquals(
            GenerationPhase.AwaitingApproval("local__tool", "{}"),
            listOf(tool("local__tool")).generationPhase(),
        )
    }

    @Test
    fun executedToolDoesNotObscureLaterText() {
        val complete = tool("shell", false).copy(output = listOf(UIMessagePart.Text("done")))
        assertEquals(
            GenerationPhase.Writing("reply"),
            listOf(complete, UIMessagePart.Text("reply")).generationPhase(),
        )
    }

    @Test
    fun earlierPendingApprovalIsNotHiddenByLaterCompletedTool() {
        val complete = tool("shell", false).copy(output = listOf(UIMessagePart.Text("done")))
        assertTrue(listOf(tool("sensitive"), complete).generationPhase() is GenerationPhase.AwaitingApproval)
    }
}
