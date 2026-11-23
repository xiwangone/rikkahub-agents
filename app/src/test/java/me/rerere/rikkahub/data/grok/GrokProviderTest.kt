package me.rerere.rikkahub.data.grok

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokProviderTest {
    @Test
    fun `video model ids are detected`() {
        assertTrue(isGrokVideoModel("grok-imagine-video"))
        assertTrue(isGrokVideoModel("GROK-VIDEO-1"))
    }

    @Test
    fun `non-video model ids are not video`() {
        assertFalse(isGrokVideoModel("grok-imagine"))
        assertFalse(isGrokVideoModel("grok-image"))
        assertFalse(isGrokVideoModel("grok-4"))
    }
}
