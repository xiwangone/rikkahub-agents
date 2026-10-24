package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.ui.components.message.tools.ToolStepStatus
import me.rerere.rikkahub.ui.components.message.tools.resolveToolStepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rendering-only fixtures: no provider requests or tool execution. */
class GroupMessagePartsTest {
    private fun text(s: String) = UIMessagePart.Text(s)
    private fun reasoning(s: String) = UIMessagePart.Reasoning(reasoning = s)
    private fun tool(id: String, output: List<UIMessagePart> = emptyList()) =
        UIMessagePart.Tool(toolCallId = id, toolName = "synthetic_read_only", input = "{}", output = output)
    private fun serverTool(id: String, status: ServerToolStatus = ServerToolStatus.COMPLETED) =
        UIMessagePart.ServerTool(toolCallId = id, toolName = "synthetic_server", status = status)
    private fun steps(parts: List<UIMessagePart>) =
        (parts.groupMessageParts().single() as MessagePartBlock.ThinkingBlock).steps

    @Test fun emptyListProducesNoBlocks() {
        assertTrue(emptyList<UIMessagePart>().groupMessageParts().isEmpty())
    }
    @Test fun singleTextPreservesIndex() {
        val block = listOf(text("hello")).groupMessageParts().single() as MessagePartBlock.ContentBlock
        assertEquals(0, block.index)
        assertEquals(text("hello"), block.part)
    }
    @Test fun reasoningAndServerToolShareTimeline() {
        val blocks = listOf(reasoning("think"), serverTool("s"), text("answer")).groupMessageParts()
        assertEquals(2, blocks.size)
        assertEquals(2, (blocks[0] as MessagePartBlock.ThinkingBlock).steps.size)
        assertEquals(2, (blocks[1] as MessagePartBlock.ContentBlock).index)
    }
    @Test fun alternatingReasoningAndToolsRemainOneTimeline() {
        val r1 = reasoning("a"); val t = tool("one"); val r2 = reasoning("b")
        assertEquals(listOf(ThinkingStep.ReasoningStep(r1), ThinkingStep.ToolStep(t), ThinkingStep.ReasoningStep(r2)), steps(listOf(r1, t, r2)))
    }
    @Test fun multipleToolsRetainInputOrder() {
        val a = tool("a"); val b = tool("b")
        assertEquals(listOf(ThinkingStep.ToolStep(a), ThinkingStep.ToolStep(b)), steps(listOf(a, b)))
    }
    @Test fun duplicateAndBlankToolIdsProduceDistinctStableComposeKeys() {
        val parts = listOf(tool("same"), tool("same"), tool(""), serverTool("same"), serverTool(""))
        val timeline = parts.groupMessageParts().single() as MessagePartBlock.ThinkingBlock
        val keys = timeline.steps.mapIndexed(::timelineStepComposeKey)

        assertEquals(listOf("tool:same:0", "tool:same:1", "tool::2", "server-tool:same:3", "server-tool::4"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }
    @Test fun separateTimelinesHaveStableDistinctSourcePositions() {
        val parts = listOf(reasoning("first"), text("middle"), tool("repeated"), text("again"), tool("repeated"))
        val timelines = parts.groupMessageParts().filterIsInstance<MessagePartBlock.ThinkingBlock>()

        assertEquals(listOf(0, 2, 4), timelines.map { it.startIndex })
        assertEquals(3, timelines.map { it.startIndex }.toSet().size)
    }
    @Test fun bodyTextIsNeverSwallowedIntoTimeline() {
        val blocks = listOf(reasoning("a"), tool("a"), text("intermediate"), reasoning("b"), tool("b"), text("final")).groupMessageParts()
        assertEquals(4, blocks.size)
        assertEquals(text("intermediate"), (blocks[1] as MessagePartBlock.ContentBlock).part)
        assertEquals(text("final"), (blocks[3] as MessagePartBlock.ContentBlock).part)
    }
    @Test fun contentIndicesRemainOriginal() {
        val blocks = listOf(text("a"), reasoning("r"), tool("t"), text("b"), text("c")).groupMessageParts()
        assertEquals(listOf(0, 3, 4), blocks.filterIsInstance<MessagePartBlock.ContentBlock>().map { it.index })
    }
    @Test fun trailingMixedStepsAreFlushed() {
        val blocks = listOf(text("intro"), reasoning("r"), tool("t")).groupMessageParts()
        assertEquals(2, (blocks.last() as MessagePartBlock.ThinkingBlock).steps.size)
    }
    @Test fun pendingApprovalSurvivesGrouping() {
        val pending = tool("p").copy(approvalState = ToolApprovalState.Pending)
        assertTrue((steps(listOf(reasoning("r"), pending)).last() as ThinkingStep.ToolStep).tool.isPending)
    }
    @Test fun completionUpdatesEarlierToolWithoutChangingTail() {
        val before = tool("p"); val tail = text("unchanged")
        val completed = before.copy(output = listOf(text("{\"success\":true}")))
        val old = listOf(before, tail).groupMessageParts()
        val next = listOf(completed, tail).groupMessageParts()
        assertFalse(((old.first() as MessagePartBlock.ThinkingBlock).steps.first() as ThinkingStep.ToolStep).tool.isExecuted)
        assertTrue(((next.first() as MessagePartBlock.ThinkingBlock).steps.first() as ThinkingStep.ToolStep).tool.isExecuted)
    }
    @Test fun errorsAreNotRewrittenAsSuccess() {
        val failed = tool("e", listOf(text("{\"success\":false,\"error\":\"synthetic_failure\"}")))
        assertEquals(failed, (steps(listOf(failed)).single() as ThinkingStep.ToolStep).tool)
    }
    @Test fun interruptedAttemptIsNotExecutedByGrouping() {
        val interrupted = tool("interrupted").copy(executionStartedAt = 1L)
        val rendered = (steps(listOf(interrupted)).single() as ThinkingStep.ToolStep).tool
        assertTrue(rendered.isInterruptedAttempt)
        assertFalse(rendered.isExecuted)
        assertEquals(
            ToolStepStatus.INTERRUPTED,
            resolveToolStepStatus(
                isPending = rendered.isPending,
                isDenied = rendered.approvalState is ToolApprovalState.Denied,
                isExecuted = rendered.isExecuted,
                success = null,
                exitCode = null,
                hasError = false,
                isInterruptedAttempt = rendered.isInterruptedAttempt,
            ),
        )
        assertEquals(interrupted, rendered)
    }
    @Test fun partialInputRemainsPartial() {
        val partial = tool("partial").copy(input = "{\"query\":")
        assertEquals(partial, (steps(listOf(partial)).single() as ThinkingStep.ToolStep).tool)
    }
    @Test fun failedServerToolRetainsFailure() {
        val s = serverTool("s", ServerToolStatus.FAILED)
        assertEquals(s, (steps(listOf(s)).single() as ThinkingStep.ServerToolStep).tool)
    }
    @Test fun toolStatusMappingDoesNotTurnFailureDenialOrCancellationIntoSuccessOrRunning() {
        fun status(
            isPending: Boolean = false,
            isDenied: Boolean = false,
            isExecuted: Boolean = false,
            success: Boolean? = null,
            isInterruptedAttempt: Boolean = false,
        ) = resolveToolStepStatus(
            isPending = isPending,
            isDenied = isDenied,
            isExecuted = isExecuted,
            success = success,
            exitCode = null,
            hasError = false,
            isInterruptedAttempt = isInterruptedAttempt,
        )

        assertEquals(ToolStepStatus.FAILED, status(isExecuted = true, success = false))
        assertEquals(ToolStepStatus.DENIED, status(isDenied = true))
        assertEquals(ToolStepStatus.NEEDS_APPROVAL, status(isPending = true))
        assertEquals(ToolStepStatus.INTERRUPTED, status(isInterruptedAttempt = true))
        assertEquals(ToolStepStatus.RUNNING, status(isExecuted = false))
        assertEquals(ToolStepStatus.COMPLETED, status(isExecuted = true))
    }
    @Test fun successfulChartSplitsTimelineInPlace() {
        val chart = tool("chart", listOf(text("{\"success\":true}\n[approval: auto]"))).copy(toolName = "chart_display")
        val blocks = listOf(reasoning("before"), tool("a"), chart, reasoning("after"), tool("b")).groupMessageParts()
        assertEquals(3, blocks.size)
        assertEquals(2, (blocks[1] as MessagePartBlock.ChartBlock).index)
        assertEquals(2, (blocks[0] as MessagePartBlock.ThinkingBlock).steps.size)
        assertEquals(2, (blocks[2] as MessagePartBlock.ThinkingBlock).steps.size)
    }
    @Test fun longRtlAndHostileTextIsOnlyData() {
        val hostile = "مرحبا <script>fetch('https://invalid.test')</script> ../ rm -rf / ".repeat(1000)
        val t = tool("hostile").copy(input = hostile, output = listOf(text(hostile)))
        val rendered = (steps(listOf(reasoning(hostile), t)).last() as ThinkingStep.ToolStep).tool
        assertEquals(hostile, rendered.input)
        assertEquals(hostile, (rendered.output.single() as UIMessagePart.Text).text)
    }
    @Test fun serializationRestoresMixedTimelineAndApproval() {
        val original: List<UIMessagePart> = listOf(reasoning("persist"), tool("pending").copy(approvalState = ToolApprovalState.Pending), serverTool("server"))
        val restored = JsonInstant.decodeFromString<List<UIMessagePart>>(JsonInstant.encodeToString(original))
        assertEquals(original, restored)
        assertEquals(3, steps(restored).size)
        assertTrue((steps(restored)[1] as ThinkingStep.ToolStep).tool.isPending)
    }
    @Test fun largeChainRetainsAllStepsWithoutMutatingInput() {
        val parts = (0 until 500).flatMap { listOf(reasoning("r$it"), tool("t$it")) }
        val snapshot = parts.toList()
        assertEquals(1000, steps(parts).size)
        assertEquals(snapshot, parts)
    }
}
