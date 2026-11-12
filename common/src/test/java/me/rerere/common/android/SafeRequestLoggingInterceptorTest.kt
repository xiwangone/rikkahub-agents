package me.rerere.common.android

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 批 3 覆-P0-2：:common 层最小请求日志拦截器。
 *
 * 用 OkHttp 拦截器短路返回 canned 响应，不依赖真实服务端；断言拦截器只记
 * method + scheme://host + 状态码 + 耗时 + error，绝不记 path/query/headers/body
 * （Telegram token 在 URL path 里，Vault 凭据头名用户可配，DoH body 含域名明文，
 * :common 层没有 LogRedactor，索性一律不记）。
 */
class SafeRequestLoggingInterceptorTest {

    private fun clientReturning(code: Int, body: String = ""): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(SafeRequestLoggingInterceptor())
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("test")
                        .body(body.toResponseBody(null))
                        .build()
                },
            )
            .build()

    private fun clientThrowing(error: IOException): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(SafeRequestLoggingInterceptor())
            .addInterceptor(Interceptor { throw error })
            .build()

    @Before
    fun setUp() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(true)
    }

    @After
    fun tearDown() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(false)
    }

    @Test
    fun `logs host only, never path query headers or body`() {
        val client = clientReturning(200, "ok")
        val request = Request.Builder()
            .url("http://127.0.0.1:9/botSECRET_TOKEN/getMe?key=SECRET_KEY")
            .addHeader("Authorization", "Bearer SECRET_HEADER")
            .post("secret body".toRequestBody(null))
            .build()
        client.newCall(request).execute().close()

        val logs = Logging.getRequestLogs()
        assertEquals(1, logs.size)
        val entry = logs.single()
        assertEquals("HTTP", entry.tag)
        assertEquals("POST", entry.method)
        assertEquals("http://127.0.0.1:9", entry.url)
        assertEquals(200, entry.responseCode)
        assertTrue(entry.requestHeaders.isEmpty())
        assertNull(entry.requestBody)
        assertNull(entry.error)
        assertTrue(entry.durationMs != null && entry.durationMs >= 0)
        // 整条记录里不出现任何敏感串
        val rendered = listOf(entry.url, entry.method) + entry.requestHeaders.values +
            listOfNotNull(entry.requestBody, entry.error)
        assertTrue(rendered.none { "SECRET" in it })
    }

    @Test
    fun `logs nothing when the switch is off`() {
        Logging.setRequestLoggingEnabled(false)
        val client = clientReturning(200, "ok")
        client.newCall(Request.Builder().url("http://127.0.0.1:9/ping").build())
            .execute().close()
        assertTrue(Logging.getRequestLogs().isEmpty())
    }

    @Test
    fun `logs error entry with host only on IOException`() {
        val client = clientThrowing(IOException("connection reset"))
        try {
            client.newCall(Request.Builder().url("http://127.0.0.1:9/botSECRET_TOKEN/x").build())
                .execute().close()
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
            // expected
        }
        val logs = Logging.getRequestLogs()
        assertEquals(1, logs.size)
        val entry = logs.single()
        assertEquals("http://127.0.0.1:9", entry.url)
        assertNull(entry.responseCode)
        assertEquals("connection reset", entry.error)
        assertTrue(entry.requestHeaders.isEmpty())
        assertNull(entry.requestBody)
    }
}
