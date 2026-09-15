package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 工具名必须满足模型提供方的 `^[a-zA-Z0-9_-]{1,64}$`：服务器名与工具名由用户自由填写，
 * 可能含空格、点、斜杠或中文；直接拼进工具名会被模型 API 拒绝（表现为「MCP 装了但模型调不动」）。
 */
class McpToolNameSanitizeTest {

    private val id = Uuid.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    @Test
    fun `spaces become underscores`() {
        assertEquals("mcp__aaaaaaaa_my_server__do_thing", buildMcpToolName(id, "my server", "do thing"))
    }

    @Test
    fun `non ascii and dots are normalised to the allowed set`() {
        val name = buildMcpToolName(id, "我的服务", "查询.天气")
        assertTrue(name, name.matches(Regex("^[a-zA-Z0-9_-]{1,64}$")))
    }

    @Test
    fun `slug is still the first 8 hex chars of the server id`() {
        assertTrue(buildMcpToolName(id, "s", "t").startsWith("mcp__aaaaaaaa_"))
    }

    @Test
    fun `long names stay within the provider limit`() {
        val name = buildMcpToolName(id, "s".repeat(80), "t".repeat(80))
        assertTrue("len=${name.length} name=$name", name.length <= 64)
        assertTrue(name.matches(Regex("^[a-zA-Z0-9_-]{1,64}$")))
    }

    @Test
    fun `clean short names keep their previous shape`() {
        assertEquals("mcp__aaaaaaaa_my-server__do_thing", buildMcpToolName(id, "my-server", "do_thing"))
    }

    @Test
    fun `blank parts degrade to a placeholder`() {
        val name = buildMcpToolName(id, "   ", "  ")
        assertTrue(name, name.matches(Regex("^[a-zA-Z0-9_-]{1,64}$")))
    }
}
