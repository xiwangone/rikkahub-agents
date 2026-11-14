package me.rerere.rikkahub.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [BrowserActivity.normalizeChooserMimeType] — the G-B normaliser
 * for the file-picker MIME type coming from a page's `<input accept>`. Pure
 * Kotlin, runs entirely on the JVM.
 */
class NormalizeChooserMimeTypeTest {

    @Test fun `valid types pass through lowercased`() {
        assertEquals("image/png", BrowserActivity.normalizeChooserMimeType("image/png"))
        assertEquals("image/png", BrowserActivity.normalizeChooserMimeType("IMAGE/PNG"))
        assertEquals(
            "application/vnd.android.package-archive",
            BrowserActivity.normalizeChooserMimeType("application/vnd.android.package-archive"),
        )
    }

    @Test fun `wildcards are preserved`() {
        assertEquals("*/*", BrowserActivity.normalizeChooserMimeType("*/*"))
        assertEquals("image/*", BrowserActivity.normalizeChooserMimeType("image/*"))
    }

    @Test fun `parameters are stripped`() {
        assertEquals(
            "text/html",
            BrowserActivity.normalizeChooserMimeType("text/html; charset=utf-8"),
        )
        assertEquals("image/*", BrowserActivity.normalizeChooserMimeType("image/*;q=0.9"))
        // Empty parameter section is still a valid type.
        assertEquals("text/html", BrowserActivity.normalizeChooserMimeType("text/html;"))
    }

    @Test fun `bare type gets a wildcard subtype`() {
        assertEquals("image/*", BrowserActivity.normalizeChooserMimeType("image"))
        assertEquals("audio/*", BrowserActivity.normalizeChooserMimeType("audio"))
        assertEquals("*/*", BrowserActivity.normalizeChooserMimeType("*"))
    }

    @Test fun `dot extensions are mapped`() {
        assertEquals("application/pdf", BrowserActivity.normalizeChooserMimeType(".pdf"))
        assertEquals("application/pdf", BrowserActivity.normalizeChooserMimeType(".PDF"))
        assertEquals("image/jpeg", BrowserActivity.normalizeChooserMimeType(".jpg"))
        assertEquals("image/jpeg", BrowserActivity.normalizeChooserMimeType(".jpeg"))
        assertEquals("image/png", BrowserActivity.normalizeChooserMimeType(".png"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            BrowserActivity.normalizeChooserMimeType(".docx"),
        )
    }

    @Test fun `unknown extension falls back to wildcard`() {
        assertEquals("*/*", BrowserActivity.normalizeChooserMimeType(".xyz"))
        assertEquals("*/*", BrowserActivity.normalizeChooserMimeType(".tar.gz"))
    }

    @Test fun `garbage falls back to wildcard`() {
        assertEquals("*/*", BrowserActivity.normalizeChooserMimeType(""))
        assertEquals("*/*", BrowserActivity.normalizeChooserMimeType("   "))
        assertEquals("*/*", BrowserActivity.normalizeChooserMimeType("foo bar"))
        assertEquals("*/*", BrowserActivity.normalizeChooserMimeType("image/"))
        assertEquals("*/*", BrowserActivity.normalizeChooserMimeType("/png"))
        assertEquals("*/*", BrowserActivity.normalizeChooserMimeType("a/b/c"))
        assertEquals("*/*", BrowserActivity.normalizeChooserMimeType("text/;"))
    }

    @Test fun `surrounding whitespace is trimmed`() {
        assertEquals("image/png", BrowserActivity.normalizeChooserMimeType("  image/png  "))
        assertEquals("application/pdf", BrowserActivity.normalizeChooserMimeType("  .pdf  "))
    }
}
