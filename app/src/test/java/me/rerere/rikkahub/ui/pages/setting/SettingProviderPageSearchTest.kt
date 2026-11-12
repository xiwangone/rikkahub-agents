package me.rerere.rikkahub.ui.pages.setting

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 供应商搜索（[ProviderSetting.matchesProviderQuery]）：以前只按名字含
 * 搜索串过滤，搜模型 ID、供应商 key、本地化类型名都搜不到。
 */
class SettingProviderPageSearchTest {

    private val provider =
        ProviderSetting.OpenAI(
            name = "Work Account",
            models =
                listOf(
                    Model(modelId = "gpt-5", displayName = "GPT-5"),
                    Model(modelId = "o3-mini", displayName = "o3 mini"),
                ),
        )

    @Test
    fun `blank query matches everything`() {
        assertTrue(provider.matchesProviderQuery(""))
        assertTrue(provider.matchesProviderQuery("   "))
    }

    @Test
    fun `name matches case-insensitively`() {
        assertTrue(provider.matchesProviderQuery("work"))
        assertTrue(provider.matchesProviderQuery("WORK ACCOUNT"))
        assertFalse(provider.matchesProviderQuery("personal"))
    }

    @Test
    fun `provider key matches`() {
        assertTrue(provider.matchesProviderQuery("openai"))
        assertTrue(provider.matchesProviderQuery("OPENAI"))
    }

    @Test
    fun `localized type name matches`() {
        assertTrue(provider.matchesProviderQuery("OpenAI", typeName = "OpenAI"))
        assertTrue(provider.matchesProviderQuery("open", typeName = "OpenAI"))
        assertFalse(provider.matchesProviderQuery("google", typeName = "OpenAI"))
    }

    @Test
    fun `model id and display name match`() {
        assertTrue(provider.matchesProviderQuery("gpt-5"))
        assertTrue(provider.matchesProviderQuery("GPT-5"))
        assertTrue(provider.matchesProviderQuery("o3 mini"))
        assertTrue(provider.matchesProviderQuery("o3-mini"))
        assertFalse(provider.matchesProviderQuery("claude-4"))
    }
}
