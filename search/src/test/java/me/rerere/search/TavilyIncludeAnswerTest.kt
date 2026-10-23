package me.rerere.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavilyIncludeAnswerTest {
    @Test
    fun `default keeps legacy generated answer request`() = runBlocking {
        val value = includeAnswerRequestValue(SearchServiceOptions.TavilyOptions())
        assertEquals("advanced", value.content)
        assertTrue(value.isString)
    }

    @Test
    fun `disabled answer is sent as boolean false`() = runBlocking {
        val value = includeAnswerRequestValue(SearchServiceOptions.TavilyOptions(includeAnswer = false))
        assertEquals("false", value.content)
        assertFalse(value.isString)
    }

    @Test
    fun `legacy serialized options keep answer enabled`() {
        val options = Json.decodeFromString<SearchServiceOptions.TavilyOptions>("{}")
        assertTrue(options.includeAnswer)
    }

    private suspend fun includeAnswerRequestValue(options: SearchServiceOptions.TavilyOptions): JsonPrimitive {
        val previous = SearchService.httpClient
        var bodyText: String? = null
        try {
            SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                bodyText = chain.request().body!!.let { body ->
                    val buffer = okio.Buffer()
                    body.writeTo(buffer)
                    buffer.readUtf8()
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"query\":\"stable Kotlin release\",\"results\":[]}".toResponseBody())
                    .build()
            }.build()
            val params = buildJsonObject { put("query", "stable Kotlin release") }
            TavilySearchService.search(params, SearchCommonOptions(), options.copy(apiKey = "test-key"))
                .getOrThrow()
            return Json.parseToJsonElement(bodyText!!).jsonObject["include_answer"]!!.jsonPrimitive
        } finally {
            SearchService.httpClient = previous
        }
    }
}
