package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.extract.ScrapeSchema
import me.rerere.search.net.withEgressGuard
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale

object BingSearchService : SearchService<SearchServiceOptions.BingLocalOptions> {
    override val name: String = "Bing"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.bing_desc))
    }

    override fun parameters(options: SearchServiceOptions.BingLocalOptions): InputSchema? =
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put("description", SEARCH_QUERY_DESCRIPTION)
                        },
                    )
                },
            required = listOf("query"),
        )

    override fun scrapingParameters(options: SearchServiceOptions.BingLocalOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", ScrapeSchema.URL_DESCRIPTION)
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", ScrapeSchema.MODE_DESCRIPTION)
                })
            },
            required = listOf("url"),
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions,
    ): Result<SearchResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
                val url = "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8")
                val locale = Locale.getDefault()
                val acceptLanguage = "${locale.language}-${locale.country},${locale.language}"
                // Fixed public endpoint, fetched through the DI client (proxy, app DNS,
                // egress guard) instead of a direct connection: the guard also covers
                // redirect hops. OkHttp handles gzip transparently, so no Accept-Encoding
                // header is set here; the body is bounded like the scrape path.
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    )
                    .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    )
                    .header("Accept-Language", acceptLanguage)
                    .header("Accept-Charset", "utf-8")
                    .header("Connection", "keep-alive")
                    .header("Referer", "https://www.bing.com/")
                    .header("Cookie", "SRCHHPGUSR=ULSR=1")
                    .build()
                val html = httpClient.withEgressGuard().newCall(request).await().use { resp ->
                    boundedBody(resp, SCRAPE_BODY_CAP)
                }
                val results = parseResults(html, commonOptions.resultSize.coerceIn(1, 50))
                // A genuine empty SERP is a successful empty result, not a failure:
                // the query was answered, there is just nothing matching it.
                SearchResult(items = results)
            }
        }

    /**
     * Parse the search page's result list. Split out from [search] so it is testable
     * without a network round trip: the markup, not the transport, is what breaks.
     */
    internal fun parseResults(html: String, limit: Int): List<SearchResultItem> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        return doc.select("li.b_algo").map { element ->
            val title = element.select("h2").text()
            val link = element.select("h2 > a").attr("href")
            val snippet = element.select(".b_caption p").text()
            SearchResultItem(
                title = title,
                url = link,
                text = snippet,
            )
        }.take(limit.coerceAtLeast(0))
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions,
    ): Result<ScrapedResult> = Result.failure(Exception("Scraping is not supported for Bing"))
}
