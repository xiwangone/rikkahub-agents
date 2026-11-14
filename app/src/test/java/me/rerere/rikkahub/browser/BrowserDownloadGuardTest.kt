package me.rerere.rikkahub.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the G-B download guards: [isHttpOrHttpsUrl] (scheme gate) and
 * [sanitizeDownloadFileName] (filename cleanup). Both are pure Kotlin so the
 * tests run entirely on the JVM — the DownloadListener wiring itself (which
 * needs a real DownloadManager) is covered by inspection, not here.
 */
class BrowserDownloadGuardTest {

    @Test fun `http and https pass the scheme gate`() {
        assertTrue(isHttpOrHttpsUrl("http://example.com/a.apk"))
        assertTrue(isHttpOrHttpsUrl("https://example.com/a.apk?x=1"))
    }

    @Test fun `scheme check is case-insensitive`() {
        assertTrue(isHttpOrHttpsUrl("HTTPS://example.com/a.apk"))
        assertTrue(isHttpOrHttpsUrl("Http://example.com/a.apk"))
    }

    @Test fun `non-http schemes are rejected`() {
        assertFalse(isHttpOrHttpsUrl("file:///data/data/me.rerere.rikkahub/files/secret.txt"))
        assertFalse(isHttpOrHttpsUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isHttpOrHttpsUrl("javascript:alert(1)"))
        assertFalse(isHttpOrHttpsUrl("data:text/html,<h1>hi</h1>"))
        assertFalse(isHttpOrHttpsUrl("blob:https://example.com/1234"))
        assertFalse(isHttpOrHttpsUrl("ftp://example.com/a.apk"))
        assertFalse(isHttpOrHttpsUrl("content://com.example.provider/file"))
    }

    @Test fun `missing or malformed scheme is rejected`() {
        assertFalse(isHttpOrHttpsUrl(""))
        assertFalse(isHttpOrHttpsUrl("   "))
        assertFalse(isHttpOrHttpsUrl("example.com/a.apk"))
        assertFalse(isHttpOrHttpsUrl("/a.apk"))
        // Colon after a slash is a path segment, not a scheme.
        assertFalse(isHttpOrHttpsUrl("example.com/path:80/a.apk"))
    }

    @Test fun `surrounding whitespace is tolerated`() {
        assertTrue(isHttpOrHttpsUrl("  https://example.com/a.apk  "))
    }

    @Test fun `plain file name passes through unchanged`() {
        assertEquals("report.pdf", sanitizeDownloadFileName("report.pdf"))
        assertEquals("my file (1).apk", sanitizeDownloadFileName("my file (1).apk"))
    }

    @Test fun `directory components are stripped`() {
        assertEquals("c.apk", sanitizeDownloadFileName("a/b/c.apk"))
        assertEquals("evil.apk", sanitizeDownloadFileName("..\\..\\evil.apk"))
        assertEquals("passwd", sanitizeDownloadFileName("/etc/passwd"))
        assertEquals("x.apk", sanitizeDownloadFileName("../../x.apk"))
    }

    @Test fun `dot segments and empty names fall back to download`() {
        assertEquals("download", sanitizeDownloadFileName(".."))
        assertEquals("download", sanitizeDownloadFileName("."))
        assertEquals("download", sanitizeDownloadFileName(""))
        assertEquals("download", sanitizeDownloadFileName("   "))
        assertEquals("download", sanitizeDownloadFileName("a/b/.."))
    }

    @Test fun `illegal characters are replaced`() {
        assertEquals("a_b_c_d_e_f_g_h", sanitizeDownloadFileName("a:b*c?d\"e<f>g|h"))
        // Control characters are replaced too.
        assertEquals("a_b", sanitizeDownloadFileName("a\u0001b"))
    }

    @Test fun `leading and trailing dots and spaces are trimmed`() {
        assertEquals("hidden", sanitizeDownloadFileName(".hidden"))
        assertEquals("file", sanitizeDownloadFileName("file..."))
        assertEquals("file.apk", sanitizeDownloadFileName("  file.apk  "))
    }

    @Test fun `overlong ascii name is truncated keeping the extension`() {
        val long = "a".repeat(300) + ".pdf"
        val result = sanitizeDownloadFileName(long)
        assertTrue("expected <= 200 utf-8 bytes, got ${result.length} chars",
            result.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue("extension must survive truncation", result.endsWith(".pdf"))
    }

    @Test fun `overlong name without extension is truncated`() {
        val long = "b".repeat(300)
        val result = sanitizeDownloadFileName(long)
        assertEquals(200, result.toByteArray(Charsets.UTF_8).size)
    }

    @Test fun `overlong multibyte name is truncated by bytes not chars`() {
        val long = "中".repeat(150) + ".pdf"
        val result = sanitizeDownloadFileName(long)
        assertTrue("expected <= 200 utf-8 bytes, got ${result.toByteArray(Charsets.UTF_8).size}",
            result.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue("extension must survive truncation", result.endsWith(".pdf"))
    }
}
