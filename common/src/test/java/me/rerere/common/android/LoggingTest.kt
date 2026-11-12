package me.rerere.common.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression for F6: modules below :app (e.g. :ai) have no BuildConfig of their own, so
 * provider code that wants to gate full request/response body logging behind "is this a
 * debug build" needs a runtime flag set once from :app's BuildConfig.DEBUG instead. Pins
 * that the setter actually flips what the getter reports.
 *
 * [Logging] is a singleton object, so its state persists across test methods within the
 * same JVM — this is a single test that sets both values explicitly rather than two
 * tests that would depend on run order to observe a "default" state.
 *
 * The bucket tests below rely on the same singleton rule: every test starts from a
 * cleared state ([setUp] resets both buffers and both switches) and asserts on
 * unique markers only, so no test depends on run order.
 */
class LoggingTest {

    @Before
    fun setUp() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(false)
        Logging.setDebugLoggingEnabled(false)
    }

    @After
    fun tearDown() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(false)
        Logging.setDebugLoggingEnabled(false)
    }

    @Test
    fun `setDebugLoggingEnabled toggles what isDebugLoggingEnabled reports`() {
        Logging.setDebugLoggingEnabled(false)
        assertFalse(Logging.isDebugLoggingEnabled())

        Logging.setDebugLoggingEnabled(true)
        assertTrue(Logging.isDebugLoggingEnabled())

        Logging.setDebugLoggingEnabled(false)
        assertFalse(Logging.isDebugLoggingEnabled())
    }

    @Test
    fun `text and request logs have separate 500-entry buckets`() {
        Logging.setRequestLoggingEnabled(true)
        repeat(600) { i -> Logging.log("text-bucket", "text $i") }
        repeat(600) { i ->
            Logging.logRequest(
                LogEntry.RequestLog(tag = "HTTP", url = "https://example.com/$i", method = "GET")
            )
        }

        // Each bucket caps at its own 500: a flood of text logs must not evict
        // request logs, and vice versa (previously both shared one 100-entry list).
        assertEquals(500, Logging.getTextLogs().size)
        assertEquals(500, Logging.getRequestLogs().size)
        assertEquals(1000, Logging.getRecentLogs().size)
        // Newest first within each bucket.
        assertEquals("text 599", Logging.getTextLogs().first().message)
        assertEquals("https://example.com/599", Logging.getRequestLogs().first().url)
    }

    @Test
    fun `getRecentLogs merges both buckets newest first`() {
        Logging.setRequestLoggingEnabled(true)
        Logging.log("merge", "old text")
        // Explicit timestamps: the merge is by timestamp, not by insertion order.
        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = "https://example.com/new",
                method = "GET",
                timestamp = System.currentTimeMillis() + 10_000L
            )
        )

        val merged = Logging.getRecentLogs()
        assertEquals(2, merged.size)
        assertTrue(merged[0] is LogEntry.RequestLog)
        assertTrue(merged[1] is LogEntry.TextLog)
    }

    @Test
    fun `logRequest is dropped when disabled unless forced`() {
        val entry = LogEntry.RequestLog(tag = "HTTP", url = "https://example.com", method = "GET")

        Logging.logRequest(entry)
        assertTrue(Logging.getRequestLogs().isEmpty())

        Logging.logRequest(entry, force = true)
        assertEquals(1, Logging.getRequestLogs().size)
    }

    @Test
    fun `logRequest records when enabled`() {
        Logging.setRequestLoggingEnabled(true)
        Logging.logRequest(
            LogEntry.RequestLog(tag = "HTTP", url = "https://example.com", method = "GET")
        )
        assertEquals(1, Logging.getRequestLogs().size)
    }
}
