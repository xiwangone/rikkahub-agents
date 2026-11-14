package me.rerere.rikkahub.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [isHandToMainUrlAllowed] — the G-B gate that decides whether a
 * `window.open()` URL may be handed to the main WebView. Pure Kotlin, runs
 * entirely on the JVM; the `handToMain` wiring itself lives inside the
 * WebChromeClient and is covered by inspection.
 */
class HandToMainUrlTest {

    @Test fun `http and https urls are allowed`() {
        assertTrue(isHandToMainUrlAllowed("http://example.com"))
        assertTrue(isHandToMainUrlAllowed("https://example.com/path?q=1"))
        assertTrue(isHandToMainUrlAllowed("HTTPS://example.com"))
    }

    @Test fun `about blank is never handed to main`() {
        assertFalse(isHandToMainUrlAllowed("about:blank"))
        assertFalse(isHandToMainUrlAllowed("ABOUT:BLANK"))
        assertFalse(isHandToMainUrlAllowed("  about:blank  "))
    }

    @Test fun `non-http schemes are rejected`() {
        assertFalse(isHandToMainUrlAllowed("file:///sdcard/x.html"))
        assertFalse(isHandToMainUrlAllowed("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isHandToMainUrlAllowed("javascript:alert(1)"))
        assertFalse(isHandToMainUrlAllowed("data:text/html,<h1>hi</h1>"))
        assertFalse(isHandToMainUrlAllowed("blob:https://example.com/1234"))
    }

    @Test fun `blank and schemeless urls are rejected`() {
        assertFalse(isHandToMainUrlAllowed(""))
        assertFalse(isHandToMainUrlAllowed("   "))
        assertFalse(isHandToMainUrlAllowed("example.com"))
    }
}
