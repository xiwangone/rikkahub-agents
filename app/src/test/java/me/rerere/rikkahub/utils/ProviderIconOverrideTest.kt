package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 提供商图标覆盖表编解码与渲染优先级（纯函数）。
 */
class ProviderIconOverrideTest {

    private val icons = setOf(
        "openai.svg", "deepseek-color.svg", "mazal.svg",
        // 模型优先级单测用到的品牌文件（与真实 assets/icons/ 同名）
        "hunyuan-color.svg", "amdradeoncloud-color.svg", "moonshot.svg",
    )

    private fun resolve(name: String, override: String?) =
        resolveProviderIcon(name, override, icons)

    private fun resolveModel(
        modelId: String,
        override: String?,
        providerName: String? = null,
        baseUrl: String? = null,
    ) = resolveModelIcon(modelId, override, icons, providerName, baseUrl)

    @Test
    fun `encode and parse round trip`() {
        assertEquals(
            ProviderIconSource.Asset("openrouter.svg"),
            ProviderIconOverrides.parse(ProviderIconOverrides.encodeAsset("openrouter.svg")),
        )
        assertEquals(
            ProviderIconSource.LocalFile("provider-icons/mazal.svg"),
            ProviderIconOverrides.parse(ProviderIconOverrides.encodeFile("provider-icons/mazal.svg")),
        )
    }

    @Test
    fun `parse rejects malformed values`() {
        assertNull(ProviderIconOverrides.parse(null))
        assertNull(ProviderIconOverrides.parse(""))
        assertNull(ProviderIconOverrides.parse("openrouter.svg"))
        assertNull(ProviderIconOverrides.parse("asset:"))
        assertNull(ProviderIconOverrides.parse("asset:a/b.svg"))
        assertNull(ProviderIconOverrides.parse("file:"))
        assertNull(ProviderIconOverrides.parse("file:/abs/path.svg"))
        assertNull(ProviderIconOverrides.parse("file:../escape.svg"))
    }

    @Test
    fun `override wins over brand match`() {
        val resolved = resolve("OpenAI", ProviderIconOverrides.encodeAsset("mazal.svg"))
        assertEquals(ProviderIconSource.Asset("mazal.svg"), resolved)
    }

    @Test
    fun `local file override wins over brand match`() {
        val resolved = resolve("OpenAI", ProviderIconOverrides.encodeFile("provider-icons/mazal.svg"))
        assertEquals(ProviderIconSource.LocalFile("provider-icons/mazal.svg"), resolved)
    }

    @Test
    fun `malformed override falls back to brand match`() {
        assertEquals(
            ProviderIconSource.Asset("openai.svg"),
            resolve("OpenAI", "not-a-prefixed-value"),
        )
    }

    @Test
    fun `no override falls back to brand match`() {
        assertEquals(
            ProviderIconSource.Asset("deepseek-color.svg"),
            resolve("DeepSeek", null),
        )
    }

    @Test
    fun `unknown name without override resolves to null`() {
        assertNull(resolve("some-provider-that-does-not-exist", null))
    }

    @Test
    fun `model override wins over model brand match`() {
        // 覆盖表（所属提供商的那一项）优先于模型名本身的品牌
        assertEquals(
            ProviderIconSource.Asset("mazal.svg"),
            resolveModel("gpt-6-luna", ProviderIconOverrides.encodeAsset("mazal.svg")),
        )
        assertEquals(
            ProviderIconSource.LocalFile("provider-icons/mazal.svg"),
            resolveModel(
                "kimi-k3",
                ProviderIconOverrides.encodeFile("provider-icons/mazal.svg"),
            ),
        )
    }

    @Test
    fun `model brand match wins over provider fallback`() {
        // 模型名能解析到品牌时，不用提供商的标
        assertEquals(
            ProviderIconSource.Asset("hunyuan-color.svg"),
            resolveModel("hy4", null, "Tencent Hunyuan", "https://tokenhub.tencentmaas.com/v1"),
        )
        assertEquals(
            ProviderIconSource.Asset("moonshot.svg"),
            resolveModel("kimi-k3", null, "Moonshot", "https://api.moonshot.ai/v1"),
        )
    }

    @Test
    fun `model falls back to provider brand when name has none`() {
        // 模型名解析不到时用所属提供商的标兜底（端点优先）
        assertEquals(
            ProviderIconSource.Asset("amdradeoncloud-color.svg"),
            resolveModel(
                "unknown-model-xyz",
                null,
                "AMD",
                "https://developer.amd.com.cn/radeon/api/v1",
            ),
        )
    }

    @Test
    fun `model with no brand and no provider resolves to null`() {
        assertNull(resolveModel("unknown-model-xyz", null, null))
        assertNull(resolveModel("unknown-model-xyz", null, "some-provider-that-does-not-exist"))
    }

    @Test
    fun `malformed model override falls back to model brand match`() {
        assertEquals(
            ProviderIconSource.Asset("openai.svg"),
            resolveModel("gpt-6-luna", "not-a-prefixed-value", null),
        )
    }
}
