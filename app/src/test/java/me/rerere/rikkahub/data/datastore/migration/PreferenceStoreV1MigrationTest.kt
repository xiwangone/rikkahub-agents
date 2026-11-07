package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * V1 migration: MCP server `type` values used to be fully qualified class names.
 * They must be rewritten to the short keys the current deserializer expects, and
 * every other field must survive untouched.
 */
class PreferenceStoreV1MigrationTest {
    @Test
    fun `legacy fully qualified transport names are rewritten`() {
        val raw = """
            [
              {"id":"a","type":"me.rerere.rikkahub.data.mcp.McpServerConfig.SseTransportServer"},
              {"id":"b","type":"me.rerere.rikkahub.data.mcp.McpServerConfig.StreamableHTTPServer"}
            ]
        """.trimIndent()

        val out = JsonInstant.parseToJsonElement(migrateMcpServersJson(raw)).jsonArray

        assertEquals(2, out.size)
        assertEquals("sse", out[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("streamable_http", out[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `current short names and unknown types are left as they are`() {
        val raw = """[{"id":"a","type":"sse"},{"id":"b","type":"streamable_http"},{"id":"c","type":"unknown_future"}]"""

        val out = JsonInstant.parseToJsonElement(migrateMcpServersJson(raw)).jsonArray

        assertEquals("sse", out[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("streamable_http", out[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("unknown_future", out[2].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non type fields survive the rewrite`() {
        val raw = """
            [{"id":"a","name":"local tools","enabled":true,"url":"https://example.invalid/sse",
              "type":"me.rerere.rikkahub.data.mcp.McpServerConfig.SseTransportServer",
              "headers":[{"name":"X-Test","value":"1"}]}]
        """.trimIndent()

        val server = JsonInstant.parseToJsonElement(migrateMcpServersJson(raw)).jsonArray.single().jsonObject

        assertEquals("a", server["id"]!!.jsonPrimitive.content)
        assertEquals("local tools", server["name"]!!.jsonPrimitive.content)
        assertEquals(true, server["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("https://example.invalid/sse", server["url"]!!.jsonPrimitive.content)
        assertEquals(1, server["headers"]!!.jsonArray.size)
        assertEquals("X-Test", server["headers"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty list stays an empty list`() {
        assertEquals("[]", migrateMcpServersJson("[]"))
    }

    /**
     * Current behaviour (recorded, not endorsed): a malformed stored payload throws instead of
     * being replaced by an empty list — dropping every configured server would be worse.
     */
    @Test
    fun `malformed payload fails loudly instead of dropping all servers`() {
        try {
            migrateMcpServersJson("not-json")
            fail("Malformed MCP payload must not be silently treated as an empty list")
        } catch (_: Exception) {
            // Expected.
        }
    }

    @Test
    fun `json object instead of array fails loudly`() {
        try {
            migrateMcpServersJson("""{"id":"a"}""")
            fail("A non-array payload cannot be migrated and must not be accepted")
        } catch (_: Exception) {
            // Expected.
        }
    }

    @Test
    fun `migration writes version 1`() {
        val migrated = runBlockingMigration { PreferenceStoreV1Migration().migrate(it) }
        assertEquals(1, migrated[me.rerere.rikkahub.data.datastore.SettingsStore.VERSION])
        assertTrue(migrated[me.rerere.rikkahub.data.datastore.SettingsStore.MCP_SERVERS] != null)
    }
}
