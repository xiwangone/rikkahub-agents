package me.rerere.rikkahub.data.ai

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import me.rerere.common.android.Logging
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 批 1（覆-P0-1）：请求日志开关关时默认不记，但失败必须留痕——
 * 非 200 响应与传输失败强制记一条；开关开时全记（含请求体）。
 * 强制条目只含元数据、不含请求体（开关关时用户未授权请求体入日志，
 * 且事后读 body 可能遇到一次性流）。
 *
 * [Logging] 是单例：每个测试先清 buffer、把开关复位为关，不依赖执行顺序。
 * 本测试是纯 JVM 测试：拦截器路径不碰 android.*（[Logging] 与
 * [me.rerere.rikkahub.utils.LogRedactor] 均为纯 JVM 代码），不需要 Robolectric。
 */
class RequestLoggingInterceptorTest {
    private val interceptor = RequestLoggingInterceptor()

    @Before
    fun setUp() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(false)
    }

    @After
    fun tearDown() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(false)
    }

    @Test
    fun `switch off and 200 records nothing`() {
        val request = postRequest()
        val response = interceptor.intercept(FakeChain(request, responseFor(request, 200)))

        assertEquals(200, response.code)
        assertTrue(Logging.getRequestLogs().isEmpty())
    }

    @Test
    fun `switch off and non-200 forces one metadata-only entry`() {
        val request = postRequest()
        val response = interceptor.intercept(FakeChain(request, responseFor(request, 500)))

        assertEquals(500, response.code)
        val entries = Logging.getRequestLogs()
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals(500, entry.responseCode)
        assertEquals("POST", entry.method)
        assertEquals("https://api.example.com/v1/chat/completions", entry.url)
        assertNull(entry.requestBody)
        // 强制条目也要脱敏：Authorization 头不能含原始 key。
        val auth = entry.requestHeaders["Authorization"].orEmpty()
        assertFalse(auth.contains("sk-testkey1234567890"))
    }

    @Test
    fun `switch off and transport failure forces one entry and rethrows`() {
        val request = postRequest()
        val failure = IOException("connection reset")
        try {
            interceptor.intercept(FakeChain(request, null, failure))
            fail("expected IOException to propagate")
        } catch (e: IOException) {
            assertEquals("connection reset", e.message)
        }

        val entries = Logging.getRequestLogs()
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertNull(entry.responseCode)
        assertEquals("connection reset", entry.error)
        assertNull(entry.requestBody)
    }

    @Test
    fun `switch on and 200 records one entry with body`() {
        Logging.setRequestLoggingEnabled(true)
        val request = postRequest()
        val response = interceptor.intercept(FakeChain(request, responseFor(request, 200)))

        assertEquals(200, response.code)
        val entries = Logging.getRequestLogs()
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals(200, entry.responseCode)
        assertTrue(entry.requestBody?.contains("test-model") == true)
    }

    @Test
    fun `switch on and non-200 records one entry with body`() {
        Logging.setRequestLoggingEnabled(true)
        val request = postRequest()
        interceptor.intercept(FakeChain(request, responseFor(request, 429)))

        val entries = Logging.getRequestLogs()
        assertEquals(1, entries.size)
        assertEquals(429, entries.first().responseCode)
        assertTrue(entries.first().requestBody?.contains("test-model") == true)
    }

    private fun postRequest(): Request =
        Request.Builder()
            .url("https://api.example.com/v1/chat/completions")
            .post("{\"model\":\"test-model\"}".toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer sk-testkey1234567890")
            .build()

    private fun responseFor(request: Request, code: Int): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .build()

    /** 只实现拦截器实际调用的 request()/proceed()，其余按 OkHttp 5.4 的 Chain 契约给默认值。 */
    private class FakeChain(
        private val req: Request,
        private val cannedResponse: Response?,
        private val failure: IOException? = null,
    ) : Interceptor.Chain {
        override fun request(): Request = req

        override fun proceed(request: Request): Response {
            failure?.let { throw it }
            return cannedResponse ?: error("no canned response")
        }

        override fun connection(): Connection? = null

        override fun call(): Call = throw UnsupportedOperationException("not used")

        override fun connectTimeoutMillis(): Int = 0

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun readTimeoutMillis(): Int = 0

        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 0

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override val followSslRedirects: Boolean = true

        override val followRedirects: Boolean = true

        override val dns: Dns = Dns.SYSTEM

        override fun withDns(dns: Dns): Interceptor.Chain = this

        override val socketFactory: SocketFactory = SocketFactory.getDefault()

        override fun withSocketFactory(socketFactory: SocketFactory): Interceptor.Chain = this

        override val retryOnConnectionFailure: Boolean = true

        override fun withRetryOnConnectionFailure(retryOnConnectionFailure: Boolean): Interceptor.Chain = this

        // 以下成员拦截器不调用，按 OkHttp 5.4.0 的 Chain 契约补齐：
        // 可空成员给 null，不可空成员抛 UnsupportedOperationException。
        override val authenticator: Authenticator
            get() = throw UnsupportedOperationException("not used")

        override fun withAuthenticator(authenticator: Authenticator): Interceptor.Chain = this

        override val cookieJar: CookieJar
            get() = throw UnsupportedOperationException("not used")

        override fun withCookieJar(cookieJar: CookieJar): Interceptor.Chain = this

        override val cache: Cache? = null

        override fun withCache(cache: Cache?): Interceptor.Chain = this

        override val proxy: Proxy? = null

        override fun withProxy(proxy: Proxy?): Interceptor.Chain = this

        override val proxySelector: ProxySelector
            get() = throw UnsupportedOperationException("not used")

        override fun withProxySelector(proxySelector: ProxySelector): Interceptor.Chain = this

        override val proxyAuthenticator: Authenticator
            get() = throw UnsupportedOperationException("not used")

        override fun withProxyAuthenticator(proxyAuthenticator: Authenticator): Interceptor.Chain = this

        override val sslSocketFactoryOrNull: SSLSocketFactory? = null

        override fun withSslSocketFactory(
            sslSocketFactory: SSLSocketFactory?,
            x509TrustManager: X509TrustManager?,
        ): Interceptor.Chain = this

        override val x509TrustManagerOrNull: X509TrustManager? = null

        override val hostnameVerifier: HostnameVerifier
            get() = throw UnsupportedOperationException("not used")

        override fun withHostnameVerifier(hostnameVerifier: HostnameVerifier): Interceptor.Chain = this

        override val certificatePinner: CertificatePinner
            get() = throw UnsupportedOperationException("not used")

        override fun withCertificatePinner(certificatePinner: CertificatePinner): Interceptor.Chain = this

        override val connectionPool: ConnectionPool
            get() = throw UnsupportedOperationException("not used")

        override fun withConnectionPool(connectionPool: ConnectionPool): Interceptor.Chain = this

        override val eventListener: EventListener
            get() = throw UnsupportedOperationException("not used")
    }
}
