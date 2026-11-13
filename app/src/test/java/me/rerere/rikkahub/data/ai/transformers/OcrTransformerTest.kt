package me.rerere.rikkahub.data.ai.transformers

import java.io.File
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [selectUncachedImageUrls], the pure selection that decides whether OCR
 * has any uncached work to do (and therefore whether to flash the "Recognizing
 * image..." status at all).
 */
class OcrTransformerTest {

    private fun userMessage(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.USER,
        parts = parts.toList(),
    )

    @Test
    fun `no images returns empty`() {
        val messages = listOf(userMessage(UIMessagePart.Text("hello")))
        val result = selectUncachedImageUrls(messages) { false }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `cached-only images return empty`() {
        val messages = listOf(
            userMessage(UIMessagePart.Image("file:///a.png")),
            userMessage(UIMessagePart.Image("file:///b.png")),
        )
        val result = selectUncachedImageUrls(messages) { true }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mixed cache state returns only misses`() {
        val messages = listOf(
            userMessage(UIMessagePart.Image("file:///cached.png")),
            userMessage(UIMessagePart.Image("file:///missing.png")),
        )
        val cached = setOf("file:///cached.png")
        val result = selectUncachedImageUrls(messages) { url -> url in cached }
        assertEquals(listOf("file:///missing.png"), result)
    }

    @Test
    fun `non-file urls are ignored`() {
        val messages = listOf(
            userMessage(UIMessagePart.Image("https://example.com/a.png")),
            userMessage(UIMessagePart.Image("file:///local.png")),
        )
        val result = selectUncachedImageUrls(messages) { false }
        assertEquals(listOf("file:///local.png"), result)
    }

    @Test
    fun `empty message list returns empty`() {
        val result = selectUncachedImageUrls(emptyList()) { false }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `cache key changes with mtime size and model`() {
        val base = buildOcrCacheKey("file:///a.png", "model-1", 100L, 200L)
        assertEquals(base, buildOcrCacheKey("file:///a.png", "model-1", 100L, 200L))
        assertNotEquals(base, buildOcrCacheKey("file:///a.png", "model-1", 101L, 200L))
        assertNotEquals(base, buildOcrCacheKey("file:///a.png", "model-1", 100L, 201L))
        assertNotEquals(base, buildOcrCacheKey("file:///a.png", "model-2", 100L, 200L))
        assertNotEquals(base, buildOcrCacheKey("file:///b.png", "model-1", 100L, 200L))
    }

    @Test
    fun `blank ocr output is not cacheable`() {
        assertFalse(shouldCacheOcrResult(""))
        assertFalse(shouldCacheOcrResult("   "))
        assertTrue(shouldCacheOcrResult("a cat on a mat"))
    }

    @Test
    fun `wrapped result carries content and tag`() {
        val wrapped = wrapOcrResult("a cat")
        assertTrue(wrapped.contains("a cat"))
        assertTrue(wrapped.contains("<image_file_ocr>"))
    }

    @Test
    fun `resolve key stats a real file and falls back for a missing one`() {
        val tmp = File.createTempFile("ocr-key-test", ".png").apply {
            writeText("x")
            deleteOnExit()
        }
        val key = resolveOcrCacheKey("file://${tmp.absolutePath}", "model-1")
        assertTrue(key.contains("${tmp.lastModified()}|${tmp.length()}|model-1"))
        val missing = resolveOcrCacheKey("file:///definitely/not/here.png", "model-1")
        assertTrue(missing.contains("|-1|-1|model-1"))
    }
}
