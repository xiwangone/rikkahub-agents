package me.rerere.rikkahub.data.vault

import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class VaultRuntimeConfigTest {
    private val syntheticVault = mapOf("SYNTHETIC_PROVIDER_KEY" to "synthetic-resolved-key")
    private val resolve: (String) -> String? = { raw ->
        if (raw.startsWith("\$\$")) syntheticVault[raw.removePrefix("\$\$")] else raw
    }

    @Test
    fun mediaProviderResolvesOnlyRuntimeCopyAndKeepsLegacyLiteral() {
        val configured = MediaGenerationProviderSetting.OpenAI(
            name = "Synthetic media provider",
            apiKey = "\$\$SYNTHETIC_PROVIDER_KEY",
        )
        val runtime = configured.withResolvedVaultCredentials(resolve)

        assertEquals("\$\$SYNTHETIC_PROVIDER_KEY", configured.apiKey)
        assertEquals("synthetic-resolved-key", runtime.apiKey)
        assertEquals(configured.name, runtime.name)
        assertNotSame(configured, runtime)
        assertEquals("legacy-synthetic-key", resolveVaultCredentialValue("legacy-synthetic-key", resolve))
    }

    @Test
    fun searchApiKeysAndSearxPasswordResolveWithoutChangingOtherOptions() {
        val tavily = SearchServiceOptions.TavilyOptions(apiKey = "\$\$SYNTHETIC_PROVIDER_KEY", depth = "basic")
        val runtimeTavily = tavily.withResolvedVaultCredentials(resolve) as SearchServiceOptions.TavilyOptions
        assertEquals("\$\$SYNTHETIC_PROVIDER_KEY", tavily.apiKey)
        assertEquals("synthetic-resolved-key", runtimeTavily.apiKey)
        assertEquals("basic", runtimeTavily.depth)

        val searx = SearchServiceOptions.SearXNGOptions(
            url = "https://example.invalid",
            username = "synthetic-user",
            password = "\$\$SYNTHETIC_PROVIDER_KEY",
        )
        val runtimeSearx = searx.withResolvedVaultCredentials(resolve) as SearchServiceOptions.SearXNGOptions
        assertEquals("synthetic-resolved-key", runtimeSearx.password)
        assertEquals("synthetic-user", runtimeSearx.username)
        assertEquals(searx.url, runtimeSearx.url)
    }

    @Test
    fun asrAndTtsResolveReferencesAndKeepMissingReferencesOutOfRequests() {
        val asr = ASRProviderSetting.Step(apiKey = "\$\$SYNTHETIC_PROVIDER_KEY", model = "synthetic-model")
        val runtimeAsr = asr.withResolvedVaultCredentials(resolve) as ASRProviderSetting.Step
        assertEquals("synthetic-resolved-key", runtimeAsr.apiKey)
        assertEquals("\$\$SYNTHETIC_PROVIDER_KEY", asr.apiKey)
        assertEquals("synthetic-model", runtimeAsr.model)

        val tts = TTSProviderSetting.OpenAI(apiKey = "\$\$SYNTHETIC_PROVIDER_KEY", model = "synthetic-model")
        val runtimeTts = tts.withResolvedVaultCredentials(resolve) as TTSProviderSetting.OpenAI
        assertEquals("synthetic-resolved-key", runtimeTts.apiKey)
        assertEquals("\$\$SYNTHETIC_PROVIDER_KEY", tts.apiKey)
        assertEquals("synthetic-model", runtimeTts.model)

        val dangling = tts.copy(apiKey = "\$\$MISSING_SYNTHETIC_KEY").withResolvedVaultCredentials(resolve)
        assertEquals("", (dangling as TTSProviderSetting.OpenAI).apiKey)
    }

    @Test
    fun `TTS resolver reads the latest vault value on each synthesis pass`() {
        val configured = TTSProviderSetting.OpenAI(apiKey = "\$\$SYNTHETIC_PROVIDER_KEY")
        var currentSyntheticValue = "synthetic-key-before-rotation"
        val currentResolver: (String) -> String? = { raw ->
            if (raw == "\$\$SYNTHETIC_PROVIDER_KEY") currentSyntheticValue else raw
        }

        val firstRequest = configured.withResolvedVaultCredentials(currentResolver) as TTSProviderSetting.OpenAI
        currentSyntheticValue = "synthetic-key-after-rotation"
        val nextRequest = configured.withResolvedVaultCredentials(currentResolver) as TTSProviderSetting.OpenAI

        assertEquals("synthetic-key-before-rotation", firstRequest.apiKey)
        assertEquals("synthetic-key-after-rotation", nextRequest.apiKey)
        assertEquals("\$\$SYNTHETIC_PROVIDER_KEY", configured.apiKey)
    }
}
