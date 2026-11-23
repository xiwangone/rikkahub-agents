package me.rerere.rikkahub.data.ai.mcp.server

import me.rerere.rikkahub.data.ai.mcp.LocalMcpProfile
import me.rerere.rikkahub.data.ai.tools.LocalToolCatalog
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地 MCP 暴露集钳制与热重同步判定回归测试（纯函数，不含 IO 与 Android）。
 *
 * 关键性质：高风险工具永不进入暴露集；仅工具集变化时不重启服务。
 */
class LocalMcpServerManagerTest {

    private fun profile(
        port: Int = 8788,
        listenScope: String = "loopback",
        authTokenRef: String = "",
        allowedNetworks: String = "",
        allowedTools: List<LocalToolOption> = LocalToolCatalog.mcpExposed,
        name: String = "test",
    ) = LocalMcpProfile(
        id = "p1",
        name = name,
        port = port,
        allowedTools = allowedTools,
        listenScope = listenScope,
        allowedNetworks = allowedNetworks,
        authTokenRef = authTokenRef,
    )

    @Test
    fun clampKeepsSafeToolsInOrder() {
        val input = listOf(LocalToolOption.Files, LocalToolOption.TimeInfo, LocalToolOption.Toast)
        assertEquals(input, LocalMcpServerManager.clampExposedTools(input))
    }

    @Test
    fun clampDropsHighRiskTools() {
        val input =
            listOf(
                LocalToolOption.Files,
                LocalToolOption.VaultTools,
                LocalToolOption.VaultExportEnv,
                LocalToolOption.Shizuku,
                LocalToolOption.McpControl,
                LocalToolOption.CronJobs,
                LocalToolOption.Workflows,
                LocalToolOption.SubAgents,
                LocalToolOption.JsSkills,
                LocalToolOption.JavascriptEngine,
                LocalToolOption.AskUser,
                LocalToolOption.TelegramBot,
                LocalToolOption.TimeInfo,
            )
        assertEquals(
            listOf(LocalToolOption.Files, LocalToolOption.TimeInfo),
            LocalMcpServerManager.clampExposedTools(input),
        )
    }

    @Test
    fun clampEmptyStaysEmpty() {
        assertEquals(emptyList<LocalToolOption>(), LocalMcpServerManager.clampExposedTools(emptyList()))
    }

    @Test
    fun requiresRestart_sameProfile() {
        assertFalse(LocalMcpServerManager.requiresRestart(profile(), profile()))
    }

    @Test
    fun requiresRestart_toolsOnlyChange() {
        assertFalse(
            LocalMcpServerManager.requiresRestart(
                profile(),
                profile(allowedTools = listOf(LocalToolOption.Files)),
            ),
        )
    }

    @Test
    fun requiresRestart_nameOnlyChange() {
        assertFalse(LocalMcpServerManager.requiresRestart(profile(), profile(name = "renamed")))
    }

    @Test
    fun requiresRestart_portChange() {
        assertTrue(LocalMcpServerManager.requiresRestart(profile(), profile(port = 8799)))
    }

    @Test
    fun requiresRestart_listenScopeChange() {
        assertTrue(LocalMcpServerManager.requiresRestart(profile(), profile(listenScope = "lan")))
    }

    @Test
    fun requiresRestart_tokenChange() {
        assertTrue(
            LocalMcpServerManager.requiresRestart(
                profile(),
                profile(authTokenRef = "MCP_AUTH_TOKEN"),
            ),
        )
    }

    @Test
    fun requiresRestart_networksChange() {
        assertTrue(
            LocalMcpServerManager.requiresRestart(
                profile(),
                profile(allowedNetworks = "192.168.0.0/16"),
            ),
        )
    }

    @Test
    fun requiresRestart_nullEitherSide() {
        assertTrue(LocalMcpServerManager.requiresRestart(null, profile()))
        assertTrue(LocalMcpServerManager.requiresRestart(profile(), null))
        assertTrue(LocalMcpServerManager.requiresRestart(null, null))
    }

    @Test
    fun mcpExposedIsNonEmptySafeSubset() {
        val exposed = LocalToolCatalog.mcpExposed
        assertTrue(exposed.isNotEmpty())
        assertTrue(LocalToolCatalog.all.containsAll(exposed))
        assertEquals(exposed.distinct().size, exposed.size)
        val forbidden =
            setOf(
                LocalToolOption.VaultTools,
                LocalToolOption.VaultExportEnv,
                LocalToolOption.Shizuku,
                LocalToolOption.McpControl,
                LocalToolOption.CronJobs,
                LocalToolOption.Workflows,
                LocalToolOption.SubAgents,
                LocalToolOption.JsSkills,
                LocalToolOption.JavascriptEngine,
                LocalToolOption.AskUser,
                LocalToolOption.TelegramBot,
            )
        assertTrue(exposed.none { it in forbidden })
    }

    @Test
    fun groupsRestrictedToGivenOptions() {
        val groups = LocalToolCatalog.groups(LocalToolCatalog.mcpExposed)
        assertTrue(groups.isNotEmpty())
        assertTrue(groups.all { (_, tools) -> tools.isNotEmpty() })
        assertTrue(groups.all { (_, tools) -> tools.all { it in LocalToolCatalog.mcpExposed } })
        assertEquals(
            LocalToolCatalog.mcpExposed.toSet(),
            groups.flatMap { (_, tools) -> tools }.toSet(),
        )
        assertTrue(LocalToolCatalog.groups(emptyList()).isEmpty())
    }
}
