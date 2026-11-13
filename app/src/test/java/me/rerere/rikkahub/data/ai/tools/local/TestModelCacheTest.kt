package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * test_model 缓存 key 与 TTL 的契约：探测结论取决于 apiKey / baseUrl / 自定义头等输入，
 * key 里缺了它们，改配置或服务异常后都会返回同一份旧结论。
 *
 * 注意：key 里含序列化后的配置指纹；断言时用同一实例或 copy（id 保持不变），
 * 新构造的实例自带随机 id，本来就该算另一个 key。
 */
class TestModelCacheTest {

    private fun setting(apiKey: String = "sk-a", baseUrl: String = "https://api.openai.com/v1") =
        ProviderSetting.OpenAI(apiKey = apiKey, baseUrl = baseUrl)

    private fun model(customHeaders: List<CustomHeader> = emptyList()) =
        Model(modelId = "gpt-5", customHeaders = customHeaders)

    @Test
    fun `same inputs produce the same key`() {
        val s = setting()
        val m = model()
        assertEquals(testModelCacheKey(s, m), testModelCacheKey(s, m))
        assertEquals(testModelCacheKey(s, m), testModelCacheKey(s.copy(apiKey = "sk-a"), m.copy()))
    }

    @Test
    fun `rotated apiKey changes the key`() {
        val s = setting()
        val m = model()
        assertNotEquals(testModelCacheKey(s, m), testModelCacheKey(s.copy(apiKey = "sk-b"), m))
    }

    @Test
    fun `changed baseUrl changes the key`() {
        val s = setting()
        val m = model()
        assertNotEquals(testModelCacheKey(s, m), testModelCacheKey(s.copy(baseUrl = "https://b.example"), m))
    }

    @Test
    fun `changed model custom headers change the key`() {
        val s = setting()
        val m = model()
        val withHeader = m.copy(customHeaders = listOf(CustomHeader("X-Test", "1")))
        assertNotEquals(testModelCacheKey(s, m), testModelCacheKey(s, withHeader))
    }

    @Test
    fun `different model id changes the key`() {
        val s = setting()
        val m = model()
        assertNotEquals(testModelCacheKey(s, m), testModelCacheKey(s, m.copy(modelId = "gpt-4")))
    }

    @Test
    fun `cached entry is fresh before expiry and stale at expiry`() {
        val entry = CachedTestModelResult("payload", expiresAtMillis = 1_000L)
        assertTrue(entry.isFresh(nowMillis = 999L))
        assertFalse(entry.isFresh(nowMillis = 1_000L))
        assertFalse(entry.isFresh(nowMillis = 1_001L))
    }
}
