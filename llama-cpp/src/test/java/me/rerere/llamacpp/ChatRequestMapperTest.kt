package me.rerere.llamacpp

import me.rerere.ai.ui.UIMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestMapperTest {

    @Test
    fun `a user message becomes an openai shaped message`() {
        val json = JSONObject(
            ChatRequestMapper.toRequestJson(listOf(UIMessage.user("hello")), emptyList())
        )
        val messages = json.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("hello", messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun `no tools means no tools key rather than an empty array`() {
        // An empty tools array makes some templates emit a tool preamble for nothing.
        val json = JSONObject(
            ChatRequestMapper.toRequestJson(listOf(UIMessage.user("hi")), emptyList())
        )
        assertTrue("tools must be absent when none are declared", !json.has("tools"))
    }

    @Test
    fun `tool declaration byte counts match the emitted json`() {
        // The planner budgets on these counts, so they must describe what is really sent.
        val tools = TestTools.two()
        val declarations = ChatRequestMapper.toolDeclarations(tools)
        val emitted = JSONObject(ChatRequestMapper.toRequestJson(listOf(UIMessage.user("x")), tools))
            .getJSONArray("tools")

        assertEquals(tools.size, declarations.size)
        repeat(emitted.length()) { i ->
            val actualBytes = emitted.getJSONObject(i).toString().toByteArray().size
            val claimed = declarations[i].jsonBytes
            assertTrue(
                "declared $claimed bytes but emitted $actualBytes for ${declarations[i].name}",
                kotlin.math.abs(actualBytes - claimed) <= 8,
            )
        }
    }
}
