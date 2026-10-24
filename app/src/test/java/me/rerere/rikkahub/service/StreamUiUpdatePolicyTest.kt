package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUiUpdatePolicyTest {
    @Test
    fun completedOutputForSameToolIdBypassesWindowButTextDeltaDoesNot() {
        val empty = toolMessage("call-a")
        val complete = toolMessage("call-a", output = listOf(UIMessagePart.Text("2"))).copy(id = empty.id)

        assertTrue(hasCompletedToolOutput(listOf(empty), listOf(complete)))
        assertTrue(shouldCommitStreamUiUpdate(119, 100, 120, completedToolOutput = true))
        assertFalse(shouldCommitStreamUiUpdate(119, 100, 120, completedToolOutput = false))
        assertTrue(shouldCommitStreamUiUpdate(220, 100, 120, completedToolOutput = false))
    }

    @Test
    fun anotherToolIdOrRepeatedIdenticalOutputDoesNotForceAnExtraFrame() {
        val emptyA = toolMessage("call-a")
        val completedB = toolMessage("call-b", output = listOf(UIMessagePart.Text("2")))
        val alreadyComplete = toolMessage("call-a", output = listOf(UIMessagePart.Text("2")))

        assertFalse(hasCompletedToolOutput(listOf(emptyA), listOf(completedB)))
        assertFalse(hasCompletedToolOutput(listOf(alreadyComplete), listOf(alreadyComplete.copy())))
        assertFalse(hasCompletedToolOutput(listOf(emptyA), listOf(emptyA.copy())))
    }

    @Test
    fun emptyPendingOrExecutingToolsRemainOnExistingUrgentStatePath() {
        val pending = toolMessage("pending", approvalState = ToolApprovalState.Pending)
        val executing = toolMessage(
            "executing",
            approvalState = ToolApprovalState.Approved,
            executionStartedAt = 123L,
        )

        assertFalse(hasCompletedToolOutput(listOf(pending), listOf(pending.copy())))
        assertFalse(hasCompletedToolOutput(listOf(executing), listOf(executing.copy())))
    }

    @Test
    fun multiAssistantSnapshotsMatchOnlyTheSameToolCallId() {
        val earlierAssistant = toolMessage("call-a")
        val laterAssistant = toolMessage("call-b")
        val updatedEarlier = earlierAssistant.copy(
            parts = listOf(UIMessagePart.Tool(
                toolCallId = "call-a",
                toolName = "tool",
                input = "{}",
                output = listOf(UIMessagePart.Text("completed")),
            )),
        )

        assertFalse(hasCompletedToolOutput(listOf(earlierAssistant, laterAssistant), listOf(laterAssistant)))
        assertTrue(hasCompletedToolOutput(listOf(earlierAssistant, laterAssistant), listOf(updatedEarlier, laterAssistant)))
    }

    @Test
    fun reusedCallIdInAnotherMessageDoesNotForceAnExtraFrame() {
        val earlier = toolMessage("reused-call")
        val later = toolMessage("reused-call", output = listOf(UIMessagePart.Text("2")))

        assertFalse(hasCompletedToolOutput(listOf(earlier), listOf(later)))
        assertFalse(hasCompletedToolOutput(listOf(earlier, later), listOf(earlier, later.copy())))
    }

    private fun toolMessage(
        id: String,
        output: List<UIMessagePart> = emptyList(),
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
        executionStartedAt: Long? = null,
    ) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = id,
                toolName = "tool",
                input = "{}",
                output = output,
                approvalState = approvalState,
                executionStartedAt = executionStartedAt,
            ),
        ),
    )
}
