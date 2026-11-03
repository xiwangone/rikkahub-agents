package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for ChatCompletionsAPI token usage parsing.
 */
class ChatCompletionsUsageParsingTest {

    // Helper to invoke the top-level parseTokenUsage (ChatCompletionsResponseParser.kt).
    // 同名成员已不存在，反射会 NoSuchMethodException；用全限定名避开与本方法重名。
    private fun parseTokenUsage(usage: JsonObject): TokenUsage? =
        me.rerere.ai.provider.providers.openai.parseTokenUsage(usage)

    // #1576: cached tokens 按 provider 方言兜底解析
    @Test
    fun `cached tokens fall back across provider dialects`() {
        fun usage(jsonStr: String) = parseTokenUsage(Json.parseToJsonElement(jsonStr).jsonObject)

        // OpenAI 官方嵌套格式
        assertEquals(
            12,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_tokens_details":{"cached_tokens":12}}""")?.cachedTokens
        )
        // Moonshot 顶层 cached_tokens
        assertEquals(
            7,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"cached_tokens":7}""")?.cachedTokens
        )
        // DeepSeek 顶层 prompt_cache_hit_tokens
        assertEquals(
            5,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_cache_hit_tokens":5,"prompt_cache_miss_tokens":3}""")?.cachedTokens
        )
        // 嵌套字段优先于顶层兜底
        assertEquals(
            12,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_tokens_details":{"cached_tokens":12},"cached_tokens":7}""")?.cachedTokens
        )
        // 都没有时为 0
        assertEquals(
            0,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}""")?.cachedTokens
        )
    }
}
