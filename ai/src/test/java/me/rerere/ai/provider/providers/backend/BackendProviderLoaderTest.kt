package me.rerere.ai.provider.providers.backend

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * M-3：BackendProvider 把挂起的 /history、/status 调用桥接成同步 loader 时
 * 用了无调度的 runBlocking，且 BackendApi 的共享 client 没有 callTimeout，
 * 服务端挂起会把补拉 / 状态探测的守护线程无限拖住。
 *
 * 修复分两层：BackendApi 的 getHistory/getStatus 走 shortRequestClient
 * （30s callTimeout，与 getModels 同口径）；BackendProvider 的桥接改走
 * Dispatchers.IO 并加同值 withTimeout。两层缺一不可——withTimeout 中断不了
 * 阻塞中的 OkHttp execute()，套接字层面的上限只能由 callTimeout 保证。
 */
class BackendProviderLoaderTest {

    private fun provider() = BackendProvider(OkHttpClient())

    private fun clientReturningJson(body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

    @Test
    fun `blockingLoader returns the loaded value`() {
        val loader = provider().blockingLoader { listOf("a", "b") }
        assertEquals(listOf("a", "b"), loader())
    }

    @Test
    fun `blockingLoader times out a hanging suspend load`() {
        val loader = provider().blockingLoader(timeoutMs = 200) {
            delay(Long.MAX_VALUE)
            "never"
        }
        val startMs = System.currentTimeMillis()
        try {
            loader()
            fail("Expected TimeoutCancellationException for a hanging load")
        } catch (e: TimeoutCancellationException) {
            // 预期：200ms 上限内抛错，而不是无限等下去。
        }
        val elapsedMs = System.currentTimeMillis() - startMs
        assertTrue("blockingLoader must time out quickly, took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun `getHistory parses a normal response`() =
        runBlocking {
            val api = BackendApi(
                baseUrl = "http://127.0.0.1:9",
                client = clientReturningJson("""[{"role":"assistant","content":"hello"}]"""),
            )
            val history = api.getHistory()
            assertEquals(1, history.size)
            assertEquals("assistant", history[0].role)
            assertEquals("hello", history[0].content)
        }

    @Test
    fun `getStatus parses a normal response`() =
        runBlocking {
            val api = BackendApi(
                baseUrl = "http://127.0.0.1:9",
                client = clientReturningJson("""{"label":"ok","pendingPrompt":true}"""),
            )
            val status = api.getStatus()
            assertEquals("ok", status?.label)
            assertEquals(true, status?.pendingPrompt)
        }

    @Test
    fun `getHistory gives up within the short-request budget when the server stalls`() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        var accepted: Socket? = null
        val acceptThread = thread(isDaemon = true) {
            runCatching {
                accepted = serverSocket.accept()
                // 接受连接后永不响应，模拟服务端挂起。
                Thread.sleep(120_000)
            }
        }
        try {
            // 读超时设为 0（无上限）：没有 callTimeout 时这次调用会无限挂起，
            // 测试靠 60s 的 join 上限兜底，回归时失败而不是把 CI 卡死。
            val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            val api = BackendApi(baseUrl = "http://127.0.0.1:$port", client = client)
            var result: List<HistoryMessage>? = null
            var failure: Throwable? = null
            val startMs = System.currentTimeMillis()
            val worker = thread(isDaemon = true) {
                try {
                    result = runBlocking { api.getHistory() }
                } catch (e: Throwable) {
                    failure = e
                }
            }
            worker.join(60_000)
            val elapsedMs = System.currentTimeMillis() - startMs
            assertFalse(
                "getHistory must return within 60s when the server stalls (30s callTimeout), elapsed=${elapsedMs}ms",
                worker.isAlive,
            )
            assertNull("getHistory must not throw on a stalled server, got: $failure", failure)
            assertTrue("stalled /history must yield an empty list", result?.isEmpty() == true)
            assertTrue(
                "getHistory took ${elapsedMs}ms, expected the ~30s short-request budget",
                elapsedMs < 60_000,
            )
        } finally {
            runCatching { accepted?.close() }
            acceptThread.interrupt()
            serverSocket.close()
        }
    }
}
