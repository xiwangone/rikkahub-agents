package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [buildWorkspaceReminder] — the pure state -> system-prompt selector.
 *
 * Contract:
 * - bound + READY            -> full `<workspace>` block advertising the workspace_* tools
 * - bound + not READY        -> `<workspace-setup>` telling the model how to guide the user to
 *                               install/repair the rootfs (tailored per DISABLED/INSTALLING/BROKEN)
 * - unbound, workspaces exist -> `<workspace-setup>` telling the model how to guide binding via +
 * - no workspace at all       -> null (nothing injected)
 *
 * Note: the READY block is the only one containing the exact tag `<workspace>`; the guidance
 * blocks use `<workspace-setup>`, so `contains("<workspace>")` distinguishes "tools live" from
 * "tools unavailable, here's how to enable".
 */
class WorkspaceReminderTransformerTest {

    private fun workspace(status: WorkspaceShellStatus, name: String = "demo") = WorkspaceEntity(
        id = "id-$name",
        name = name,
        root = "id-$name",
        shellStatus = status.name,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `bound and READY yields the full workspace block with tools`() {
        val prompt = buildWorkspaceReminder(workspace(WorkspaceShellStatus.READY, "proj"), hasAnyWorkspace = true)
        requireNotNull(prompt)
        assertTrue(prompt.contains("<workspace>"))
        assertTrue(prompt.contains("proj"))
        assertTrue(prompt.contains("workspace_shell"))
    }

    @Test
    fun `bound but DISABLED yields setup guidance to install the rootfs`() {
        val prompt = buildWorkspaceReminder(workspace(WorkspaceShellStatus.DISABLED), hasAnyWorkspace = true)
        requireNotNull(prompt)
        assertTrue(prompt.contains("<workspace-setup>"))
        // must NOT masquerade as the tools-live block
        assertFalse(prompt.contains("<workspace>"))
        assertTrue(prompt.contains("install its rootfs"))
        assertTrue(prompt.contains(WorkspaceShellStatus.DISABLED.name))
    }

    @Test
    fun `bound but INSTALLING tells the user to wait`() {
        val prompt = buildWorkspaceReminder(workspace(WorkspaceShellStatus.INSTALLING), hasAnyWorkspace = true)
        requireNotNull(prompt)
        assertTrue(prompt.contains("<workspace-setup>"))
        assertTrue(prompt.contains("installing"))
    }

    @Test
    fun `bound but BROKEN tells the user to reinstall or repair`() {
        val prompt = buildWorkspaceReminder(workspace(WorkspaceShellStatus.BROKEN), hasAnyWorkspace = true)
        requireNotNull(prompt)
        assertTrue(prompt.contains("<workspace-setup>"))
        assertTrue(prompt.contains("broken"))
    }

    @Test
    fun `unbound but workspaces exist yields binding guidance`() {
        val prompt = buildWorkspaceReminder(workspace = null, hasAnyWorkspace = true)
        requireNotNull(prompt)
        assertTrue(prompt.contains("<workspace-setup>"))
        assertFalse(prompt.contains("<workspace>"))
        assertTrue(prompt.contains("+ button"))
    }

    @Test
    fun `no workspace at all injects nothing`() {
        val prompt = buildWorkspaceReminder(workspace = null, hasAnyWorkspace = false)
        assertNull(prompt)
    }

    // ---- 注入位置（injectWorkspaceContext）：不改写 system，插在最后一条 user 之前 ----

    private fun userMessage(text: String) =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun systemMessage(text: String) =
        UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(text)))

    private fun textOf(message: UIMessage) =
        message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    @Test
    fun `injects before the last user message without touching system`() {
        val messages = listOf(systemMessage("SYS"), userMessage("first"), userMessage("latest"))
        val result = injectWorkspaceContext(messages, "<workspace>ctx</workspace>")
        assertEquals(4, result.size)
        // system 消息原样保留（未被改写、未被打上合成标记）
        assertEquals("SYS", textOf(result[0]))
        assertFalse(result[0].isSynthetic)
        assertEquals("first", textOf(result[1]))
        // 注入物紧邻最后一条 user 之前
        assertTrue(result[2].isSynthetic)
        assertEquals("<workspace>ctx</workspace>", textOf(result[2]))
        assertEquals("latest", textOf(result[3]))
    }

    @Test
    fun `appends to the end when there is no user message`() {
        val result = injectWorkspaceContext(listOf(systemMessage("SYS")), "ctx")
        assertEquals(2, result.size)
        assertEquals("SYS", textOf(result[0]))
        assertEquals("ctx", textOf(result[1]))
        assertTrue(result[1].isSynthetic)
    }

    @Test
    fun `next turn injects again right before the newest user message`() {
        val messages = listOf(systemMessage("SYS"), userMessage("q1"), userMessage("q2"))
        val result = injectWorkspaceContext(messages, "ctx-v2")
        assertEquals(4, result.size)
        assertEquals(1, result.count { it.isSynthetic })
        assertTrue(result[2].isSynthetic)
        assertEquals("ctx-v2", textOf(result[2]))
        assertEquals("q2", textOf(result[3]))
    }
}
