package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 提供商图标覆盖表编解码与渲染优先级（纯函数）。
 */
class ProviderIconOverrideTest {

    private val icons = setOf("openai.svg", "deepseek-color.svg", "mazal.svg")

    private fun resolve(name: String, override: String?) =
        resolveProviderIcon(name, override, icons)

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
}
