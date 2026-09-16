package me.rerere.rikkahub.data.ai.tools

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSurfaceSessionTest {
    private val conversationId = "tool-surface-test-conversation"
    private val toolName = "nfc_read_tag"

    @After
    fun cleanup() {
        ToolSurfaceSession.clear(conversationId)
    }

    @Test
    fun coldToolIsNotLoadedUntilSchemaDiscovery() {
        assertFalse(ToolSurfaceSession.isLoaded(conversationId, toolName))

        ToolSurfaceSession.markLoaded(conversationId, toolName)

        assertTrue(ToolSurfaceSession.isLoaded(conversationId, toolName))
    }

    @Test
    fun clearingConversationRemovesLoadedSchemaState() {
        ToolSurfaceSession.markLoaded(conversationId, toolName)
        assertTrue(ToolSurfaceSession.isLoaded(conversationId, toolName))

        ToolSurfaceSession.clear(conversationId)

        assertFalse(ToolSurfaceSession.isLoaded(conversationId, toolName))
    }
}
