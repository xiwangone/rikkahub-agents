package me.rerere.rikkahub.browser

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * End-to-end browser egress audit on a real WebView: page → HTTP POST → audit row on disk.
 *
 * Why this lives in `androidTest` rather than as a host test: [BrowserActivity] is declared
 * `android:exported="false"`, so `adb shell am start` cannot launch it — but an instrumented
 * test shares the app's package and UID, so it can. That makes the whole "what did the
 * browser send outward" path verifiable on a PC-hosted emulator instead of by tapping a
 * physical phone.
 *
 * Self-contained: the probe page is served by a [ServerSocket] created inside the test
 * (no external site, no PC-side helper), so the only moving parts are the app itself and
 * the audit file it writes.
 */
@RunWith(AndroidJUnit4::class)
class BrowserAuditEgressInstrumentedTest {
    private val received = CopyOnWriteArrayList<String>()

    @Test
    fun nonGetRequest_isAuditedWithMaskedUrl() {
        val server = startProbeServer()
        try {
            val url = "http://127.0.0.1:${server.localPort}/probe.html"
            val scenario = ActivityScenario.launch<BrowserActivity>(
                BrowserActivity.intent(InstrumentationRegistry.getInstrumentation().targetContext, url),
            )
            try {
                assertTrue("服务端应收到页面 GET", waitUntil(20_000) { received.any { it.startsWith("GET /probe.html") } })
                assertTrue(
                    "页面脚本发出的 POST 应到达（实际：$received）",
                    waitUntil(20_000) { received.any { it.startsWith("POST ") } },
                )

                val audit = waitForAuditLine("method=POST", 15_000)
                assertTrue("审计应记录非 GET 请求（实际日志：$audit）", audit.contains("method=POST"))
                assertTrue("应记录来源为前台浏览器", audit.contains("origin=foreground"))
                assertTrue("应记录目标 URL（含探针标记）", audit.contains("probe.html"))
                assertTrue("query 里的 token 应被掩码", audit.contains("token=***"))
                assertFalse("明文密钥不得落盘", audit.contains(PROBE_SECRET))
            } finally {
                scenario.close()
            }
        } finally {
            runCatching { server.close() }
        }
    }

    private fun waitUntil(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(300)
        }
        return condition()
    }

    /** 轮询审计文件，直到出现 [needle] 或超时；返回读到的全文（便于失败时看到实情）。 */
    private fun waitForAuditLine(
        needle: String,
        timeoutMs: Long,
    ): String {
        var text = ""
        waitUntil(timeoutMs) {
            text = auditLogText()
            text.contains(needle)
        }
        return text
    }

    private fun auditLogText(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val file = File(context.filesDir, "logs/browser-$day.log")
        return if (file.exists()) file.readText() else ""
    }

    /** 极简探针服务：`/probe.html` 返回会立刻发 POST 的页面，其余路径只回 200。 */
    private fun startProbeServer(): ServerSocket {
        val server = ServerSocket(0)
        Executors.newSingleThreadExecutor().execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use { client ->
                        val requestLine =
                            BufferedReader(InputStreamReader(client.getInputStream())).readLine().orEmpty()
                        received.add(requestLine)
                        val body = if (requestLine.startsWith("GET /probe.html")) PROBE_HTML else "ok"
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val header =
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        client.getOutputStream().apply {
                            write(header.toByteArray(Charsets.UTF_8))
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }
        return server
    }

    private companion object {
        /** 明文标记：出现即说明脱敏失败。 */
        const val PROBE_SECRET = "INSTRUMENTEDPROBESECRET"

        val PROBE_HTML =
            """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>probe</title></head>
            <body>
            <p>probe</p>
            <script>
              fetch('/api/echo?token=$PROBE_SECRET&tag=probe.html', {
                method: 'POST',
                headers: { 'Content-Type': 'text/plain' },
                body: 'instrumented-probe'
              });
            </script>
            </body></html>
            """.trimIndent()
    }
}
