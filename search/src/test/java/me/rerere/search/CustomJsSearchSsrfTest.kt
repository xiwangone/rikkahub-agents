package me.rerere.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class CustomJsSearchSsrfTest {
    @Test(timeout = 15_000)
    fun `fetch to loopback is blocked`() = runBlocking {
        val (result, reached) = attemptFetch("http://127.0.0.1/")
        assertFalse("fetch to loopback must be blocked before reaching the network", reached)
        assertTrue("search must fail when fetch to loopback is blocked", result.isFailure)
    }

    @Test(timeout = 15_000)
    fun `fetch to cloud metadata endpoint is blocked`() = runBlocking {
        val (result, reached) = attemptFetch("http://169.254.169.254/")
        assertFalse("fetch to link-local metadata must be blocked before reaching the network", reached)
        assertTrue("search must fail when fetch to link-local metadata is blocked", result.isFailure)
    }

    private suspend fun attemptFetch(url: String): Pair<Result<SearchResult>, Boolean> {
        val previousClient = SearchService.httpClient
        val reached = AtomicBoolean(false)
        SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            reached.set(true)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("internal-secret".toResponseBody())
                .build()
        }.build()
        try {
            val result = CustomJsSearchService.search(
                buildJsonObject { put("query", "test") },
                SearchCommonOptions(),
                SearchServiceOptions.CustomJsOptions(searchScript = """
                    function search(query, resultSize) {
                        const res = fetch('$url');
                        if (!res.ok) throw Error('fetch blocked: ' + res.status);
                        return {items: [{title: 'leaked', url: '$url', text: res.text()}]};
                    }
                """.trimIndent()),
            )
            return result to reached.get()
        } finally {
            SearchService.httpClient = previousClient
        }
    }
}
