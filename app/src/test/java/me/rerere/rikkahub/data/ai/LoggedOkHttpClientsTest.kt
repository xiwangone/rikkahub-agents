package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批 3 覆-P0-2：自建 OkHttpClient 统一经 [LoggedOkHttpClients] 挂完整请求日志拦截器。
 *
 * 断言工厂造出的 client 带有 [RequestLoggingInterceptor]（与 DI client 同口径的
 * network interceptor），且只挂一次。
 */
class LoggedOkHttpClientsTest {

    @Test
    fun `builder carries exactly one RequestLoggingInterceptor`() {
        val client = LoggedOkHttpClients.builder().build()
        val interceptors = client.networkInterceptors.filterIsInstance<RequestLoggingInterceptor>()
        assertTrue(
            "expected exactly one RequestLoggingInterceptor, got ${client.networkInterceptors}",
            interceptors.size == 1,
        )
    }
}
