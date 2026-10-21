package me.rerere.search

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.util.KeyRoulette
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.uuid.Uuid

interface SearchService<T : SearchServiceOptions> {
    val name: String

    fun parameters(options: T): InputSchema?

    fun scrapingParameters(options: T): InputSchema?

    @Composable
    fun Description()

    suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T,
    ): Result<SearchResult>

    suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T,
    ): Result<ScrapedResult>

    companion object {
        @Suppress("UNCHECKED_CAST", "CyclomaticComplexMethod") // 服务名到实现的线性分派；分支随服务数量增长，拆分反而割裂同一张对照表
        fun <T : SearchServiceOptions> getService(options: T): SearchService<T> =
            when (options) {
                is SearchServiceOptions.TavilyOptions -> TavilySearchService
                is SearchServiceOptions.ExaOptions -> ExaSearchService
                is SearchServiceOptions.ZhipuOptions -> ZhipuSearchService
                is SearchServiceOptions.DoubaoOptions -> DoubaoSearchService
                is SearchServiceOptions.BingLocalOptions -> BingSearchService
                is SearchServiceOptions.DuckDuckGoOptions -> DuckDuckGoSearchService
                is SearchServiceOptions.SearXNGOptions -> SearXNGService
                is SearchServiceOptions.LinkUpOptions -> LinkUpService
                is SearchServiceOptions.BraveOptions -> BraveSearchService
                is SearchServiceOptions.MetasoOptions -> MetasoSearchService
                is SearchServiceOptions.OllamaOptions -> OllamaSearchService
                is SearchServiceOptions.PerplexityOptions -> PerplexitySearchService
                is SearchServiceOptions.FirecrawlOptions -> FirecrawlSearchService
                is SearchServiceOptions.JinaOptions -> JinaSearchService
                is SearchServiceOptions.BochaOptions -> BochaSearchService
                is SearchServiceOptions.RikkaHubOptions -> RikkaHubSearchService
                is SearchServiceOptions.GrokOptions -> GrokSearchService
                is SearchServiceOptions.TinyfishOptions -> TinyfishSearchService
                is SearchServiceOptions.SerperOptions -> SerperSearchService
                is SearchServiceOptions.DeepSeekOptions -> DeepSeekSearchService
                is SearchServiceOptions.CustomJsOptions -> CustomJsSearchService
            } as SearchService<T>

        @Volatile
        internal var httpClient: OkHttpClient =
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

        @Volatile
        internal var keyRoulette: KeyRoulette = KeyRoulette.default()

        fun init(
            client: OkHttpClient,
            context: Context? = null,
        ) {
            httpClient = client
            keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
        }

        internal val json by lazy {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
    }
}

/** Cap for a scraped page body: bounds memory before Jsoup parses it (which roughly doubles
 * the footprint), so a huge or unbounded response can't be dragged fully into memory first. */
internal const val SCRAPE_BODY_CAP = 256 * 1024

/**
 * Read at most [capBytes] from [response]'s body and decode it using its declared charset
 * (UTF-8 fallback), mirroring the chunked-read loop WebFetchTool.readBounded uses so a
 * multi-GB response never gets buffered whole just because callTimeout only bounds time.
 */
internal fun boundedBody(response: Response, capBytes: Int): String {
    val charset = response.body.contentType()?.charset() ?: Charsets.UTF_8
    val ins = response.body.byteStream()
    val out = java.io.ByteArrayOutputStream(minOf(capBytes, 8 * 1024))
    val buf = ByteArray(8192)
    var total = 0
    while (total < capBytes) {
        val want = minOf(buf.size, capBytes - total)
        val read = ins.read(buf, 0, want)
        if (read < 0) break
        out.write(buf, 0, read)
        total += read
    }
    return String(out.toByteArray(), charset)
}

@Serializable
data class SearchCommonOptions(
    val resultSize: Int = 10,
)

@Serializable
data class SearchResult(
    val answer: String? = null,
    val items: List<SearchResultItem>,
    val images: List<String> = emptyList(),
    /** Local time at which this result was retrieved; not a publication date. */
    val retrievedAt: String? = null,
) {
    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val text: String,
        /** Provider-supplied publication date. Null means that no date was supplied. */
        val publishedDate: String? = null,
        val highlights: List<String> = emptyList(),
    )
}

@Serializable
data class ScrapedResult(
    val urls: List<ScrapedResultUrl>,
    /** Local time at which this result was retrieved; not a publication date. */
    val retrievedAt: String? = null,
)

@Serializable
data class ScrapedResultUrl(
    val url: String,
    val content: String,
    val metadata: ScrapedResultMetadata? = null,
)

@Serializable
data class ScrapedResultMetadata(
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
    /** Provider-supplied publication date. Null means that no date was supplied. */
    val publishedDate: String? = null,
)

@Serializable
sealed class SearchServiceOptions {
    abstract val id: Uuid

    /**
     * 稳定类型键：与子类 `@SerialName` 字面量一致。
     *
     * 序列化名是数据契约，显示名/创建/可添加列表一律按该字符串匹配。
     * **新增类型时：`@SerialName` 与 `typeKey` 两处字面量必须相同。**
     *
     * （原注释把「添加后全变 DuckDuckGo」归因于 R8 破坏 KClass 相等匹配，已否证：本机用
     * Kotlin 2.4.20 + kotlinx-serialization 1.11.0 + R8 8.5.35、按 app/proguard-rules.pro
     * 现有规则收缩后，create/typeKey/displayName/多态序列化往返全部正确。真因见
     * `DEFAULT` 的声明类型注释与 PreferencesStore 的 search_services 解码处。）
     */
    abstract val typeKey: String

    open val displayName: String
        get() = TYPES[typeKey] ?: "Unknown"

    companion object {
        /**
         * **声明类型必须写死成 `SearchServiceOptions`，不能省。**
         *
         * 省略时它推断为 `DuckDuckGoOptions`。而设置读取用
         * `decodePrefOrDefault(raw, listOf(SearchServiceOptions.DEFAULT))` 这种形状：泛型 `T`
         * 同时由「返回值槽位」和「default 实参」决定，Kotlin 取最具体的 `List<DuckDuckGoOptions>`
         * （赋值给 `List<SearchServiceOptions>` 靠协变通过，不会把 T 放宽回基类）。于是
         * reified 解码拿到的是 **DuckDuckGoOptions 的具体序列化器**：`type` 判别键被
         * `ignoreUnknownKeys` 当成未知键忽略，**盘上任何类型都静默解成 DuckDuckGoOptions**，
         * 连 apiKey / 脚本等子类字段一起丢。这就是「添加任何搜索服务都变 DuckDuckGo」的根因
         * （写入侧 `encodeToString(settings.searchServices)` 静态类型是基类，所以盘上的
         * `"type":"exa"` 一直是对的 —— 读写不对称恰好掩盖了它）。
         */
        val DEFAULT: SearchServiceOptions = DuckDuckGoOptions()

        /**
         * 按类型创建实例。
         *
         * 不用 `primaryConstructor.callBy()`：它依赖运行时反射读的类元数据，在收缩/混淆后的发行包里不可靠，
         * 且 `callBy` 不会处理默认参数。这里逐个列出，新增类型时必须同步加一行。
         */
        // 每个分支是一次类型映射，线性穷举、无嵌套；拆成多组反而会把“哪个类型归哪组”变成隐式约定，
        // 新增类型时更容易漏，故保持单层 when.
        @Suppress("CyclomaticComplexMethod")
        fun create(typeKey: String): SearchServiceOptions =
            when (typeKey) {
                "duckduckgo" -> DuckDuckGoOptions()
                "bing_local" -> BingLocalOptions()
                "rikkahub" -> RikkaHubOptions()
                "zhipu" -> ZhipuOptions()
                "doubao" -> DoubaoOptions()
                "tavily" -> TavilyOptions()
                "exa" -> ExaOptions()
                "searxng" -> SearXNGOptions()
                "linkup" -> LinkUpOptions()
                "brave" -> BraveOptions()
                "metaso" -> MetasoOptions()
                "ollama" -> OllamaOptions()
                "perplexity" -> PerplexityOptions()
                "firecrawl" -> FirecrawlOptions()
                "jina" -> JinaOptions()
                "bocha" -> BochaOptions()
                "grok" -> GrokOptions()
                "tinyfish" -> TinyfishOptions()
                "serper" -> SerperOptions()
                "deepseek" -> DeepSeekOptions()
                "custom_js" -> CustomJsOptions()
                else -> DEFAULT
            }

        /** typeKey（= @SerialName 字面量）→ 显示名。顺序即「添加服务」对话框的展示顺序。 */
        val TYPES =
            mapOf(
                "bing_local" to "Bing",
                "rikkahub" to "RikkaHub",
                "zhipu" to "智谱",
                "doubao" to "豆包",
                "tavily" to "Tavily",
                "exa" to "Exa",
                "searxng" to "SearXNG",
                "linkup" to "LinkUp",
                "brave" to "Brave",
                "metaso" to "秘塔",
                "ollama" to "Ollama",
                "perplexity" to "Perplexity",
                "firecrawl" to "Firecrawl",
                "jina" to "Jina",
                "bocha" to "博查",
                "grok" to "Grok",
                "tinyfish" to "Tinyfish",
                "serper" to "Serper",
                "deepseek" to "DeepSeek",
                "custom_js" to "Custom JS",
                "duckduckgo" to "DuckDuckGo",
            )

        /** 可添加的服务类型：DuckDuckGo 是未配置时的默认兜底，不作为「添加」项。 */
        val ADDABLE_TYPES: Map<String, String> =
            TYPES.filterKeys { it != "duckduckgo" }
    }

    @Serializable
    @SerialName("bing_local")
    class BingLocalOptions(
        override val id: Uuid = Uuid.random(),
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "bing_local"
    }

    @Serializable
    @SerialName("duckduckgo")
    data class DuckDuckGoOptions(
        override val id: Uuid = Uuid.random(),
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "duckduckgo"
    }

    @Serializable
    @SerialName("zhipu")
    data class ZhipuOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "zhipu"
    }

    @Serializable
    @SerialName("doubao")
    data class DoubaoOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val mode: DoubaoSearchMode = DoubaoSearchMode.CUSTOM,
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "doubao"
    }

    @Serializable
    @SerialName("tavily")
    data class TavilyOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "advanced",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "tavily"
    }

    @Serializable
    @SerialName("exa")
    data class ExaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "exa"
    }

    @Serializable
    @SerialName("searxng")
    data class SearXNGOptions(
        override val id: Uuid = Uuid.random(),
        val url: String = "",
        val engines: String = "",
        val language: String = "",
        val username: String = "",
        val password: String = "",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "searxng"
    }

    @Serializable
    @SerialName("linkup")
    data class LinkUpOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "standard",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "linkup"
    }

    @Serializable
    @SerialName("brave")
    data class BraveOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "brave"
    }

    @Serializable
    @SerialName("metaso")
    data class MetasoOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "metaso"
    }

    @Serializable
    @SerialName("ollama")
    data class OllamaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "ollama"
    }

    @Serializable
    @SerialName("perplexity")
    data class PerplexityOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val maxTokens: Int? = null,
        val maxTokensPerPage: Int? = null,
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "perplexity"
    }

    @Serializable
    @SerialName("firecrawl")
    data class FirecrawlOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "firecrawl"
    }

    @Serializable
    @SerialName("jina")
    data class JinaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val searchUrl: String = "https://s.jina.ai/",
        val scrapeUrl: String = "https://r.jina.ai/",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "jina"
    }

    @Serializable
    @SerialName("bocha")
    data class BochaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val summary: Boolean = true,
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "bocha"
    }

    @Serializable
    @SerialName("rikkahub")
    data class RikkaHubOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "standard",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "rikkahub"
    }

    @Serializable
    @SerialName("grok")
    data class GrokOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val model: String = "grok-4-1-fast-non-reasoning",
        val customUrl: String = "https://api.x.ai/v1/responses",
        val systemPrompt: String = "You are a helpful search assistant. Search the web to find accurate and up-to-date information for the user's query. Provide a comprehensive answer with citations.",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "grok"
    }

    @Serializable
    @SerialName("tinyfish")
    data class TinyfishOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "tinyfish"
    }

    @Serializable
    @SerialName("serper")
    data class SerperOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "serper"
    }

    @Serializable
    @SerialName("deepseek")
    data class DeepSeekOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "deepseek"
    }
    @Serializable
    @SerialName("custom_js")
    data class CustomJsOptions(
        override val id: Uuid = Uuid.random(),
        val name: String = "",
        val searchScript: String = DEFAULT_SEARCH_SCRIPT,
        val scrapeScript: String = "",
    ) : SearchServiceOptions() {
        override val typeKey: String get() = "custom_js"
        override val displayName: String
            get() = name.ifBlank { "Custom JS" }

        companion object {
            const val DEFAULT_SCRAPE_SCRIPT = """// Implement scrape(urls) function
// urls is an array of URL strings
// Use fetch(url, options?) for HTTP requests
// fetch() returns { status, ok, text(), json() }
// Return { urls: [{ url, content, metadata?: { title?, description?, language? } }] }

function scrape(urls) {
  return {
    urls: urls.map(function(url) {
      const res = fetch(url);
      const body = res.text();
      return { url: url, content: body };
    })
  };
}"""

            const val DEFAULT_SEARCH_SCRIPT = """// Implement search(query, resultSize) function
// Use fetch(url, options?) for HTTP requests
// fetch() returns { status, ok, text(), json() }
// Return { items: [{ title, url, text }], answer?: string }

function search(query, resultSize) {
  const encoded = encodeURIComponent(query);
  const res = fetch("https://example.com/search?q=" + encoded + "&limit=" + resultSize);
  const data = res.json();
  return {
    items: data.results.map(function(r) {
      return { title: r.title, url: r.url, text: r.snippet };
    })
  };
}"""
        }
    }
}

internal suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        // 协程取消要传导到底层 Call，否则被中止的请求会在后台继续跑完（连接 / 流量 / 电量泄漏）。
        continuation.invokeOnCancellation { runCatching { cancel() } }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response) { cause, _, _ ->
                        response.closeQuietly()
                    }
                }
            },
        )
    }

@Serializable
enum class DoubaoSearchMode {
    @SerialName("global")
    GLOBAL,

    @SerialName("custom")
    CUSTOM,
}
