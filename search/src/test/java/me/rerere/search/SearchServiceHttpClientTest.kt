package me.rerere.search

import me.rerere.common.android.SafeRequestLoggingInterceptor
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索请求挂最小请求日志拦截器（[SearchService.httpClient] 兜底 client）。
 *
 * 只记 host + 状态码 + 耗时，不记 path/query/headers/body（搜索 URL 含查询词，
 * `:common` 层无脱敏能力）。`init()` 换上 DI client 后走 `:app` 的完整拦截器，
 * 本用例只钉兜底 client 这一路。
 */
class SearchServiceHttpClientTest {

    @Test
    fun `fallback client carries the safe request logging interceptor`() {
        assertTrue(
            "expected SafeRequestLoggingInterceptor on SearchService.httpClient, " +
                "got: ${SearchService.httpClient.interceptors.map { it.javaClass.name }}",
            SearchService.httpClient.interceptors.any { it is SafeRequestLoggingInterceptor },
        )
    }
}
