package me.rerere.rikkahub.ui.pages.setting

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 模型列表拉取 key 推导（[modelListBaseUrl]）：
 * produceState 的 key 是 (providerId, baseUrl, refreshTrigger)，仅 models 不同时 key
 * 必须相同（增删排序已添加模型不重拉远端列表），baseUrl 变化时 key 必须变化。
 */
class ModelListBaseUrlTest {

    @Test
    fun `openai returns its baseUrl`() {
        assertEquals("https://api.openai.com/v1", ProviderSetting.OpenAI().modelListBaseUrl())
        assertEquals(
            "https://example.com/v1",
            ProviderSetting.OpenAI(baseUrl = "https://example.com/v1").modelListBaseUrl(),
        )
    }

    @Test
    fun `google claude backend return their baseUrl`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            ProviderSetting.Google().modelListBaseUrl(),
        )
        assertEquals("https://api.anthropic.com/v1", ProviderSetting.Claude().modelListBaseUrl())
        assertEquals(
            "https://backend.example.com",
            ProviderSetting.Backend(baseUrl = "https://backend.example.com").modelListBaseUrl(),
        )
        assertEquals("", ProviderSetting.Backend().modelListBaseUrl())
    }

    @Test
    fun `types without baseUrl return empty string`() {
        assertEquals("", ProviderSetting.AICore().modelListBaseUrl())
        assertEquals("", ProviderSetting.LiteRtLocal().modelListBaseUrl())
        assertEquals("", ProviderSetting.LlamaCppLocal().modelListBaseUrl())
        assertEquals("", ProviderSetting.Codex().modelListBaseUrl())
        assertEquals("", ProviderSetting.Grok().modelListBaseUrl())
        assertEquals("", ProviderSetting.GeminiOAuth().modelListBaseUrl())
    }

    @Test
    fun `models-only change keeps the key`() {
        val before = ProviderSetting.OpenAI(models = listOf(Model(modelId = "gpt-5")))
        val after = before.copy(models = before.models + Model(modelId = "gpt-5-mini"))
        assertEquals(before.id, after.id)
        assertEquals(before.modelListBaseUrl(), after.modelListBaseUrl())
    }

    @Test
    fun `baseUrl change changes the key`() {
        val before = ProviderSetting.OpenAI(baseUrl = "https://a.example.com/v1")
        val after = before.copy(baseUrl = "https://b.example.com/v1")
        assertNotEquals(before.modelListBaseUrl(), after.modelListBaseUrl())
    }
}
