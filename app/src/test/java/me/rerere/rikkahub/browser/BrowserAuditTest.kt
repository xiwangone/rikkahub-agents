package me.rerere.rikkahub.browser

import android.app.Application
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import me.rerere.rikkahub.data.log.FileLogSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Host-JVM (Robolectric) coverage for the browser egress audit — the part that used to need
 * a physical device: the [WebResourceRequest] → record → mask → file path.
 *
 * What is asserted, and why each one matters:
 *  - a GET is not recorded (navigations and sub-resources would otherwise flood the log);
 *  - a non-GET is recorded with its origin tag, so foreground and headless stay separable;
 *  - secrets in the URL query are masked before anything is written (leaking them into a
 *    seven-day log file would be worse than not logging at all);
 *  - repeats inside the dedup window collapse to one row (a polling XHR must not flood);
 *  - the row reaches `filesDir/logs/browser-<date>.log`, i.e. the durable sink, not just
 *    the in-memory one.
 *
 * Isolation: [BrowserAudit] is a singleton with a dedup table and the sink appends to a
 * per-day file shared by the whole test class, so every case uses a unique marker in the
 * URL and asserts on that marker only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowserAuditTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FileLogSink.init(context, "browser-audit-test")
    }

    @Test
    fun getRequest_isNotRecorded() {
        val marker = marker()
        BrowserAudit.maybeRecordRequest(
            FakeWebResourceRequest("https://example.com/get-$marker", "GET"),
            origin = "foreground",
        )
        FileLogSink.flush()
        assertFalse("GET 不应留痕", logText().contains(marker))
    }

    @Test
    fun postRequest_isRecordedWithOriginAndMethod() {
        val marker = marker()
        BrowserAudit.maybeRecordRequest(
            FakeWebResourceRequest("https://example.com/post-$marker", "POST"),
            origin = "headless",
        )
        FileLogSink.flush()
        val text = logText()
        assertTrue("POST 应留痕", text.contains("post-$marker"))
        assertTrue("应带方法", text.contains("method=POST"))
        assertTrue("应带来源标记", text.contains("origin=headless"))
    }

    @Test
    fun urlQuerySecret_isMaskedInFile() {
        val secret = "SECRETVALUE" + UUID.randomUUID().toString().replace("-", "")
        val marker = marker()
        BrowserAudit.maybeRecordRequest(
            FakeWebResourceRequest("https://example.com/p?token=$secret&tag=$marker", "PUT"),
            origin = "foreground",
        )
        FileLogSink.flush()
        val text = logText()
        assertTrue("URL 应留痕", text.contains("tag=$marker"))
        assertTrue("敏感 query 应掩码", text.contains("token=***"))
        assertFalse("明文密钥不得落盘", text.contains(secret))
    }

    @Test
    fun repeatWithinDedupWindow_isRecordedOnce() {
        val marker = marker()
        val url = "https://example.com/dedup-$marker"
        repeat(3) {
            BrowserAudit.maybeRecordRequest(FakeWebResourceRequest(url, "POST"), origin = "foreground")
        }
        FileLogSink.flush()
        assertEquals("2 秒窗口内同键只记一条", 1, countOf(logText(), "dedup-$marker"))
    }

    @Test
    fun actionRow_carriesToolAndMaskedUrl() {
        val marker = marker()
        val secret = "ANOTHERSECRET" + UUID.randomUUID().toString().replace("-", "")
        BrowserAudit.action(
            tool = "browser_submit",
            url = "https://example.com/form?key=$secret&tag=$marker",
            detail = "selector=#f",
        )
        FileLogSink.flush()
        val text = logText()
        assertTrue("动作应留痕", text.contains("tag=$marker"))
        assertTrue("应记工具名", text.contains("action tool=browser_submit"))
        assertTrue("应记 detail", text.contains("selector=#f"))
        assertFalse("明文密钥不得落盘", text.contains(secret))
    }

    /** 唯一标记：绕过去重表与共享日志文件带来的跨用例干扰。 */
    private fun marker(): String = UUID.randomUUID().toString().take(8)

    private fun logText(): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val file = File(context.filesDir, "logs/browser-$day.log")
        return if (file.exists()) file.readText() else ""
    }

    private fun countOf(
        text: String,
        needle: String,
    ): Int = Regex(Regex.escape(needle)).findAll(text).count()
}

/** Minimal [WebResourceRequest] stand-in — the interface is what the WebView client hands us. */
private class FakeWebResourceRequest(
    private val url: String,
    private val httpMethod: String,
    private val mainFrame: Boolean = true,
) : WebResourceRequest {
    override fun getUrl(): Uri = Uri.parse(url)

    override fun isForMainFrame(): Boolean = mainFrame

    override fun isRedirect(): Boolean = false

    override fun hasGesture(): Boolean = false

    override fun getMethod(): String = httpMethod

    override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
}
