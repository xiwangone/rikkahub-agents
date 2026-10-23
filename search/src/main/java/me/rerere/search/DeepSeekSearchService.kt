package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.uuid.Uuid

/** DeepSeek official web search/fetch API. Never put credentials or provider body text in errors. */
object DeepSeekSearchService : SearchService<SearchServiceOptions.DeepSeekOptions> {
    override val name = "DeepSeek"
    private const val SEARCH_URL = "https://api.deepseek.com/v1/tools/search"
    private const val FETCH_URL = "https://api.deepseek.com/v1/tools/fetch"
    private val jsonType = "application/json".toMediaType()

    @Composable
    override fun Description() {
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri("https://platform.deepseek.com/api_keys") }) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.DeepSeekOptions) = InputSchema.Obj(
        properties = buildJsonObject { put("query", buildJsonObject { put("type", "string"); put("description", "$SEARCH_QUERY_DESCRIPTION Search query must be 1-128 characters.") }) },
        required = listOf("query"),
    )

    override fun scrapingParameters(options: SearchServiceOptions.DeepSeekOptions) = InputSchema.Obj(
        properties = buildJsonObject { put("url", buildJsonObject { put("type", "string") }) },
        required = listOf("url"),
    )

    override suspend fun search(params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions.DeepSeekOptions): Result<SearchResult> = requestResult {
        val query = params["query"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: error("DeepSeek search requires query")
        val payload = buildJsonObject {
            put("query", query)
            put("count", commonOptions.resultSize.coerceIn(1, 50))
        }
        val responseText = post(SEARCH_URL, payload, serviceOptions)
        val decoded = json.decodeFromString<DeepSeekSearchResponse>(responseText)
        val results = decoded.results ?: error("DeepSeek search response is missing results")
        SearchResult(items = results.map { item ->
            val title = item.title?.takeIf(String::isNotBlank) ?: error("DeepSeek search result is missing title")
            val resultUrl = item.url?.takeIf(String::isNotBlank) ?: error("DeepSeek search result is missing url")
            SearchResultItem(title, resultUrl, item.summary?.takeIf(String::isNotBlank) ?: item.snippet.orEmpty(), item.date)
        })
    }

    override suspend fun scrape(params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions.DeepSeekOptions): Result<ScrapedResult> = requestResult {
        val url = params["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: error("DeepSeek fetch requires url")
        val responseText = post(FETCH_URL, buildJsonObject { put("url", url) }, serviceOptions)
        val decoded = json.decodeFromString<DeepSeekFetchResponse>(responseText)
        val content = decoded.content?.takeIf(String::isNotBlank) ?: error("DeepSeek fetch response is missing content")
        ScrapedResult(listOf(ScrapedResultUrl(url, content, ScrapedResultMetadata(decoded.title?.takeIf(String::isNotBlank)))) )
    }

    private suspend fun post(url: String, payload: JsonObject, options: SearchServiceOptions.DeepSeekOptions): String {
        val key = keyRoulette.next(options.apiKey, options.id.toString()).trim()
        require(key.isNotEmpty()) { "DeepSeek API key is not configured" }
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(jsonType)).build()
        return httpClient.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("DeepSeek request failed with HTTP ${response.code}")
            text.ifBlank { error("DeepSeek returned an empty response (HTTP ${response.code})") }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 网络请求失败类型不可枚举；取消已单独透传，其余统一转为 Result.failure
    private suspend fun <T> requestResult(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.success(block()) } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { Result.failure(error) }
    }

    @Serializable private data class DeepSeekSearchResponse(val results: List<DeepSeekSearchItem>? = null)
    @Serializable private data class DeepSeekSearchItem(val title: String? = null, val url: String? = null, val summary: String? = null, val snippet: String? = null, val date: String? = null)
    @Serializable private data class DeepSeekFetchResponse(val title: String? = null, val content: String? = null)
}
