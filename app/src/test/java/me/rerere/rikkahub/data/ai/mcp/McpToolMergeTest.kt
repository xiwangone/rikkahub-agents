package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolMergeTest {
    private fun serverTool(name: String, description: String? = null) = Tool(
        name = name,
        description = description,
        inputSchema = ToolSchema(
            properties = JsonObject(emptyMap<String, JsonElement>()),
            required = emptyList<String>(),
        ),
    )

    @Test
    fun `empty server list keeps stored tools with user switches`() {
        val stored = listOf(
            McpTool(name = "search", enable = false, needsApproval = false),
            McpTool(name = "fetch", enable = true),
        )

        assertSame(stored, mergeTools(stored, emptyList()))
    }

    @Test
    fun `empty stored and empty server yields empty`() {
        assertTrue(mergeTools(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `surviving tool keeps user switches and refreshes description`() {
        val stored = listOf(
            McpTool(name = "search", enable = false, needsApproval = false, description = "old"),
        )
        val server = listOf(serverTool("search", "new"))

        val merged = mergeTools(stored, server)

        assertEquals(1, merged.size)
        assertEquals("search", merged[0].name)
        assertFalse(merged[0].enable)
        assertFalse(merged[0].needsApproval)
        assertEquals("new", merged[0].description)
    }

    @Test
    fun `new server tool is enabled by default`() {
        val stored = listOf(McpTool(name = "search", enable = false))
        val server = listOf(serverTool("search"), serverTool("fetch", "fetch the web"))

        val merged = mergeTools(stored, server)

        assertEquals(listOf("search", "fetch"), merged.map { it.name })
        assertFalse(merged[0].enable)
        assertTrue(merged[1].enable)
        assertTrue(merged[1].needsApproval)
        assertEquals("fetch the web", merged[1].description)
    }

    @Test
    fun `disappeared tool is removed`() {
        val stored = listOf(
            McpTool(name = "search", enable = false),
            McpTool(name = "gone", enable = true),
        )
        val server = listOf(serverTool("search"))

        assertEquals(listOf("search"), mergeTools(stored, server).map { it.name })
    }
}
