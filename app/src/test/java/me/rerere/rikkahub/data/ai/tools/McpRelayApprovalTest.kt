package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 中转工具审批统一（issue #24）：单工具开关说了算。
 *
 * 根因：ChatToolFactory 注册 MCP 中转工具时用的是
 * `requiresApproval(mcpToolName) || tool.needsApproval`，而 requiresApproval
 * 对 `mcp__*` 前缀恒为真，于是开关只能把审批加上去、永远去不掉。
 * 修法：名字规则与开关取与（[ToolApprovalDefaults.mcpRelayNeedsApproval]），
 * 新发现工具默认 true（[McpTool.needsApproval]）。
 */
class McpRelayApprovalTest {

    private val relayName = "mcp__test-server__search"

    @Test fun `relay flag false means no approval`() {
        assertFalse(ToolApprovalDefaults.mcpRelayNeedsApproval(relayName, false))
    }

    @Test fun `relay flag true means approval`() {
        assertTrue(ToolApprovalDefaults.mcpRelayNeedsApproval(relayName, true))
    }

    @Test fun `relay decision follows the flag, not the name rule alone`() {
        // 名字规则对 mcp__* 恒为真；开关关掉就必须不审批（#24 的核心断言）。
        assertFalse(ToolApprovalDefaults.mcpRelayNeedsApproval("mcp__a__b", false))
        assertTrue(ToolApprovalDefaults.mcpRelayNeedsApproval("mcp__a__b", true))
    }

    @Test fun `non-mcp name without always-ask never approves via relay rule`() {
        assertFalse(ToolApprovalDefaults.mcpRelayNeedsApproval("device_info", true))
        assertFalse(ToolApprovalDefaults.mcpRelayNeedsApproval("device_info", false))
    }

    @Test fun `newly discovered mcp tools default to needing approval`() {
        assertTrue(McpTool(name = "search").needsApproval)
        assertTrue(McpTool(name = "search", enable = true).needsApproval)
    }

    @Test fun `explicitly cleared flag is preserved`() {
        assertFalse(McpTool(name = "search", needsApproval = false).needsApproval)
        assertTrue(McpTool(name = "search", needsApproval = true).needsApproval)
    }

    // 序列化约定（JsonInstant，encodeDefaults=true）：已存 JSON 里该字段是显式
    // 写出的，改默认值不影响存量值；缺失字段的极旧存量落到新默认值 true。
    @Test fun `stored tool without needsApproval field decodes to true`() {
        val decoded = JsonInstant.decodeFromString<McpTool>(
            """{"enable":true,"name":"search","description":null,"inputSchema":null}""",
        )
        assertTrue(decoded.needsApproval)
    }

    @Test fun `stored explicit false survives the default flip`() {
        val decoded = JsonInstant.decodeFromString<McpTool>(
            """{"enable":true,"name":"search","description":null,"inputSchema":null,"needsApproval":false}""",
        )
        assertFalse(decoded.needsApproval)
    }

    @Test fun `stored explicit true decodes to true`() {
        val decoded = JsonInstant.decodeFromString<McpTool>(
            """{"enable":true,"name":"search","description":null,"inputSchema":null,"needsApproval":true}""",
        )
        assertTrue(decoded.needsApproval)
    }

    @Test fun `encoding a new tool writes needsApproval true explicitly`() {
        val json = JsonInstant.encodeToString(McpTool(name = "search"))
        assertTrue(json.contains("\"needsApproval\":true"))
    }

    // Phase 13 — External Automation mutations: privilege escalation surface,
    // per-call confirmation only; the read-only status tool stays grantable.
    @Test fun `external automation mutations reject always-allow scope`() {
        for (name in listOf(
            "external_automation_set_enabled",
            "external_automation_add_trusted_package",
            "external_automation_remove_trusted_package",
        )) {
            assertTrue("$name should be in ALWAYS_ASK", name in ToolApprovalDefaults.ALWAYS_ASK)
            assertTrue("$name should be in NO_ALWAYS_ALLOW", name in ToolApprovalDefaults.NO_ALWAYS_ALLOW)
            assertFalse("$name should never auto-approve", ToolApprovalDefaults.isAutoApprovable(name))
            assertFalse("$name should not allow always-allow", ToolApprovalDefaults.allowsAlwaysAllow(name))
        }
    }

    @Test fun `external automation status stays read-only and grantable`() {
        assertFalse("external_automation_status" in ToolApprovalDefaults.NO_ALWAYS_ALLOW)
        assertFalse("external_automation_status" in ToolApprovalDefaults.ALWAYS_ASK)
        assertTrue(ToolApprovalDefaults.isAutoApprovable("external_automation_status"))
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("external_automation_status"))
    }

    @Test fun `no-always-allow set still rejects auto-approval for every member`() {
        for (name in ToolApprovalDefaults.NO_ALWAYS_ALLOW) {
            assertFalse("$name should never auto-approve", ToolApprovalDefaults.isAutoApprovable(name))
        }
    }
}
