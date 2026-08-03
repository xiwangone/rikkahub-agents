package me.rerere.rikkahub.ui.pages.setting.locallm

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.locallm.LocalRuntime
import me.rerere.locallm.litert.LiteRtModelMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the runtime-aware `deriveLocalModelCapabilities` fix: before this, every call site
 * routed unconditionally through `LiteRtModelMetadata.deriveCapabilities`, which would stamp
 * a GGUF with LiteRT-derived capabilities (and could grant IMAGE input, which llama.cpp never
 * supports in this build).
 */
class SettingLocalLlmCapabilitiesTest {

    @Test
    fun `llama cpp catalog file gets TOOL and REASONING from its tags`() {
        val caps = deriveLocalModelCapabilities(LocalRuntime.LlamaCpp, "Qwen3-4B-Q4_K_M.gguf")

        assertEquals(listOf(Modality.TEXT), caps.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), caps.abilities)
    }

    @Test
    fun `llama cpp file outside the catalog still gets TOOL, never REASONING`() {
        val caps = deriveLocalModelCapabilities(LocalRuntime.LlamaCpp, "some-manual-model.gguf")

        assertEquals(listOf(Modality.TEXT), caps.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL), caps.abilities)
    }

    @Test
    fun `llama cpp never derives IMAGE input, unlike LiteRT`() {
        val caps = deriveLocalModelCapabilities(LocalRuntime.LlamaCpp, "Qwen3-0.6B-Q8_0.gguf")

        assertFalse(Modality.IMAGE in caps.inputModalities)
    }

    @Test
    fun `LiteRT branch is unchanged, still routes through LiteRtModelMetadata`() {
        val direct = LiteRtModelMetadata.deriveCapabilities("some-litert-file.litertlm")
        val viaHelper = deriveLocalModelCapabilities(LocalRuntime.LiteRT, "some-litert-file.litertlm")

        assertEquals(direct, viaHelper)
    }

    @Test
    fun `enableAfterFirstDownload enables a llama cpp provider, not just LiteRT`() {
        val enabled = enableAfterFirstDownload(ProviderSetting.LlamaCppLocal(enabled = false))

        assertTrue((enabled as ProviderSetting.LlamaCppLocal).enabled)
    }

    @Test
    fun `enableAfterFirstDownload still enables a LiteRT provider`() {
        val enabled = enableAfterFirstDownload(ProviderSetting.LiteRtLocal(enabled = false))

        assertTrue((enabled as ProviderSetting.LiteRtLocal).enabled)
    }

    @Test
    fun `enableAfterFirstDownload leaves an unrelated provider untouched`() {
        val original = ProviderSetting.OpenAI(enabled = false)

        assertEquals(original, enableAfterFirstDownload(original))
    }
}
