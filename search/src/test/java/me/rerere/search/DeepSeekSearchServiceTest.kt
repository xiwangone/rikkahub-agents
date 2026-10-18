package me.rerere.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline fixture: all calls are intercepted locally; no DeepSeek credential or API is used. */
class DeepSeekSearchServiceTest {
    private fun params(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun officialSearchRequestUsesRotatableConfiguredKeyAndParsesResult() = runBlocking {
        val previous = SearchService.httpClient
        try {
            SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("https://api.deepseek.com/v1/tools/search", request.url.toString())
                assertEquals("Bearer test-key", request.header("Authorization"))
                assertTrue(request.body!!.contentType().toString().contains("application/json"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"results":[{"title":"T","url":"https://example.test","summary":"S","date":"2026-01-01"}]}""".toResponseBody()).build()
            }.build()
            val result = DeepSeekSearchService.search(params("""{"query":"hello"}"""), SearchCommonOptions(3), SearchServiceOptions.DeepSeekOptions(apiKey = "test-key")).getOrThrow()
            assertEquals("T", result.items.single().title)
            assertEquals("S", result.items.single().text)
        } finally { SearchService.httpClient = previous }
    }


    @Test fun officialFetchRequestParsesContentAndTitle() = runBlocking {
        val previous = SearchService.httpClient
        try {
            SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("https://api.deepseek.com/v1/tools/fetch", request.url.toString())
                assertEquals("Bearer fetch-key", request.header("Authorization"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"title":"Page","content":"body text"}""".toResponseBody()).build()
            }.build()
            val result = DeepSeekSearchService.scrape(params("""{"url":"https://example.test"}"""), SearchCommonOptions(), SearchServiceOptions.DeepSeekOptions(apiKey = "fetch-key")).getOrThrow()
            assertEquals("body text", result.urls.single().content)
            assertEquals("Page", result.urls.single().metadata!!.title)
        } finally { SearchService.httpClient = previous }
    }

    @Test fun non2xxDoesNotExposeProviderBodyOrConfiguredKey() = runBlocking {
        val previous = SearchService.httpClient
        try {
            SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                    .body("secret test-key must-not-leak".toResponseBody()).build()
            }.build()
            val result = DeepSeekSearchService.search(params("""{"query":"hello"}"""), SearchCommonOptions(), SearchServiceOptions.DeepSeekOptions(apiKey = "test-key"))
            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()!!.message.orEmpty()
            assertTrue(error.contains("401")); assertFalse(error.contains("test-key")); assertFalse(error.contains("must-not-leak"))
        } finally { SearchService.httpClient = previous }
    }

    @Test fun missingSearchFieldsAreReportedAsFailure() = runBlocking {
        val previous = SearchService.httpClient
        try {
            SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{}".toResponseBody()).build()
            }.build()
            assertTrue(DeepSeekSearchService.search(params("""{"query":"hello"}"""), SearchCommonOptions(), SearchServiceOptions.DeepSeekOptions(apiKey = "test-key")).isFailure)
        } finally { SearchService.httpClient = previous }
    }
}
