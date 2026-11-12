package me.rerere.ai.util

import android.util.Log
import me.rerere.common.log.AppLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

// 生产侧的 TAG 是 file-private, 测试文件不可见: 此处自备同值常量。
private const val TAG = "SSE"

/**
 * 批 5 (级别与 tag) 级-P1-1: [SSEEventSource] 的流失败按归因分级。
 *
 * - 读流中途断开是本次操作失败, 记 e;
 * - `cancel()` 主动掐断与 `onFailure` 里 `isCanceled` 为真是预期取消, 记 i。
 *
 * 直接驱动 [SSEEventSource] 的公开方法, 不发真实网络请求。
 */
class SSEEventSourceLoggingTest {

    private val recorded = mutableListOf<Triple<Char, String, String>>()

    private val recordingSink =
        object : AppLogger.Sink {
            override fun d(tag: String, message: String) {
                recorded += Triple('D', tag, message)
            }

            override fun i(tag: String, message: String) {
                recorded += Triple('I', tag, message)
            }

            override fun w(tag: String, message: String) {
                recorded += Triple('W', tag, message)
            }

            override fun w(tag: String, message: String, tr: Throwable?) {
                recorded += Triple('W', tag, message)
            }

            override fun e(tag: String, message: String) {
                recorded += Triple('E', tag, message)
            }

            override fun e(tag: String, message: String, tr: Throwable?) {
                recorded += Triple('E', tag, message)
            }
        }

    // AppLogger 没有卸载接口; 测试后装一个直通 logcat 的 sink, 让同 JVM 内
    // 后续测试的 AppLogger 调用恢复默认行为 (只记录、不干预)。
    private val logcatSink =
        object : AppLogger.Sink {
            override fun d(tag: String, message: String) {
                Log.d(tag, message)
            }

            override fun i(tag: String, message: String) {
                Log.i(tag, message)
            }

            override fun w(tag: String, message: String) {
                Log.w(tag, message)
            }

            override fun w(tag: String, message: String, tr: Throwable?) {
                Log.w(tag, message, tr)
            }

            override fun e(tag: String, message: String) {
                Log.e(tag, message)
            }

            override fun e(tag: String, message: String, tr: Throwable?) {
                Log.e(tag, message, tr)
            }
        }

    @Before
    fun installRecordingSink() {
        recorded.clear()
        AppLogger.install(recordingSink)
    }

    @After
    fun restoreLogcatSink() {
        AppLogger.install(logcatSink)
    }

    private val request = Request.Builder().url("https://example.com/sse").build()
    private val failures = mutableListOf<Throwable?>()

    private val listener =
        object : EventSourceListener() {
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                failures += t
            }
        }

    private fun sseLines() = recorded.filter { it.second == TAG }

    @Test
    fun `onFailure without cancel logs one e line`() {
        val source = SSEEventSource(request, listener)
        source.onFailure(OkHttpClient().newCall(request), IOException("boom-test"))

        val lines = sseLines()
        assertEquals("one non-canceled failure must log exactly one line, got $recorded", 1, lines.size)
        assertEquals('E', lines[0].first)
        assertTrue(lines[0].third.startsWith("call failed:"))
        assertTrue(lines[0].third.contains("isCanceled=false"))
        assertEquals(1, failures.size)
    }

    @Test
    fun `onFailure after cancel logs one i line`() {
        val call = OkHttpClient().newCall(request)
        call.cancel()

        val source = SSEEventSource(request, listener)
        source.onFailure(call, IOException("Canceled"))

        val lines = sseLines()
        assertEquals("one canceled failure must log exactly one line, got $recorded", 1, lines.size)
        assertEquals('I', lines[0].first)
        assertTrue(lines[0].third.startsWith("call failed:"))
        assertTrue(lines[0].third.contains("isCanceled=true"))
        assertEquals(1, failures.size)
    }

    @Test
    fun `cancel logs one i line with the caller`() {
        val source = SSEEventSource(request, listener)
        source.cancel()

        val lines = sseLines()
        assertEquals("one cancel must log exactly one line, got $recorded", 1, lines.size)
        assertEquals('I', lines[0].first)
        assertTrue(lines[0].third.startsWith("cancel(): caller="))
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `stream read failure logs one e line`() {
        val body =
            object : ResponseBody() {
                override fun contentType() = "text/event-stream".toMediaType()

                override fun contentLength() = 0L

                override fun source() =
                    object : ForwardingSource(Buffer()) {
                        override fun read(sink: Buffer, byteCount: Long): Long =
                            throw IOException("boom-test-read")
                    }.buffer()
            }
        val response =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()

        val source = SSEEventSource(request, listener)
        source.processResponse(response)

        val lines = sseLines()
        assertEquals("one stream read failure must log exactly one line, got $recorded", 1, lines.size)
        assertEquals('E', lines[0].first)
        assertTrue(lines[0].third.startsWith("stream interrupted:"))
        assertEquals(1, failures.size)
    }
}
