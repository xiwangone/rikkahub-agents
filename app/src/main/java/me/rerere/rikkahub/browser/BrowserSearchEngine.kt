package me.rerere.rikkahub.browser

import java.net.URLEncoder

/**
 * 内置浏览器可选的搜索引擎。
 *
 * 之所以做成枚举而不是散落的 URL 常量：搜索地址栏 [normalizeBrowserQuery] 的输入是纯文本，
 * 无法确定用户意图，需要一个稳定可持久化的标识（[id]）来记住用户选择，并在读取时对未知
 * 值安全回落（见 [fromId]）。
 *
 * [displayName] 是品牌名，按惯例不做翻译，因此不进入资源文件。
 * [queryTemplate] 中的 `%s` 由 [searchUrl] 替换为 URL-encode 后的关键词。
 * 默认值取 [DEFAULT]（Bing）——DuckDuckGo 在部分网络环境不可访问。
 */
enum class BrowserSearchEngine(
    /** 持久化到 DataStore 的稳定标识，不要随意改动。 */
    val id: String,
    /** 展示用品牌名（不翻译）。 */
    val displayName: String,
    /** 查询 URL 模板，`%s` 为关键词占位符。 */
    private val queryTemplate: String,
) {
    BING("bing", "Bing", "https://www.bing.com/search?q=%s"),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("google", "Google", "https://www.google.com/search?q=%s"),
    BAIDU("baidu", "Baidu", "https://www.baidu.com/s?wd=%s"),
    ;

    /** 把用户输入的关键词拼成搜索 URL。关键词统一 URL-encode（空格编码为 `+`）。 */
    fun searchUrl(query: String): String = queryTemplate.replace("%s", encodeQuery(query))

    companion object {
        /** 默认搜索引擎。 */
        val DEFAULT: BrowserSearchEngine = BING

        /** 选择列表的顺序来源（枚举声明顺序即展示顺序）。 */
        val ALL: List<BrowserSearchEngine> = entries.toList()

        /**
         * 按 [id] 查找；找不到（旧版本遗留、被手工改坏的偏好值）时回落到 [DEFAULT]，
         * 保证读取路径永远不会拿到非法值。
         */
        fun fromId(id: String?): BrowserSearchEngine =
            entries.firstOrNull { it.id == id } ?: DEFAULT

        private fun encodeQuery(query: String): String =
            runCatching { URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)
    }
}
