package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless authorisation — the central policy shared by the cron direct-mode
 * runner, the workflow runner, and the creation-time validators (A1).
 *
 * Unattended fires have no conversation, so they recognise exactly two
 * authorisations: global YOLO and a persistent "Always Allow" grant.
 * Session-scoped ("Allow for this chat") and one-shot approvals do not
 * apply, and the 16 NO_ALWAYS_ALLOW tools never pass (user decision
 * 2026-11-17: "16 个永不无人值守").
 */
class ToolApprovalHeadlessTest {

    private val yolo = ToolApprovalDefaults.HeadlessApprovalSnapshot(
        yoloEnabled = true,
        alwaysAllowGrants = emptySet(),
    )
    private val grantedSsh = ToolApprovalDefaults.HeadlessApprovalSnapshot(
        yoloEnabled = false,
        alwaysAllowGrants = setOf("ssh_exec"),
    )
    private val none = ToolApprovalDefaults.HeadlessApprovalSnapshot.NONE

    @Test
    fun `YOLO passes an ordinary gated tool`() {
        assertTrue(ToolApprovalDefaults.isHeadlessAutoApproved("ssh_exec", yolo))
    }

    @Test
    fun `persistent grant passes its tool`() {
        assertTrue(ToolApprovalDefaults.isHeadlessAutoApproved("ssh_exec", grantedSsh))
    }

    @Test
    fun `persistent grant does not pass another tool`() {
        assertFalse(ToolApprovalDefaults.isHeadlessAutoApproved("termux_run_command", grantedSsh))
    }

    @Test
    fun `deny-all snapshot passes nothing`() {
        assertFalse(ToolApprovalDefaults.isHeadlessAutoApproved("ssh_exec", none))
        assertFalse(ToolApprovalDefaults.isHeadlessAutoApproved("post_notification", none))
    }

    @Test
    fun `NO_ALWAYS_ALLOW never passes even with YOLO`() {
        for (name in listOf("vault_ssh_exec", "vault_http_exec", "mcp_add", "save_ssh_host")) {
            assertFalse("$name must never run unattended", ToolApprovalDefaults.isHeadlessAutoApproved(name, yolo))
        }
    }

    @Test
    fun `NO_ALWAYS_ALLOW never passes even with a grant`() {
        val granted = ToolApprovalDefaults.HeadlessApprovalSnapshot(
            yoloEnabled = false,
            alwaysAllowGrants = setOf("vault_ssh_exec"),
        )
        assertFalse(ToolApprovalDefaults.isHeadlessAutoApproved("vault_ssh_exec", granted))
    }

    @Test
    fun `workspace tools only pass with YOLO, never with a grant`() {
        assertTrue(ToolApprovalDefaults.isHeadlessAutoApproved("workspace_shell", yolo))
        val granted = ToolApprovalDefaults.HeadlessApprovalSnapshot(
            yoloEnabled = false,
            alwaysAllowGrants = setOf("workspace_shell"),
        )
        assertFalse(ToolApprovalDefaults.isHeadlessAutoApproved("workspace_shell", granted))
    }

    @Test
    fun `ask_user never passes headless`() {
        assertFalse(ToolApprovalDefaults.isHeadlessAutoApproved("ask_user", yolo))
    }

    @Test
    fun `requiresHeadlessAuthorization covers gated and never-allowed tools only`() {
        assertTrue(ToolApprovalDefaults.requiresHeadlessAuthorization("ssh_exec"))
        assertTrue(ToolApprovalDefaults.requiresHeadlessAuthorization("vault_ssh_exec"))
        assertFalse(ToolApprovalDefaults.requiresHeadlessAuthorization("get_tool_schema"))
        assertFalse(ToolApprovalDefaults.requiresHeadlessAuthorization("show_toast"))
    }

    @Test
    fun `workspace_shell and workspace_run_background are in ALWAYS_ASK`() {
        assertTrue("workspace_shell" in ToolApprovalDefaults.ALWAYS_ASK)
        assertTrue("workspace_run_background" in ToolApprovalDefaults.ALWAYS_ASK)
        assertTrue(ToolApprovalDefaults.requiresApproval("workspace_shell"))
        assertTrue(ToolApprovalDefaults.requiresApproval("workspace_run_background"))
    }

    @Test
    fun `workspace_shell and workspace_run_background stay auto-approvable`() {
        // They are gated, not banned: a YOLO / persistent-grant fire may run
        // them. (The grant half never applies to workspace_ names by the
        // existing ToolApprovalPreferences design — only YOLO passes them
        // headless — but the set membership must not drift into
        // NO_ALWAYS_ALLOW.)
        assertTrue(ToolApprovalDefaults.isAutoApprovable("workspace_shell"))
        assertTrue(ToolApprovalDefaults.isAutoApprovable("workspace_run_background"))
        assertFalse("workspace_shell" in ToolApprovalDefaults.NO_ALWAYS_ALLOW)
        assertFalse("workspace_run_background" in ToolApprovalDefaults.NO_ALWAYS_ALLOW)
    }

    @Test
    fun `privacy-reading tools are in ALWAYS_ASK`() {
        for (name in listOf("clipboard_tool", "keyboard_read_field", "keyboard_editor_info")) {
            assertTrue("$name must be in ALWAYS_ASK", name in ToolApprovalDefaults.ALWAYS_ASK)
            assertTrue("$name must requireApproval", ToolApprovalDefaults.requiresApproval(name))
        }
    }

    @Test
    fun `workspace approval is central plus per-workspace override`() {
        // Central gate wins even when the workspace says "no approval".
        assertTrue(resolveWorkspaceToolApprovalWithCentral("workspace_shell", mapOf("workspace_shell" to false)))
        // A workspace override can only ADD approval for a centrally-free tool.
        assertTrue(resolveWorkspaceToolApprovalWithCentral("workspace_read_file", mapOf("workspace_read_file" to true)))
        assertFalse(resolveWorkspaceToolApprovalWithCentral("workspace_read_file", emptyMap()))
        // The workspace override alone cannot gate a tool the centre leaves free
        // unless it says so — and it cannot ungate a central gate.
        assertFalse(resolveWorkspaceToolApprovalWithCentral("workspace_list", mapOf("workspace_list" to false)))
    }

    @Test
    fun `headless skip reason names the tool and the remedy`() {
        val never = ToolApprovalDefaults.headlessSkipReason("vault_ssh_exec")
        assertTrue(never.contains("vault_ssh_exec"))
        assertTrue(never.contains("can never run unattended"))
        val grant = ToolApprovalDefaults.headlessSkipReason("ssh_exec")
        assertTrue(grant.contains("ssh_exec"))
        assertTrue(grant.contains("Always Allow"))
        assertTrue(grant.contains("enable YOLO"))
    }

    @Test
    fun `authorization detail lists ungranted tools and the remedy`() {
        val detail = ToolApprovalDefaults.headlessAuthorizationDetail(
            toolNames = listOf("ssh_exec_saved", "show_toast"),
            snapshot = none,
            kind = "workflow",
        )
        assertNotNull(detail)
        assertTrue(detail!!.contains("ssh_exec_saved"))
        assertTrue(detail.contains("Always Allow"))
        assertTrue(detail.contains("workflow"))
        assertFalse(detail.contains("show_toast"))
    }

    @Test
    fun `authorization detail is null when everything is covered`() {
        val granted = ToolApprovalDefaults.HeadlessApprovalSnapshot(
            yoloEnabled = false,
            alwaysAllowGrants = setOf("ssh_exec_saved"),
        )
        assertNull(
            ToolApprovalDefaults.headlessAuthorizationDetail(
                toolNames = listOf("ssh_exec_saved", "show_toast"),
                snapshot = granted,
                kind = "job",
            ),
        )
    }

    @Test
    fun `authorization detail keeps NO_ALWAYS_ALLOW even under YOLO`() {
        val detail = ToolApprovalDefaults.headlessAuthorizationDetail(
            toolNames = listOf("ssh_exec_saved", "vault_ssh_exec"),
            snapshot = yolo,
            kind = "workflow",
        )
        // YOLO covers ssh_exec_saved, but vault_ssh_exec can never run
        // unattended — only the grantable half disappears from the detail.
        assertNotNull(detail)
        assertTrue(detail!!.contains("vault_ssh_exec"))
        assertTrue(detail.contains("can never run unattended"))
        assertFalse(detail.contains("ssh_exec_saved"))
    }
}
