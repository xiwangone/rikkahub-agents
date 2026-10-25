package me.rerere.rikkahub.data.datastore

import android.app.Application
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.pebbletemplates.pebble.PebbleEngine
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.util.Base64
import java.util.Collections
import java.util.Date
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.search.SearchServiceOptions
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

/** Runs the production cipher and SettingsStore with synthetic preferences only. No Room or device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class MediaGenerationSettingsPersistenceTest {
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeScope: AppScope? = null
    private var activeDataStoreScope: CoroutineScope? = null
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settingsFile: File
    private val sentinelKey = stringPreferencesKey("synthetic_unrelated_setting")
    private val assistantId = Uuid.parse("11111111-2222-4333-8444-555555555555")
    private val modelId = Uuid.parse("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
    private val assistant = Assistant(id = assistantId, name = "Synthetic preserved assistant")
    private val media = listOf<MediaGenerationProviderSetting>(
        MediaGenerationProviderSetting.OpenAI(
            id = Uuid.parse("12345678-1234-4234-8234-123456789abc"),
            name = "Synthetic provider", apiKey = "synthetic-not-a-real-credential",
        ),
    )

    /** Pump Android Main while real DataStore/cipher work proceeds on IO. No coroutine implementation is mocked. */
    private fun <T> perform(block: suspend () -> T): Result<T> {
        val task = workers.async { block() }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        check(task.isCompleted) { "Synthetic settings operation timed out" }
        return runCatching { runBlocking { task.await() } }
    }

    private fun stopCollector() {
        activeScope?.let { scope -> perform { scope.coroutineContext[Job]!!.cancelAndJoin() }.getOrThrow() }
        activeScope = null
    }

    private fun stopDataStoreScope() {
        activeDataStoreScope?.let { scope -> perform { scope.coroutineContext[Job]!!.cancelAndJoin() }.getOrThrow() }
        activeDataStoreScope = null
    }

    private suspend fun seed(target: DataStore<Preferences>, raw: String?) {
        target.edit { values ->
            values.clear()
            values[SettingsStore.VERSION] = 3 // All registered migrations are already applied.
            values[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(listOf(assistant))
            values[SettingsStore.SELECT_ASSISTANT] = assistantId.toString()
            values[SettingsStore.SELECT_MODEL] = modelId.toString()
            values[SettingsStore.TITLE_PROMPT] = "Synthetic original title prompt"
            values[SettingsStore.DYNAMIC_COLOR] = false
            values[SettingsStore.LAUNCH_COUNT] = 27
            values[sentinelKey] = "synthetic-untouched-sentinel"
            if (raw != null) values[SettingsStore.MEDIA_GENERATION_PROVIDERS] = raw
        }
    }

    private fun open(): SettingsStore {
        val scope = AppScope()
        activeScope = scope
        return SettingsStore(RuntimeEnvironment.getApplication(), scope, dataStore)
    }

    private fun assertOtherSettings(settings: Settings) {
        assertFalse(settings.init)
        assertTrue("custom assistant survives", settings.assistants.any { it.id == assistantId && it.name == assistant.name })
        assertEquals(assistantId, settings.assistantId)
        assertEquals(modelId, settings.chatModelId)
        assertEquals("Synthetic original title prompt", settings.titlePrompt)
        assertFalse(settings.dynamicColor)
        assertEquals(27, settings.launchCount)
    }

    @Test
    fun realSettingsFlowPreservesCiphertextAndRejectsDestructiveDefaultFallback() {
        // Only AndroidKeyStore's external key source is synthetic. Production AES-GCM and Base64 code runs unchanged.
        val provider = SyntheticSettingsSecurityProvider()
        Security.insertProviderAt(provider, 1)
        SyntheticSettingsKeyStore.key = newKey()
        SyntheticSettingsKeyStore.unavailable = false
        stopKoin()
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        var passed = 0
        try {
            val json = JsonInstant.encodeToString(media)
            val encrypted = ProviderCredentialCipher.encrypt(json)
            assertTrue("production cipher round trip", ProviderCredentialCipher.decrypt(encrypted) == json)
            val context: Application = RuntimeEnvironment.getApplication()
            settingsFile = File(context.filesDir, "datastore/settings.preferences_pb")
            val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            activeDataStoreScope = seedScope
            val initial = PreferenceDataStoreFactory.create(scope = seedScope, produceFile = { settingsFile })
            dataStore = initial
            perform { seed(initial, encrypted) }.getOrThrow()

            val firstBytes = settingsFile.readBytes()
            var store = open()
            val initialized = perform { store.awaitInitialized() }
            initialized.exceptionOrNull()?.let { println("SYNTHETIC_INITIALIZATION_FAILURE_TYPE=" + it.javaClass.name) }
            assertTrue("encrypted fixture must initialize using the real SettingsStore", initialized.isSuccess)
            assertOtherSettings(store.settingsFlow.value)
            assertEquals(media, store.settingsFlow.value.mediaGenerationProviders)
            assertTrue("initialization must not write raw preferences", firstBytes.contentEquals(settingsFile.readBytes()))
            assertTrue("reading must retain exact ciphertext", perform { dataStore.data.first()[SettingsStore.MEDIA_GENERATION_PROVIDERS] }.getOrThrow() == encrypted)
            passed++
            println("PASS synthetic case: encrypted list initializes; raw file and unrelated settings unchanged")

            // Normal explicit update may rotate the GCM IV, but must retain all decoded credentials and assistants.
            perform { store.update { it.copy(titlePrompt = "Synthetic updated title prompt") } }.getOrThrow()
            val afterUpdate = perform { dataStore.data.first() }.getOrThrow()
            val rewritten = afterUpdate[SettingsStore.MEDIA_GENERATION_PROVIDERS]!!
            assertTrue("normal writer keeps media field encrypted", rewritten != json)
            assertTrue("normal writer preserves decoded media credentials", ProviderCredentialCipher.decrypt(rewritten) == json)
            assertTrue("unknown setting retained", afterUpdate[sentinelKey] == "synthetic-untouched-sentinel")
            assertEquals(media, store.settingsFlow.value.mediaGenerationProviders)
            assertTrue(store.settingsFlow.value.assistants.any { it.id == assistantId })
            stopCollector()

            val goodCases = listOf(
                Triple("legacy plaintext list", json, media),
                Triple("absent field", null, emptyList()),
                Triple("encrypted empty list", ProviderCredentialCipher.encrypt("[]"), emptyList()),
            )
            for ((label, raw, expected) in goodCases) {
                perform { seed(dataStore, raw) }.getOrThrow()
                val before = settingsFile.readBytes()
                store = open()
                assertTrue("$label initializes", perform { store.awaitInitialized() }.isSuccess)
                assertOtherSettings(store.settingsFlow.value)
                assertEquals(expected, store.settingsFlow.value.mediaGenerationProviders)
                assertTrue("$label does not rewrite storage", before.contentEquals(settingsFile.readBytes()))
                val values = perform { dataStore.data.first() }.getOrThrow()
                assertTrue("$label preserves original key presence/value", values[SettingsStore.MEDIA_GENERATION_PROVIDERS] == raw)
                stopCollector()
                passed++
                println("PASS synthetic case: $label; no read-time persistence")
            }

            val originalKey = SyntheticSettingsKeyStore.key
            val tampered = Base64.getDecoder().decode(encrypted).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            val badCases = listOf(
                Triple("malformed string", "synthetic-not-json-or-valid-base64!", "normal"),
                Triple("present empty string", "", "normal"),
                Triple("GCM authentication failure", Base64.getEncoder().encodeToString(tampered), "normal"),
                Triple("decrypted wrong JSON schema", ProviderCredentialCipher.encrypt("{\"synthetic\":true}"), "normal"),
                Triple("wrong key", encrypted, "wrong"),
                Triple("unavailable key", encrypted, "unavailable"),
            )
            for ((label, raw, keyMode) in badCases) {
                SyntheticSettingsKeyStore.key = if (keyMode == "wrong") newKey() else originalKey
                SyntheticSettingsKeyStore.unavailable = keyMode == "unavailable"
                perform { seed(dataStore, raw) }.getOrThrow()
                val beforeBytes = settingsFile.readBytes()
                val beforeMap = perform { dataStore.data.first().asMap() }.getOrThrow()
                store = open()
                assertTrue("$label must fail initialization", perform { store.awaitInitialized() }.isFailure)
                assertTrue("$label cannot masquerade as loaded defaults", store.settingsFlow.value.init)
                var transformed = false
                val update = perform { store.update { transformed = true; it.copy(mediaGenerationProviders = emptyList()) } }
                assertTrue("$label must reject update", update.isFailure)
                assertFalse("$label must fail before calling update transform", transformed)
                assertTrue("$label must reject whole-settings replacement", perform { store.replaceSettings(Settings()) }.isFailure)
                val afterMap = perform { dataStore.data.first().asMap() }.getOrThrow()
                assertTrue("$label preserves every raw setting", beforeMap == afterMap)
                assertTrue("$label preserves exact preference file bytes", beforeBytes.contentEquals(settingsFile.readBytes()))
                assertTrue("$label preserves original media value", afterMap[SettingsStore.MEDIA_GENERATION_PROVIDERS] == raw)
                stopCollector()
                passed++
                println("PASS synthetic case: $label; initialization/update/replace blocked; all original bytes retained")
            }
            assertEquals(10, passed)
            println("SYNTHETIC_SETTINGS_CASES_PASSED=$passed")
            println("SCOPE: actual SettingsStore and production AES-GCM; synthetic JVM key source/preferences; no Room, no APK startup or real Android Keystore claim")
        } finally {
            stopCollector()
            stopDataStoreScope()
            stopKoin()
            workers.cancel()
            SyntheticSettingsKeyStore.key = null
            SyntheticSettingsKeyStore.unavailable = false
            Security.removeProvider(provider.name)
        }
    }

    @Test
    fun searchProxyAndWebSecretsMigrateFromPlaintextOnNormalWrite() {
        val provider = SyntheticSettingsSecurityProvider()
        Security.insertProviderAt(provider, 1)
        SyntheticSettingsKeyStore.key = newKey()
        SyntheticSettingsKeyStore.unavailable = false
        stopKoin()
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        try {
            val telegram = TelegramBotPreferences(RuntimeEnvironment.getApplication())
            val syntheticTokenRef = "\$\$SYNTHETIC_TELEGRAM_TOKEN"
            val syntheticProxyRef = "\$\$SYNTHETIC_TELEGRAM_PROXY_PASSWORD"
            perform {
                telegram.update { it.copy(token = syntheticTokenRef, proxyPassword = syntheticProxyRef) }
            }.getOrThrow()
            @Suppress("UNCHECKED_CAST")
            val telegramStore = TelegramBotPreferences::class.java.getDeclaredField("store")
                .apply { isAccessible = true }.get(telegram) as DataStore<Preferences>
            val telegramRaw = perform { telegramStore.data.first().asMap() }.getOrThrow()
            val storedToken = telegramRaw.entries.single { it.key.name == "token" }.value as String
            val storedProxyPassword = telegramRaw.entries.single { it.key.name == "proxy_password" }.value as String
            assertFalse(storedToken == syntheticTokenRef)
            assertFalse(storedProxyPassword == syntheticProxyRef)
            assertEquals(syntheticTokenRef, ProviderCredentialCipher.decrypt(storedToken))
            assertEquals(syntheticProxyRef, ProviderCredentialCipher.decrypt(storedProxyPassword))
            assertEquals(syntheticTokenRef, perform { telegram.current() }.getOrThrow().token)
            assertEquals(syntheticProxyRef, perform { telegram.current() }.getOrThrow().proxyPassword)

            val services: List<SearchServiceOptions> = listOf(
                SearchServiceOptions.TavilyOptions(apiKey = "synthetic-search-api-key", depth = "basic"),
            )
            val network = NetworkSetting(
                proxyUrl = "http://proxy.example.invalid:8080",
                proxyUsername = "synthetic-proxy-user",
                proxyPassword = "synthetic-proxy-password",
            )
            val searchJson = JsonInstant.encodeToString(services)
            val networkJson = JsonInstant.encodeToString(network)
            val context: Application = RuntimeEnvironment.getApplication()
            settingsFile = File(context.filesDir, "datastore/settings.preferences_pb")
            val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            activeDataStoreScope = seedScope
            val initial = PreferenceDataStoreFactory.create(scope = seedScope, produceFile = { settingsFile })
            dataStore = initial
            perform { seed(initial, null) }.getOrThrow()
            perform {
                initial.edit { values ->
                    values[SettingsStore.SEARCH_SERVICES] = searchJson
                    values[SettingsStore.NETWORK_SETTING] = networkJson
                    values[SettingsStore.WEB_BRIDGE_PASSWORD] = "legacy-synthetic-web-bridge-password"
                    values[SettingsStore.WEB_SERVER_ACCESS_PASSWORD] = "legacy-synthetic-web-server-password"
                }
            }.getOrThrow()

            val beforeRead = settingsFile.readBytes()
            val store = open()
            assertTrue("legacy plaintext settings initialize", perform { store.awaitInitialized() }.isSuccess)
            assertEquals(services, store.settingsFlow.value.searchServices)
            assertEquals(network, store.settingsFlow.value.networkSetting)
            assertEquals("legacy-synthetic-web-bridge-password", store.settingsFlow.value.webBridgePassword)
            assertEquals("legacy-synthetic-web-server-password", store.settingsFlow.value.webServerAccessPassword)
            assertTrue("reading legacy values is not a migration write", beforeRead.contentEquals(settingsFile.readBytes()))
            val legacyValues = perform { dataStore.data.first() }.getOrThrow()
            assertEquals(searchJson, legacyValues[SettingsStore.SEARCH_SERVICES])
            assertEquals(networkJson, legacyValues[SettingsStore.NETWORK_SETTING])

            perform { store.update { it.copy(titlePrompt = "Synthetic ordinary settings update") } }.getOrThrow()
            val encryptedValues = perform { dataStore.data.first() }.getOrThrow()
            assertEquals(searchJson, ProviderCredentialCipher.decrypt(encryptedValues[SettingsStore.SEARCH_SERVICES]!!))
            assertEquals(networkJson, ProviderCredentialCipher.decrypt(encryptedValues[SettingsStore.NETWORK_SETTING]!!))
            assertEquals(
                "legacy-synthetic-web-bridge-password",
                ProviderCredentialCipher.decrypt(encryptedValues[SettingsStore.WEB_BRIDGE_PASSWORD]!!),
            )
            assertEquals(
                "legacy-synthetic-web-server-password",
                ProviderCredentialCipher.decrypt(encryptedValues[SettingsStore.WEB_SERVER_ACCESS_PASSWORD]!!),
            )
            assertEquals(services, store.settingsFlow.value.searchServices)
            assertEquals(network, store.settingsFlow.value.networkSetting)
            stopCollector()
            println("PASS synthetic case: legacy search/proxy/Web credentials read unchanged, then encrypt on ordinary write")
            println("SCOPE: production SettingsStore and AES-GCM; synthetic settings and key source; no real credentials/device key")
        } finally {
            stopCollector()
            stopDataStoreScope()
            stopKoin()
            workers.cancel()
            SyntheticSettingsKeyStore.key = null
            SyntheticSettingsKeyStore.unavailable = false
            Security.removeProvider(provider.name)
        }
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}

/** JCA provider used only by this host-side fixture; contains no real key or credential. */
class SyntheticSettingsSecurityProvider : Provider("SyntheticSettingsFixture", 1.0, "Synthetic test key source") {
    init { put("KeyStore.AndroidKeyStore", SyntheticSettingsKeyStore::class.java.name) }
}

class SyntheticSettingsKeyStore : KeyStoreSpi() {
    companion object { var key: SecretKey? = null; var unavailable: Boolean = false }
    override fun engineGetKey(alias: String?, password: CharArray?): Key? {
        if (unavailable) throw UnrecoverableKeyException("Synthetic fixture key unavailable")
        return key
    }
    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String?): Certificate? = null
    override fun engineGetCreationDate(alias: String?): Date = Date(0)
    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<Certificate>?) = throw UnsupportedOperationException()
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<Certificate>?) = throw UnsupportedOperationException()
    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = throw UnsupportedOperationException()
    override fun engineDeleteEntry(alias: String?) = throw UnsupportedOperationException()
    override fun engineAliases(): java.util.Enumeration<String> = Collections.enumeration(listOf("synthetic"))
    override fun engineContainsAlias(alias: String?): Boolean = key != null
    override fun engineSize(): Int = if (key == null) 0 else 1
    override fun engineIsKeyEntry(alias: String?): Boolean = key != null
    override fun engineIsCertificateEntry(alias: String?): Boolean = false
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}
