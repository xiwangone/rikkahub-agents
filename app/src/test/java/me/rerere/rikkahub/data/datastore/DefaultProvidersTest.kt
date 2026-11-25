package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers should include local litert disabled and built in`() {
        val localProviders = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.LiteRtLocal>()

        assertEquals(1, localProviders.size)

        val provider = localProviders.single()
        assertEquals("Local · LiteRT", provider.name)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
    }

    @Test
    fun `default providers should include local llamacpp disabled and built in right after litert`() {
        val localProviders = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.LlamaCppLocal>()

        assertEquals(1, localProviders.size)

        val provider = localProviders.single()
        assertEquals("Local · llama.cpp", provider.name)
        assertEquals("local_llamacpp", provider.providerKey)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)

        // On-device providers stay grouped at the top in DEFAULT_PROVIDERS order:
        // AICore, then LiteRT, then llama.cpp.
        val litertIndex = DEFAULT_PROVIDERS.indexOfFirst { it is ProviderSetting.LiteRtLocal }
        val llamacppIndex = DEFAULT_PROVIDERS.indexOfFirst { it is ProviderSetting.LlamaCppLocal }
        assertTrue(litertIndex >= 0)
        assertEquals(litertIndex + 1, llamacppIndex)
    }

    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/credits", provider.balanceOption.apiPath)
        assertEquals("balance", provider.balanceOption.resultPath)
    }
}
