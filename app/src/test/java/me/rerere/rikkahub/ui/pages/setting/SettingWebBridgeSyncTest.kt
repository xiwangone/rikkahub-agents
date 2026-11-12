package me.rerere.rikkahub.ui.pages.setting

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web 桥字段同步（[syncWebBridgeFields]/[syncToBackendProviders]）：全局字段
 * 只同步进各后端供应商自己的副本。以前只在拨动开关时同步，改完主机/端口
 * 直接重启 App，后端用的可能是旧地址。
 */
class SettingWebBridgeSyncTest {

    private fun settingsWithBackends(): Settings {
        val staleBackend =
            ProviderSetting.Backend(
                webBridgeEnabled = true,
                webBridgeEcsHost = "old.example.com",
                webBridgeEcsPort = 2222,
                webBridgeEcsUser = "old",
                webBridgeRemotePort = 9000,
                webBridgeLocalPort = 9001,
                webBridgePrivateKeyPath = "/old/key",
                webBridgePassword = "old-pass",
            )
        return Settings(
            webBridgeEcsHost = "ecs.example.com",
            webBridgeEcsUser = "root",
            webBridgeEcsPort = 22,
            webBridgeRemotePort = 8080,
            webBridgeLocalPort = 8080,
            webBridgePrivateKeyPath = "/home/key",
            webBridgePassword = "secret",
            providers = listOf(
                staleBackend,
                ProviderSetting.OpenAI(name = "Work"),
            ),
        )
    }

    private fun backendOf(settings: Settings): ProviderSetting.Backend =
        settings.providers.filterIsInstance<ProviderSetting.Backend>().single()

    @Test
    fun `sync copies global fields into backend copy`() {
        val synced = syncWebBridgeFields(settingsWithBackends())
        val backend = backendOf(synced)
        assertEquals("ecs.example.com", backend.webBridgeEcsHost)
        assertEquals("root", backend.webBridgeEcsUser)
        assertEquals(22, backend.webBridgeEcsPort)
        assertEquals(8080, backend.webBridgeRemotePort)
        assertEquals(8080, backend.webBridgeLocalPort)
        assertEquals("/home/key", backend.webBridgePrivateKeyPath)
        assertEquals("secret", backend.webBridgePassword)
    }

    @Test
    fun `sync keeps each backend enable flag`() {
        val synced = syncWebBridgeFields(settingsWithBackends())
        assertTrue(backendOf(synced).webBridgeEnabled)
    }

    @Test
    fun `sync leaves non-backend providers untouched`() {
        val before = settingsWithBackends()
        val openaiBefore = before.providers.filterIsInstance<ProviderSetting.OpenAI>().single()
        val synced = syncWebBridgeFields(before)
        assertEquals(openaiBefore, synced.providers.filterIsInstance<ProviderSetting.OpenAI>().single())
    }

    @Test
    fun `toggle sync sets the flag on all backends`() {
        val enabled = syncToBackendProviders(settingsWithBackends(), true)
        assertTrue(backendOf(enabled).webBridgeEnabled)
        assertEquals("ecs.example.com", backendOf(enabled).webBridgeEcsHost)

        val disabled = syncToBackendProviders(settingsWithBackends(), false)
        assertFalse(backendOf(disabled).webBridgeEnabled)
        assertEquals("ecs.example.com", backendOf(disabled).webBridgeEcsHost)
    }

    @Test
    fun `sync with no backends is a no-op`() {
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI()))
        assertEquals(settings, syncWebBridgeFields(settings))
        assertEquals(settings, syncToBackendProviders(settings, true))
    }
}
