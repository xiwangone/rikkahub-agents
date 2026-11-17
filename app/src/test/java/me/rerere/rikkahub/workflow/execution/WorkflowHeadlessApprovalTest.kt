package me.rerere.rikkahub.workflow.execution

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.workflow.model.WorkflowAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless authorisation for WorkflowActionRunner (A1): same central policy
 * as DirectModeActionRunner — YOLO or a persistent "Always Allow" grant
 * passes, NO_ALWAYS_ALLOW never passes, an unauthorised action is skipped
 * (named in the summary) instead of executed, and a fire where every action
 * was skipped records FAILED.
 */
class WorkflowHeadlessApprovalTest {

    private fun gatedTool(name: String): Tool = Tool(
        name = name,
        description = "fake gated",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        needsApproval = { true },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    private fun readOnlyTool(name: String): Tool = Tool(
        name = name,
        description = "fake read-only",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    private fun runnerWith(snapshot: ToolApprovalDefaults.HeadlessApprovalSnapshot) =
        WorkflowActionRunner({ snapshot })

    private fun action(tool: String, command: String? = null) = WorkflowAction(
        tool = tool,
        args = buildJsonObject {
            if (command != null) put("command", command)
        },
        timeoutSeconds = 10,
    )

    @Test
    fun `NO_ALWAYS_ALLOW tool is skipped even with YOLO on`() = runBlocking {
        val runner = runnerWith(
            ToolApprovalDefaults.HeadlessApprovalSnapshot(yoloEnabled = true, alwaysAllowGrants = emptySet()),
        )
        val result = runner.run(
            listOf(action("vault_ssh_exec")),
            listOf(gatedTool("vault_ssh_exec")),
        )
        assertFalse(result.success)
        assertEquals(listOf("vault_ssh_exec"), result.skippedTools)
        assertTrue(requireNotNull(result.error).contains("authorization_required"))
        assertTrue(requireNotNull(result.error).contains("can never run unattended"))
    }

    @Test
    fun `gated tool runs with a persistent grant`() = runBlocking {
        val runner = runnerWith(
            ToolApprovalDefaults.HeadlessApprovalSnapshot(
                yoloEnabled = false,
                alwaysAllowGrants = setOf("ssh_exec"),
            ),
        )
        val result = runner.run(
            listOf(action("ssh_exec", "uname -a")),
            listOf(gatedTool("ssh_exec")),
        )
        assertTrue("expected success: ${result.error}", result.success)
        assertTrue(result.skippedTools.isEmpty())
    }

    @Test
    fun `gated tool without a grant is skipped and the fire fails`() = runBlocking {
        val runner = runnerWith(ToolApprovalDefaults.HeadlessApprovalSnapshot.NONE)
        val result = runner.run(
            listOf(action("ssh_exec", "uname -a")),
            listOf(gatedTool("ssh_exec")),
        )
        assertFalse(result.success)
        assertEquals(listOf("ssh_exec"), result.skippedTools)
        assertTrue(requireNotNull(result.error).contains("authorization_required"))
        assertTrue(result.summary.contains("needs authorization first"))
    }

    @Test
    fun `partial skip still succeeds but names the skipped tool in the summary`() = runBlocking {
        val runner = runnerWith(ToolApprovalDefaults.HeadlessApprovalSnapshot.NONE)
        val result = runner.run(
            listOf(action("show_toast"), action("ssh_exec", "uname -a")),
            listOf(readOnlyTool("show_toast"), gatedTool("ssh_exec")),
        )
        assertTrue("expected success: ${result.error}", result.success)
        assertEquals(listOf("ssh_exec"), result.skippedTools)
        assertTrue(result.summary.contains("ssh_exec"))
        assertTrue(result.summary.contains("authorization_required"))
    }
}
