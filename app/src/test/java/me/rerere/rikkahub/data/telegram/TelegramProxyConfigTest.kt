package me.rerere.rikkahub.data.telegram

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [readTelegramProxyConfig] / [writeTelegramProxyConfig] — the pure
 * key<->[TelegramBotConfig] mapping TelegramBotPreferences delegates to for its six proxy
 * fields. Exercised against a real file-backed DataStore<Preferences> (constructed directly
 * via [PreferenceDataStoreFactory], no Android Context involved) because
 * TelegramBotPreferences itself needs a Context-backed DataStore to construct, and this
 * project has no Robolectric/Mockito seam to fake one.
 */
class TelegramProxyConfigTest {

    private fun newStore(dir: File, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(dir, "telegram_proxy_test.preferences_pb") },
        )

    private fun withTempStore(block: suspend (DataStore<Preferences>) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("telegram-proxy-test").toFile()
        // 显式 scope：DataStore 的文件句柄随 scope 生命周期释放。默认 scope 永不取消，
        // 会让 .tmp 文件一直被占用，下一次写入的重命名在部分平台（如 Windows）失败。
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            block(newStore(dir, scope))
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a proxy config round-trips through all six fields`() = withTempStore { store ->
        val cfg = TelegramBotConfig(
            proxyEnabled = true,
            proxyType = "HTTP",
            proxyHost = "proxy.example.com",
            proxyPort = 8080,
            proxyUsername = "synthetic-proxy-user",
            proxyPassword = "synthetic-proxy-password",
        )

        store.edit { p -> writeTelegramProxyConfig(p, cfg) }
        val read = readTelegramProxyConfig(store.data.first())

        assertEquals(cfg.proxyEnabled, read.proxyEnabled)
        assertEquals(cfg.proxyType, read.proxyType)
        assertEquals(cfg.proxyHost, read.proxyHost)
        assertEquals(cfg.proxyPort, read.proxyPort)
        assertEquals(cfg.proxyUsername, read.proxyUsername)
        assertEquals(cfg.proxyPassword, read.proxyPassword)
    }

    @Test
    fun `a missing proxy key reads as the TelegramBotConfig default`() = withTempStore { store ->
        val defaults = TelegramBotConfig()

        val read = readTelegramProxyConfig(store.data.first())

        assertEquals(defaults.proxyEnabled, read.proxyEnabled)
        assertEquals(defaults.proxyType, read.proxyType)
        assertEquals(defaults.proxyHost, read.proxyHost)
        assertEquals(defaults.proxyPort, read.proxyPort)
        assertEquals(defaults.proxyUsername, read.proxyUsername)
        assertEquals(defaults.proxyPassword, read.proxyPassword)
    }

    @Test
    fun `disabled proxy round-trips false without dragging in unrelated defaults`() = withTempStore { store ->
        val cfg = TelegramBotConfig(proxyEnabled = false, proxyType = "SOCKS5")

        store.edit { p -> writeTelegramProxyConfig(p, cfg) }
        val read = readTelegramProxyConfig(store.data.first())

        assertEquals(false, read.proxyEnabled)
        assertEquals("SOCKS5", read.proxyType)
    }

    @Test
    fun `proxy password storage applies injected protection and preserves legacy literals`() = withTempStore { store ->
        val cfg = TelegramBotConfig(proxyPassword = "synthetic-proxy-password")
        store.edit { p -> writeTelegramProxyConfig(p, cfg) { "synthetic-cipher:$it" } }

        val preferences = store.data.first()
        val stored = preferences.asMap().entries.single { it.key.name == "proxy_password" }.value as String
        assertEquals("synthetic-cipher:synthetic-proxy-password", stored)
        val decoded = readTelegramProxyConfig(preferences) { it.removePrefix("synthetic-cipher:") }
        assertEquals("synthetic-proxy-password", decoded.proxyPassword)

        store.edit { p -> writeTelegramProxyConfig(p, cfg) }
        assertEquals("synthetic-proxy-password", readTelegramProxyConfig(store.data.first()).proxyPassword)
    }

    @Test
    fun `bot token protects reference text and falls back to legacy plaintext`() {
        val reference = "\$\$SYNTHETIC_TELEGRAM_TOKEN"
        val encoded = encryptTelegramCredential(reference) { "synthetic-cipher:$it" }
        assertEquals("synthetic-cipher:\$\$SYNTHETIC_TELEGRAM_TOKEN", encoded)
        assertEquals(reference, decryptTelegramCredential(encoded) { it.removePrefix("synthetic-cipher:") })
        assertEquals("legacy-synthetic-token", decryptTelegramCredential("legacy-synthetic-token") { null })
    }
}
