package me.rerere.ai.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSettingTest {
    @Test
    fun `supportsBuiltInSearch follows provider capability`() {
        // OpenAI：只有 Responses API 有 web_search 工具
        assertFalse(ProviderSetting.OpenAI().supportsBuiltInSearch)
        assertTrue(ProviderSetting.OpenAI().copy(useResponseApi = true).supportsBuiltInSearch)
        assertTrue(ProviderSetting.Google().supportsBuiltInSearch)
        assertTrue(ProviderSetting.Claude().supportsBuiltInSearch)
        // GeminiOAuth 走 Google 的请求实现，同样支持
        assertTrue(ProviderSetting.GeminiOAuth().supportsBuiltInSearch)
        // 端侧模型与未实现内置搜索的提供商不支持
        assertFalse(ProviderSetting.AICore().supportsBuiltInSearch)
        assertFalse(ProviderSetting.LiteRtLocal().supportsBuiltInSearch)
        assertFalse(ProviderSetting.LlamaCppLocal().supportsBuiltInSearch)
        assertFalse(ProviderSetting.Codex().supportsBuiltInSearch)
        assertFalse(ProviderSetting.Grok().supportsBuiltInSearch)
        assertFalse(ProviderSetting.Backend().supportsBuiltInSearch)
    }
}
