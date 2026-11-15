package me.rerere.rikkahub.data.ai.tools

import kotlin.uuid.Uuid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批分级的两条边界：
 *
 * 1. **本会话允许（💬）对任何工具都生效** —— 包括 NO_ALWAYS_ALLOW 里的高危工具；
 * 2. **但全局 YOLO / 持久「总是允许」对 NO_ALWAYS_ALLOW 仍然无效** ——
 *    长期或无人值守式的放行不允许覆盖这些工具。
 *
 * 会话级授权的安全边界全在 [ToolApprovalAllowList]：粒度 (conversationId, toolName)、
 * 内存态、/new 清零、可单条撤销。
 */
class SessionGrantableApprovalTest {

    private val conv = Uuid.parse("00000000-0000-4000-8000-000000000001")

    @After
    fun tearDown() {
        ToolApprovalAllowList.clearChat(conv)
    }

    @Test
    fun `chat scope accepts every tool including high-risk ones`() {
        val all = ToolApprovalDefaults.NO_ALWAYS_ALLOW + listOf("workspace_shell", "ssh_exec_saved", "read_file")
        for (tool in all) {
            assertTrue("$tool should accept chat scope", ToolApprovalDefaults.allowsChatScope(tool))
        }
    }

    @Test
    fun `chat-scope grant is per conversation and revocable`() {
        assertFalse(ToolApprovalAllowList.isAllowedForChat(conv, "vault_ssh_exec"))

        ToolApprovalAllowList.grantForChat(conv, "vault_ssh_exec")
        assertTrue(ToolApprovalAllowList.isAllowedForChat(conv, "vault_ssh_exec"))
        // 只影响被授予的那一个工具
        assertFalse(ToolApprovalAllowList.isAllowedForChat(conv, "eval_javascript"))

        ToolApprovalAllowList.revokeForChat(conv, "vault_ssh_exec")
        assertFalse(ToolApprovalAllowList.isAllowedForChat(conv, "vault_ssh_exec"))
    }

    @Test
    fun `clearing a chat drops all grants`() {
        ToolApprovalAllowList.grantForChat(conv, "vault_ssh_exec")
        ToolApprovalAllowList.grantForChat(conv, "keystore_decrypt")
        assertEquals(2, ToolApprovalAllowList.listForChat(conv).size)

        ToolApprovalAllowList.clearChat(conv)
        assertTrue(ToolApprovalAllowList.listForChat(conv).isEmpty())
    }

    @Test
    fun `high-risk tools never become auto-approvable by blanket paths`() {
        for (tool in ToolApprovalDefaults.NO_ALWAYS_ALLOW) {
            assertFalse(
                "$tool must not be auto-approvable by YOLO / headless",
                ToolApprovalDefaults.isAutoApprovable(tool),
            )
            assertFalse(
                "$tool must not offer persistent always-allow",
                ToolApprovalDefaults.allowsAlwaysAllow(tool),
            )
        }
    }
}
