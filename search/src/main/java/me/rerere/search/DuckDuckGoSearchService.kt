package me.rerere.search

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.extract.ExtractMode
import me.rerere.search.extract.ScrapeSchema
import me.rerere.search.extract.WebExtractor
import me.rerere.search.net.hostIsBlockedLiteral
import me.rerere.search.net.withEgressGuard
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

object DuckDuckGoSearchService : SearchService<SearchServiceOptions.DuckDuckGoOptions> {

    override val name: String = "DuckDuckGo"

    private const val ENDPOINT = "https://html.duckduckgo.com/html/?q="

    @Composable
    override fun Description() {
    }

    override fun parameters(options: SearchServiceOptions.DuckDuckGoOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.DuckDuckGoOptions): InputSchema? =
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
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val url = ENDPOINT + URLEncoder.encode(query, "UTF-8")
            val locale = Locale.getDefault()
            val acceptLanguage = "${locale.language}-${locale.country},${locale.language}"
            val body = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                )
                .header("Accept-Language", acceptLanguage)
                .header("Accept-Encoding", "gzip, deflate, sdch")
                .header("Accept-Charset", "utf-8")
                .header("Connection", "keep-alive")
                .referrer("https://duckduckgo.com/")
                .timeout(5000)
                .execute()
                .body()

            val results = parseResults(body, commonOptions.resultSize)

            require(results.isNotEmpty()) {
                "Search failed: no results found"
            }

            SearchResult(items = results)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.contentOrNull?.trim()
            require(!url.isNullOrBlank()) { "url is required" }
            require(url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                "url must be an absolute http(s) URL"
            }
            require(!hostIsBlockedLiteral(url.toHttpUrlOrNull()?.host)) {
                "blocked_private_address: $url"
            }
            val mode = when (params["mode"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "text" -> ExtractMode.TEXT
                "links" -> ExtractMode.LINKS
                "metadata" -> ExtractMode.METADATA
                else -> ExtractMode.ARTICLE
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ScrapeSchema.DEFAULT_USER_AGENT)
                .get()
                .build()

            val guarded = httpClient.withEgressGuard()
            guarded.newCall(request).execute().use { resp ->
                require(resp.isSuccessful) { "HTTP ${resp.code} from $url" }
                val html = resp.body.string()
                val page = WebExtractor.extract(
                    html = html,
                    baseUrl = url,
                    mode = mode,
                    maxChars = 32 * 1024,
                    startIndex = 0,
                )
                require(page.text.isNotBlank() || mode != ExtractMode.ARTICLE) {
                    "no readable content extracted from $url"
                }
                ScrapedResult(
                    urls = listOf(
                        ScrapedResultUrl(
                            url = url,
                            content = page.text,
                            metadata = ScrapedResultMetadata(
                                title = page.title,
                                description = page.description,
                                language = page.language,
                            ),
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Parse the HTML endpoint's result list. Split out from [search] so it is testable
     * without a network round trip: the markup, not the transport, is what breaks.
     */
    internal fun parseResults(html: String, limit: Int): List<SearchResult.SearchResultItem> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        return doc.select("div.result").mapNotNull { row ->
            val anchor = row.selectFirst("a.result__a") ?: return@mapNotNull null
            val title = anchor.text().trim().ifBlank { return@mapNotNull null }
            val href = unwrapRedirect(anchor.attr("href")).ifBlank { return@mapNotNull null }
            val snippet = row.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            SearchResult.SearchResultItem(title = title, url = href, text = snippet)
        }.take(limit)
    }

    /** DuckDuckGo wraps outbound links as `//duckduckgo.com/l/?uddg=<encoded>`. */
    private fun unwrapRedirect(href: String): String {
        if (!href.contains("uddg=")) return href
        val encoded = href.substringAfter("uddg=").substringBefore("&")
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(href)
    }
}
