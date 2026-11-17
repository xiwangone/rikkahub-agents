package me.rerere.rikkahub.workflow.tools

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.workflow.db.WorkflowDao
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.db.WorkflowRunDao
import me.rerere.rikkahub.workflow.db.WorkflowRunEntity
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * workflow_create creation-time authorisation (A1): an unauthorised action
 * must be rejected with `authorization_required` before anything is
 * persisted, and the check lives in the create/update tools — not in
 * WorkflowJson.parse, whose signature is a detekt-baseline key.
 */
class WorkflowCreateApprovalTest {

    /** Only getById / upsert are ever reached by the create path. */
    private class FakeWorkflowDao : WorkflowDao {
        val upserted = mutableListOf<WorkflowEntity>()

        override fun observeAll(): Flow<List<WorkflowEntity>> = emptyFlow()
        override suspend fun listAll(): List<WorkflowEntity> = emptyList()
        override suspend fun listEnabled(): List<WorkflowEntity> = emptyList()
        override suspend fun getById(id: String): WorkflowEntity? = null
        override fun observeById(id: String): Flow<WorkflowEntity?> = emptyFlow()
        override suspend fun upsert(entity: WorkflowEntity) {
            upserted += entity
        }

        override suspend fun update(entity: WorkflowEntity): Unit = Unit
        override suspend fun deleteById(id: String): Int = 0
        override suspend fun setEnabled(id: String, enabled: Boolean, updatedAtMs: Long): Int = 0

        override suspend fun recordFire(
            id: String,
            firedAtMs: Long,
            status: String,
            errorMessage: String?,
            runsTodayCount: Int,
            runsTodayDate: String,
        ): Int = 0
    }

    private class FakeWorkflowRunDao : WorkflowRunDao {
        override suspend fun insert(entity: WorkflowRunEntity): Long = 0L
        override suspend fun lastN(workflowId: String, limit: Int): List<WorkflowRunEntity> = emptyList()

        override suspend fun trim(workflowId: String, keep: Int): Unit = Unit
        override suspend fun deleteAllFor(workflowId: String): Int = 0
        override suspend fun countCountedFiresSince(workflowId: String, sinceMs: Long): Int = 0

        override suspend fun lastActualFireAtMs(workflowId: String): Long? = null
    }

    private val knownTools = listOf("ssh_exec_saved", "vault_ssh_exec", "show_toast")
    private val none = ToolApprovalDefaults.HeadlessApprovalSnapshot.NONE
    private val yolo = ToolApprovalDefaults.HeadlessApprovalSnapshot(yoloEnabled = true, alwaysAllowGrants = emptySet())

    private fun execText(tool: me.rerere.ai.core.Tool, argsJson: String): String = runBlocking {
        val parts = tool.execute(Json.parseToJsonElement(argsJson))
        (parts.first { it is UIMessagePart.Text } as UIMessagePart.Text).text
    }

    private fun createArgs(tool: String): String =
        """{"definition":{"name":"W","trigger":{"type":"manual"},"actions":[{"tool":"$tool","args":{}}]}}"""

    private fun createTool(
        dao: FakeWorkflowDao,
        snapshot: ToolApprovalDefaults.HeadlessApprovalSnapshot,
    ): me.rerere.ai.core.Tool =
        workflowCreateTool(
            repository = WorkflowRepository(dao, FakeWorkflowRunDao()),
            knownToolNamesProvider = { knownTools },
            approvalSnapshot = { snapshot },
        )

    @Test fun `create rejects gated tool without authorization`() {
        val dao = FakeWorkflowDao()
        val out = execText(createTool(dao, none), createArgs("ssh_exec_saved"))
        assertTrue("expected authorization_required, got: $out", out.contains("authorization_required"))
        assertTrue("expected the tool to be named, got: $out", out.contains("ssh_exec_saved"))
        assertTrue("expected nothing persisted, got ${dao.upserted.size} upserts", dao.upserted.isEmpty())
    }

    @Test fun `create accepts gated tool with YOLO and persists`() {
        val dao = FakeWorkflowDao()
        val out = execText(createTool(dao, yolo), createArgs("ssh_exec_saved"))
        assertFalse("did not expect authorization_required, got: $out", out.contains("authorization_required"))
        assertTrue("expected one upsert, got ${dao.upserted.size}", dao.upserted.size == 1)
        assertTrue("expected ok envelope, got: $out", out.contains("\"ok\":true"))
    }

    @Test fun `create rejects NO_ALWAYS_ALLOW tool even with YOLO`() {
        val dao = FakeWorkflowDao()
        val out = execText(createTool(dao, yolo), createArgs("vault_ssh_exec"))
        assertTrue("expected authorization_required, got: $out", out.contains("authorization_required"))
        assertTrue("expected the tool to be named, got: $out", out.contains("vault_ssh_exec"))
        assertTrue("expected never-run wording, got: $out", out.contains("can never run unattended"))
        assertTrue("expected nothing persisted, got ${dao.upserted.size} upserts", dao.upserted.isEmpty())
    }
}
