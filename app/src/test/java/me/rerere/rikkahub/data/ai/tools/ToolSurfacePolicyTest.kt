package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.isSafeToolOutputRead
import me.rerere.rikkahub.ui.pages.assistant.detail.findMissingEnabledReadTools
import me.rerere.rikkahub.ui.pages.assistant.detail.suggestToolNamesForList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies pure allowlist/blocklist [ToolSurfacePolicy], batch `get_tool_schema`,
 * safe `/tool_outputs/` read exemption, and usage-based list suggestion helpers.
 */
class ToolSurfacePolicyTest {

    @Test
    fun `未配置名单时所有工具默认 HOT`() {
        assertEquals(SurfaceTier.HOT, ToolSurfacePolicy.tierOf("workspace_shell"))
        assertEquals(SurfaceTier.HOT, ToolSurfacePolicy.tierOf("telegram_send_message"))
        assertEquals(SurfaceTier.HOT, ToolSurfacePolicy.tierOf("appops_get"))
        assertEquals(SurfaceTier.HOT, ToolSurfacePolicy.tierOf("some_new_tool"))
        assertEquals(TierSource.DEFAULT_HOT, ToolSurfacePolicy.decide("workspace_shell").source)
    }

    @Test
    fun `extraCold 把指定工具下调为 COLD`() {
        assertEquals(SurfaceTier.COLD, ToolSurfacePolicy.tierOf("workspace_shell", setOf("workspace_shell")))
        assertEquals(
            TierSource.ASSISTANT_EXTRA_COLD,
            ToolSurfacePolicy.decide("workspace_shell", setOf("workspace_shell")).source,
        )
    }

    @Test
    fun `extraCold 不含的工具保持 HOT`() =
        assertEquals(SurfaceTier.HOT, ToolSurfacePolicy.tierOf("workspace_shell", setOf("ssh_exec_saved")))

    @Test
    fun `白名单模式：名单内保持 HOT`() =
        assertEquals(
            SurfaceTier.HOT,
            ToolSurfacePolicy.tierOfWithScope("workspace_shell", listOf("workspace_shell")),
        )

    @Test
    fun `白名单模式：名单外一律冷档（不硬删）`() {
        assertEquals(
            SurfaceTier.COLD,
            ToolSurfacePolicy.tierOfWithScope("web_fetch", listOf("workspace_shell")),
        )
        assertEquals(
            TierSource.OUTSIDE_ASSISTANT_SCOPE,
            ToolSurfacePolicy.tierWithScope("web_fetch", listOf("workspace_shell")).source,
        )
    }

    @Test
    fun `白名单模式：保命工具不受名单影响`() {
        for (name in ToolSurfacePolicy.ALWAYS_KEEP_TOOL_NAMES) {
            assertEquals(
                SurfaceTier.HOT,
                ToolSurfacePolicy.tierOfWithScope(name, listOf("workspace_shell")),
            )
        }
    }

    @Test
    fun `白名单模式：名单为空时退回默认 HOT`() =
        assertEquals(
            SurfaceTier.HOT,
            ToolSurfacePolicy.tierOfWithScope("workspace_shell", emptyList()),
        )

    @Test
    fun `effectiveDiscoveryTier 把白名单外的工具判为 COLD 以允许 get_tool_schema 解锁`() {
        assertEquals(
            SurfaceTier.COLD,
            effectiveDiscoveryTier(
                toolName = "workspace_shell",
                trimEnabled = true,
                scope = listOf("web_fetch"),
            ),
        )
        assertEquals(
            SurfaceTier.HOT,
            effectiveDiscoveryTier(
                toolName = "workspace_shell",
                trimEnabled = true,
                scope = listOf("workspace_shell"),
            ),
        )
    }

    @Test
    fun `保命工具名单仅包含零副作用自救层`() =
        assertEquals(
            setOf("list_tools", "get_tool_schema", "ask_user"),
            ToolSurfacePolicy.ALWAYS_KEEP_TOOL_NAMES,
        )

    @Test
    fun `get_tool_schema 支持 names 批量解锁并返回未命中列表`() = runBlocking {
        val convId = "batch-test-conv"
        ToolSurfaceSession.clear(convId)
        val dummySchema = InputSchema.Obj(properties = buildJsonObject {}, required = emptyList())
        val tools =
            listOf(
                Tool(name = "tool_a", description = "Tool A", parameters = { dummySchema }, execute = { emptyList() }),
                Tool(name = "tool_b", description = "Tool B", parameters = { dummySchema }, execute = { emptyList() }),
            )
        val discovery =
            buildToolDiscoveryTools(
                allTools = tools,
                conversationId = convId,
                extraCold = setOf("tool_a", "tool_b"),
            )
        val getSchema = discovery.first { it.name == "get_tool_schema" }
        val input =
            buildJsonObject {
                put(
                    "names",
                    buildJsonArray {
                        add(JsonPrimitive("tool_a"))
                        add(JsonPrimitive("tool_b"))
                        add(JsonPrimitive("missing_tool"))
                    },
                )
            }
        val output = getSchema.execute(input).filterIsInstance<UIMessagePart.Text>().first().text
        val parsed = Json.parseToJsonElement(output).jsonObject
        assertEquals(2, parsed["count"]?.jsonPrimitive?.content?.toInt())
        assertEquals(2, parsed["tools"]?.jsonArray?.size)
        assertEquals(listOf("missing_tool"), parsed["not_found"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertTrue(ToolSurfaceSession.isLoaded(convId, "tool_a"))
        assertTrue(ToolSurfaceSession.isLoaded(convId, "tool_b"))
        ToolSurfaceSession.clear(convId)
    }

    @Test
    fun `isSafeToolOutputRead 仅放行私有 tool_outputs 只读且拒绝路径穿越`() {
        assertTrue(isSafeToolOutputRead("read_file", """{"path":"/tool_outputs/abc.txt"}"""))
        assertTrue(isSafeToolOutputRead("file_info", """{"path":"/tool_outputs/abc.txt"}"""))
        assertFalse(isSafeToolOutputRead("read_file", """{"path":"/tool_outputs/../secret.txt"}"""))
        assertFalse(isSafeToolOutputRead("read_file", """{"path":"/sdcard/Download/a.txt"}"""))
        assertFalse(isSafeToolOutputRead("delete_file", """{"path":"/tool_outputs/abc.txt"}"""))
    }

    @Test
    fun `suggestToolNamesForList 按用量生成冷白名单并保护基础读工具`() {
        val injected = setOf("read_file", "workspace_read_file", "ssh_exec_saved", "telegram_send_message", "list_tools")
        val counts = mapOf("ssh_exec_saved" to 12L)
        val cold = suggestToolNamesForList(injected, counts, isAllowList = false)
        assertEquals(listOf("telegram_send_message"), cold)

        val allow = suggestToolNamesForList(injected, counts, isAllowList = true)
        assertTrue("ssh_exec_saved" in allow)
        assertTrue("read_file" in allow)
        assertTrue("workspace_read_file" in allow)
        assertFalse("telegram_send_message" in allow)

        val missing = findMissingEnabledReadTools(listOf("ssh_exec_saved"), injected)
        assertEquals(listOf("read_file", "workspace_read_file"), missing)
    }
}
