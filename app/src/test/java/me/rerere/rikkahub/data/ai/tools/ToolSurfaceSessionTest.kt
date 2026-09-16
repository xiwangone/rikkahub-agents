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
    fun markLoadedIsIdempotent() {
        ToolSurfaceSession.markLoaded(conversationId, toolName)
        ToolSurfaceSession.markLoaded(conversationId, toolName)

        assertTrue(ToolSurfaceSession.isLoaded(conversationId, toolName))
    }

    /**
     * Idle eviction must NOT drop the unlocked set: shrinking a cold tool back to an empty
     * schema would invalidate the provider's prefix cache for the whole conversation.
     * Only the explicit [ToolSurfaceSession.clear] (conversation reset) may forget it.
     */
    @Test
    fun clearAllInMemoryKeepsMemoryOnlyCleanupFromLeakingAcrossConversations() {
        ToolSurfaceSession.markLoaded(conversationId, toolName)
        assertTrue(ToolSurfaceSession.isLoaded(conversationId, toolName))

        ToolSurfaceSession.clearAllInMemory()

        // Memory view is dropped; the persisted copy (unavailable in a plain JVM test,
        // where init() was never called) is what restores it after a process restart.
        assertFalse(ToolSurfaceSession.isLoaded(conversationId, toolName))
    }

    @Test
    fun clearingConversationRemovesLoadedSchemaState() {
        ToolSurfaceSession.markLoaded(conversationId, toolName)
        assertTrue(ToolSurfaceSession.isLoaded(conversationId, toolName))

        ToolSurfaceSession.clear(conversationId)

        assertFalse(ToolSurfaceSession.isLoaded(conversationId, toolName))
    }
}
