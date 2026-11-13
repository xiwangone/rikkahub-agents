package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelFilterTest {
    private val models = listOf(
        Model(modelId = "gpt-5-mini", displayName = "GPT-5 Mini"),
        Model(modelId = "gpt-5-chat", displayName = "GPT-5 Chat"),
        Model(modelId = "claude-sonnet-4-5", displayName = "Claude Sonnet 4.5"),
        Model(modelId = "deepseek-v3", displayName = "DeepSeek V3"),
    )

    @Test
    fun `empty query returns original list`() {
        assertEquals(models, models.filterByKeywords(""))
        assertEquals(models, models.filterByKeywords("   "))
    }

    @Test
    fun `single keyword matches modelId or displayName`() {
        assertEquals(listOf(models[0], models[1]), models.filterByKeywords("gpt"))
        assertEquals(listOf(models[2]), models.filterByKeywords("Claude"))
    }

    @Test
    fun `multiple keywords all must match`() {
        assertEquals(listOf(models[0]), models.filterByKeywords("gpt 5 mini"))
        assertEquals(emptyList<Model>(), models.filterByKeywords("gpt claude"))
    }

    @Test
    fun `keywords may match across modelId and displayName`() {
        // "Mini" 只在 displayName 里，"gpt" 只在 modelId 里
        assertEquals(listOf(models[0]), models.filterByKeywords("gpt Mini"))
    }

    @Test
    fun `displayName-only keyword does not match modelId`() {
        // "Chat" 只在 displayName 里：gpt-5-mini 不应命中
        assertEquals(listOf(models[1]), models.filterByKeywords("Chat"))
    }
}
