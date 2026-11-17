package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Covers DirectModeActionRunner.run() outcome mapping. The focus is the tool-unavailable
 * path: a direct-mode cron job validates its tool list at creation time, but the assistant's
 * enabled-tools set can change later. When a referenced tool is no longer available at fire
 * time the runner must produce a FAILED outcome whose errorMessage NAMES the missing tool,
 * so the failed run-history row tells the user exactly what to re-enable.
 */
class DirectModeActionRunnerRunTest {

    private fun fakeTool(name: String): Tool = Tool(
        name = name,
        description = "fake",
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    private fun action(tool: String): DirectModeActionRunner.Action =
        DirectModeActionRunner.Action(tool, JsonObject(emptyMap()))

    @Test
    fun `all tools available yields success`() = runBlocking {
        val runner = DirectModeActionRunner(kotlinx.serialization.json.Json) { me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.HeadlessApprovalSnapshot.NONE }
        val seq = runner.run(
            actions = listOf(action("send_notification")),
            availableTools = listOf(fakeTool("send_notification")),
        )
        assertEquals("success", seq.finalOutcome)
        assertNull(seq.errorMessage)
    }

    @Test
    fun `unavailable tool yields failed outcome naming the tool`() = runBlocking {
        val runner = DirectModeActionRunner(kotlinx.serialization.json.Json) { me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.HeadlessApprovalSnapshot.NONE }
        val seq = runner.run(
            actions = listOf(action("send_notification")),
            availableTools = emptyList(), // tool was disabled after job creation
        )
        assertEquals("failed", seq.finalOutcome)
        val err = requireNotNull(seq.errorMessage)
        assertTrue("error uses the tool_unavailable code", err.contains("tool_unavailable"))
        assertTrue("error names the exact missing tool", err.contains("send_notification"))
        assertTrue("error names the action index", err.contains("action 0"))
    }

    @Test
    fun `unavailable tool in second action is still reported by name`() = runBlocking {
        val runner = DirectModeActionRunner(kotlinx.serialization.json.Json) { me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.HeadlessApprovalSnapshot.NONE }
        val seq = runner.run(
            actions = listOf(action("get_battery"), action("send_sms")),
            availableTools = listOf(fakeTool("get_battery")), // send_sms missing
        )
        assertEquals("failed", seq.finalOutcome)
        val err = requireNotNull(seq.errorMessage)
        assertTrue("error names the missing tool", err.contains("send_sms"))
        assertTrue("error uses the tool_unavailable code", err.contains("tool_unavailable"))
        assertTrue("error points at action index 1", err.contains("action 1"))
    }

    private fun gatedTool(name: String): Tool = Tool(
        name = name,
        description = "fake gated",
        needsApproval = { true },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    private fun runnerWith(snapshot: ToolApprovalDefaults.HeadlessApprovalSnapshot) =
        DirectModeActionRunner(kotlinx.serialization.json.Json) { snapshot }

    // ── Headless authorisation (A1) ──────────────────────────────────────────

    @Test
    fun `NO_ALWAYS_ALLOW tool is skipped even with YOLO on`() = runBlocking {
        val runner = runnerWith(
            ToolApprovalDefaults.HeadlessApprovalSnapshot(yoloEnabled = true, alwaysAllowGrants = emptySet()),
        )
        val seq = runner.run(
            actions = listOf(action("vault_ssh_exec")),
            availableTools = listOf(gatedTool("vault_ssh_exec")),
        )
        assertEquals("failed", seq.finalOutcome)
        assertEquals(listOf("vault_ssh_exec"), seq.skippedTools)
        val err = requireNotNull(seq.errorMessage)
        assertTrue(err.contains("authorization_required"))
        assertTrue(err.contains("can never run unattended"))
    }

    @Test
    fun `gated tool runs with a persistent grant`() = runBlocking {
        val runner = runnerWith(
            ToolApprovalDefaults.HeadlessApprovalSnapshot(
                yoloEnabled = false,
                alwaysAllowGrants = setOf("ssh_exec"),
            ),
        )
        val seq = runner.run(
            actions = listOf(action("ssh_exec")),
            availableTools = listOf(gatedTool("ssh_exec")),
        )
        assertEquals("success", seq.finalOutcome)
        assertTrue(seq.skippedTools.isEmpty())
    }

    @Test
    fun `gated tool without a grant is skipped with a visible record`() = runBlocking {
        val runner = runnerWith(ToolApprovalDefaults.HeadlessApprovalSnapshot.NONE)
        val seq = runner.run(
            actions = listOf(action("ssh_exec")),
            availableTools = listOf(gatedTool("ssh_exec")),
        )
        assertEquals("failed", seq.finalOutcome)
        assertEquals(listOf("ssh_exec"), seq.skippedTools)
        val err = requireNotNull(seq.errorMessage)
        assertTrue(err.contains("authorization_required"))
        assertTrue(err.contains("needs authorization first"))
    }

    @Test
    fun `partial skip still succeeds but names the skipped tool`() = runBlocking {
        val runner = runnerWith(ToolApprovalDefaults.HeadlessApprovalSnapshot.NONE)
        val seq = runner.run(
            actions = listOf(action("send_notification"), action("ssh_exec")),
            availableTools = listOf(fakeTool("send_notification"), gatedTool("ssh_exec")),
        )
        assertEquals("success", seq.finalOutcome)
        assertEquals(listOf("ssh_exec"), seq.skippedTools)
        assertTrue(requireNotNull(seq.errorMessage).contains("ssh_exec"))
    }

    /**
     * A cancelled tool must unwind the run as a real cancellation, not get turned into a
     * `failed` SequenceResult. WorkflowActionRunner already re-throws CancellationException
     * for this exact reason (see its `run()`); DirectModeActionRunner did not.
     */
    @Test
    fun `cancellation from a tool propagates instead of becoming a Failed outcome`() = runBlocking {
        val runner = DirectModeActionRunner(kotlinx.serialization.json.Json) { me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.HeadlessApprovalSnapshot.NONE }
        val cancellingTool = Tool(
            name = "boom",
            description = "fake",
            execute = { throw CancellationException("run cancelled") },
        )
        var caught: CancellationException? = null
        try {
            runner.run(actions = listOf(action("boom")), availableTools = listOf(cancellingTool))
            fail("expected CancellationException to propagate, not be swallowed into a SequenceResult")
        } catch (c: CancellationException) {
            caught = c
        }
        assertTrue("must be the real CancellationException, not a converted Failed step", caught != null)
    }
}
