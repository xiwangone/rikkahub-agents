package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UIMessageSerializationTest {

    @Test
    fun `synthetic marker is not serialized`() {
        val message = UIMessage.user("internal").copy(isSynthetic = true)

        val encoded = Json.encodeToString(message)
        val decoded = Json.decodeFromString<UIMessage>(encoded)

        assertTrue(message.isSynthetic)
        assertFalse(encoded.contains("isSynthetic"))
        assertFalse(decoded.isSynthetic)
    }

    @Test
    fun `context checkpoint marker survives serialization`() {
        val message = UIMessage.user("summary").copy(isContextCheckpoint = true)

        val decoded = Json.decodeFromString<UIMessage>(Json.encodeToString(message))

        assertTrue(decoded.isContextCheckpoint)
    }

    @Test
    fun `messages stored before checkpoints existed decode as ordinary messages`() {
        val legacy = """{"id":"00000000-0000-0000-0000-000000000001","role":"user","parts":[]}"""

        assertFalse(Json.decodeFromString<UIMessage>(legacy).isContextCheckpoint)
    }

    @Test
    fun `legacy messages default step count and use cumulative usage as their only known request`() {
        val legacy = """{"role":"assistant","parts":[],"usage":{"promptTokens":40,"completionTokens":5}}"""

        val decoded = Json.decodeFromString<UIMessage>(legacy)

        assertEquals(0, decoded.stepCount)
        assertNull(decoded.lastUsage)
        assertEquals(decoded.usage, decoded.lastRequestUsage())
    }

    @Test
    fun `cumulative and latest request usage survive serialization`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()).copy(
            usage = TokenUsage(promptTokens = 300, completionTokens = 30, totalTokens = 330),
            lastUsage = TokenUsage(promptTokens = 120, completionTokens = 10, totalTokens = 130),
            stepCount = 2,
        )

        val decoded = Json.decodeFromString<UIMessage>(Json.encodeToString(message))

        assertEquals(message.usage, decoded.usage)
        assertEquals(message.lastUsage, decoded.lastUsage)
        assertEquals(2, decoded.stepCount)
        assertEquals(130, decoded.contextTokens())
    }
}
