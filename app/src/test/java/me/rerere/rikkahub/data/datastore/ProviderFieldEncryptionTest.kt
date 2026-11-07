package me.rerere.rikkahub.data.datastore

import android.app.Application
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.pebbletemplates.pebble.PebbleEngine
import java.io.File
import java.security.Security
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.degradedSecretFields
import me.rerere.ai.provider.isCredentialDegraded
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.toHeaders
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
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

/**
 * Verifies the 5 required acceptance criteria for `PROVIDERS` field-level encryption:
 * 1. Disk evidence: provider name / baseUrl / models are plaintext on disk; apiKey / privateKey /
 *    password / token / customHeaders[].value are `enc:v1:` ciphertext and absent in plaintext.
 * 2. Legacy whole-blob ciphertext migration (including leading BOM) & migration idempotency.
 * 3. Mixed format (plaintext + `enc:v1:` ciphertext + `$$vault_ref`) round-trip.
 * 4. Bad `enc:v1:` ciphertext protection: startup does not crash, field is marked `degraded`,
 *    original ciphertext on disk is preserved character-for-character across subsequent saves
 *    (even if a caller attempts to overwrite with an empty string), and request path fails fast
 *    with a clear degradation error.
 * 5. Backup round-trip: export redacts secrets (`$$vault_ref` preserved), and new-install import
 *    restores 100% of non-sensitive provider/model configuration without locking on foreign keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class ProviderFieldEncryptionTest {
    private lateinit var workers: CoroutineScope
    private var activeScope: AppScope? = null
    private var activeDataStoreScope: CoroutineScope? = null
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settingsFile: File
    private lateinit var securityProvider: SyntheticSettingsSecurityProvider

    private val openAiId = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val googleId = Uuid.parse("22222222-2222-4222-8222-222222222222")
    private val backendId = Uuid.parse("33333333-3333-4333-8333-333333333333")
    private val modelId = Uuid.parse("44444444-4444-4444-8444-444444444444")

    private val openAiSecret = "synthetic-openai-secret-value-9001"
    private val googleApiKey = "synthetic-google-apikey-value-9002"
    private val googlePrivateKey = "-----BEGIN PRIVATE KEY-----\\nSYNTHETIC_VERTEX_KEY_9003\\n-----END PRIVATE KEY-----"
    private val backendPassword = "synthetic-backend-password-9004"
    private val backendToken = "\$\$VAULT_BACKEND_TOKEN_REF"
    private val headerSecret = "Bearer synthetic-custom-header-secret-9005"
    private val modelHeaderSecret = "synthetic-model-header-secret-9006"

    private val configuredProviders = listOf<ProviderSetting>(
        ProviderSetting.OpenAI(
            id = openAiId,
            name = "CustomOpenAIGateway",
            baseUrl = "https://gateway.example.invalid/v1",
            apiKey = openAiSecret,
            customHeaders = listOf(CustomHeader("X-Gateway-Auth", headerSecret)),
            models = listOf(
                Model(
                    id = modelId,
                    modelId = "gpt-4.1-custom",
                    displayName = "GPT-4.1 Custom",
                    customHeaders = listOf(CustomHeader("X-Model-Auth", modelHeaderSecret)),
                ),
            ),
        ),
        ProviderSetting.Google(
            id = googleId,
            name = "VertexServiceAccountProvider",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            vertexAI = true,
            useServiceAccount = true,
            apiKey = googleApiKey,
            privateKey = googlePrivateKey,
            projectId = "synthetic-gcp-project",
        ),
        ProviderSetting.Backend(
            id = backendId,
            name = "SelfHostedBackend",
            baseUrl = "https://backend.example.invalid",
            password = backendPassword,
            token = backendToken,
        ),
    )

    @Before
    fun setUp() {
        workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        securityProvider = SyntheticSettingsSecurityProvider()
        Security.insertProviderAt(securityProvider, 1)
        SyntheticSettingsKeyStore.key = newKey()
        SyntheticSettingsKeyStore.unavailable = false
        ProviderCredentialCipher.clearDegradedStateForTest()
        stopKoin()
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        val context: Application = RuntimeEnvironment.getApplication()
        settingsFile = File(context.filesDir, "datastore/provider_field_test_${System.nanoTime()}.preferences_pb")
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        activeDataStoreScope = seedScope
        dataStore = PreferenceDataStoreFactory.create(scope = seedScope, produceFile = { settingsFile })
    }

    @After
    fun tearDown() {
        stopCollector()
        stopDataStoreScope()
        stopKoin()
        workers.cancel()
        ProviderCredentialCipher.clearDegradedStateForTest()
        SyntheticSettingsKeyStore.key = null
        SyntheticSettingsKeyStore.unavailable = false
        Security.removeProvider(securityProvider.name)
        if (this::settingsFile.isInitialized && settingsFile.exists()) settingsFile.delete()
    }

    private fun <T> perform(block: suspend () -> T): Result<T> {
        val task = workers.async { block() }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        check(task.isCompleted) { "Provider field encryption test operation timed out" }
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

    private fun openStore(): SettingsStore {
        val scope = AppScope()
        activeScope = scope
        return SettingsStore(RuntimeEnvironment.getApplication(), scope, dataStore)
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `legacy whole-blob ciphertext migrates to field-level JSON with disk evidence and idempotency`() {
        val legacyPlainJson = JsonInstant.encodeToString(configuredProviders)
        val legacyWholeBlobCipher = "\uFEFF" + ProviderCredentialCipher.encrypt(legacyPlainJson)
        perform {
            dataStore.edit { prefs ->
                prefs.clear()
                prefs[SettingsStore.VERSION] = 5
                prefs[SettingsStore.PROVIDERS] = legacyWholeBlobCipher
            }
        }.getOrThrow()

        val store = openStore()
        assertTrue("legacy whole-blob initializes", perform { store.awaitInitialized() }.isSuccess)
        val loadedFromLegacy = store.settingsFlow.value.providers
        val loadedOpenAi = loadedFromLegacy.first { it.id == openAiId } as ProviderSetting.OpenAI
        val loadedGoogle = loadedFromLegacy.first { it.id == googleId } as ProviderSetting.Google
        val loadedBackend = loadedFromLegacy.first { it.id == backendId } as ProviderSetting.Backend
        assertEquals("https://gateway.example.invalid/v1", loadedOpenAi.baseUrl)
        assertEquals(openAiSecret, loadedOpenAi.apiKey)
        assertEquals(headerSecret, loadedOpenAi.customHeaders.single().value)
        assertEquals(modelHeaderSecret, loadedOpenAi.models.single().customHeaders.single().value)
        assertEquals(googleApiKey, loadedGoogle.apiKey)
        assertEquals(googlePrivateKey, loadedGoogle.privateKey)
        assertEquals(backendPassword, loadedBackend.password)
        assertEquals(backendToken, loadedBackend.token)

        perform { store.update { it.copy(titlePrompt = "Migrate providers to field-level v1") } }.getOrThrow()
        val migratedRaw = perform { dataStore.data.first()[SettingsStore.PROVIDERS] }.getOrThrow()!!
        val pbFileText = settingsFile.readBytes().decodeToString(throwOnInvalidSequence = false)

        assertTrue("outer format is JSON array", migratedRaw.trimStart().startsWith("["))
        assertTrue("provider name is plaintext on disk", migratedRaw.contains("CustomOpenAIGateway") && pbFileText.contains("CustomOpenAIGateway"))
        assertTrue("baseUrl is plaintext on disk", migratedRaw.contains("https://gateway.example.invalid/v1") && pbFileText.contains("https://gateway.example.invalid/v1"))
        assertTrue("modelId is plaintext on disk", migratedRaw.contains("gpt-4.1-custom"))
        assertTrue("Vault reference name remains plaintext", migratedRaw.contains(backendToken))
        for (secret in listOf(openAiSecret, googleApiKey, "SYNTHETIC_VERTEX_KEY_9003", backendPassword, headerSecret, modelHeaderSecret)) {
            assertFalse("secret '$secret' must NOT appear in PROVIDERS JSON", migratedRaw.contains(secret))
            assertFalse("secret '$secret' must NOT appear in settings.preferences_pb bytes", pbFileText.contains(secret))
        }
        val firstObj = (JsonInstant.parseToJsonElement(migratedRaw) as JsonArray).first().jsonObject
        assertTrue(firstObj["apiKey"]!!.jsonPrimitive.content.startsWith("enc:v1:"))

        perform { store.update { it.copy(titlePrompt = "Second unrelated update") } }.getOrThrow()
        val afterSecondUpdateRaw = perform { dataStore.data.first()[SettingsStore.PROVIDERS] }.getOrThrow()!!
        assertEquals("unchanged providers must remain byte-for-byte idempotent across updates", migratedRaw, afterSecondUpdateRaw)
    }

    @Test
    fun `mixed format with plaintext, enc_v1 ciphertext, and vault reference round-trips cleanly`() {
        val preEncryptedKey = "enc:v1:" + ProviderCredentialCipher.encrypt("synthetic-pre-encrypted-key")
        val mixedProviders = listOf<ProviderSetting>(
            ProviderSetting.OpenAI(id = openAiId, name = "MixedEnc", apiKey = preEncryptedKey),
            ProviderSetting.Claude(id = googleId, name = "MixedPlain", apiKey = "synthetic-legacy-plaintext-claude"),
            ProviderSetting.OpenAI(id = backendId, name = "MixedVault", apiKey = "\$\$MY_VAULT_KEY"),
        )
        perform {
            dataStore.edit { prefs ->
                prefs.clear()
                prefs[SettingsStore.VERSION] = 5
                prefs[SettingsStore.PROVIDERS] = JsonInstant.encodeToString(mixedProviders)
            }
        }.getOrThrow()

        val store = openStore()
        assertTrue("mixed format initializes", perform { store.awaitInitialized() }.isSuccess)
        val decodedMixed = store.settingsFlow.value.providers
        assertEquals("synthetic-pre-encrypted-key", (decodedMixed.first { it.id == openAiId } as ProviderSetting.OpenAI).apiKey)
        assertEquals("synthetic-legacy-plaintext-claude", (decodedMixed.first { it.id == googleId } as ProviderSetting.Claude).apiKey)
        assertEquals("\$\$MY_VAULT_KEY", (decodedMixed.first { it.id == backendId } as ProviderSetting.OpenAI).apiKey)

        perform { store.update { it.copy(titlePrompt = "Normalize mixed format") } }.getOrThrow()
        val normalizedMixedRaw = perform { dataStore.data.first()[SettingsStore.PROVIDERS] }.getOrThrow()!!
        assertFalse("legacy plaintext key upgraded to enc:v1:", normalizedMixedRaw.contains("synthetic-legacy-plaintext-claude"))
        assertTrue("pre-encrypted key preserved idempotently", normalizedMixedRaw.contains(preEncryptedKey))
        assertTrue("vault reference kept plaintext", normalizedMixedRaw.contains("\$\$MY_VAULT_KEY"))
    }

    @Test
    fun `bad enc_v1 ciphertext does not crash startup, marks provider degraded, and preserves original ciphertext on save`() {
        val badApiKeyCipher = "enc:v1:corrupted_unparseable_ciphertext_payload_do_not_overwrite"
        val badHeaderCipher = "enc:v1:corrupted_header_ciphertext_payload_do_not_overwrite"
        val validClaudeCipher = "enc:v1:" + ProviderCredentialCipher.encrypt("synthetic-valid-claude-key")
        val brokenProvider = ProviderSetting.OpenAI(
            id = openAiId,
            name = "DegradedOpenAI",
            baseUrl = "https://degraded.example.invalid/v1",
            apiKey = badApiKeyCipher,
            customHeaders = listOf(CustomHeader("Authorization", badHeaderCipher)),
        )
        val healthyProvider = ProviderSetting.Claude(
            id = googleId,
            name = "HealthyClaude",
            apiKey = validClaudeCipher,
        )
        perform {
            dataStore.edit { prefs ->
                prefs.clear()
                prefs[SettingsStore.VERSION] = 5
                prefs[SettingsStore.PROVIDERS] = JsonInstant.encodeToString(listOf<ProviderSetting>(brokenProvider, healthyProvider))
            }
        }.getOrThrow()

        val store = openStore()
        assertTrue("bad single field must NOT crash startup", perform { store.awaitInitialized() }.isSuccess)
        val degradedOpenAi = store.settingsFlow.value.providers.first { it.id == openAiId } as ProviderSetting.OpenAI
        val workingClaude = store.settingsFlow.value.providers.first { it.id == googleId } as ProviderSetting.Claude
        assertTrue("broken provider must be marked degraded", degradedOpenAi.isCredentialDegraded)
        assertEquals(listOf("apiKey", "customHeaders[Authorization]"), degradedOpenAi.degradedSecretFields())
        assertFalse("healthy provider must not be marked degraded", workingClaude.isCredentialDegraded)
        assertEquals("synthetic-valid-claude-key", workingClaude.apiKey)

        try {
            KeyRoulette.default().next(degradedOpenAi.apiKey, degradedOpenAi.id.toString())
            fail("Expected degraded apiKey to fail fast")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("degraded"))
        }
        try {
            degradedOpenAi.customHeaders.toHeaders()
            fail("Expected degraded customHeader to fail fast")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("degraded"))
        }

        perform {
            store.update { current ->
                current.copy(
                    titlePrompt = "Save while degraded",
                    providers = current.providers.map { p ->
                        if (p.id == openAiId) (p as ProviderSetting.OpenAI).copy(apiKey = "") else p
                    },
                )
            }
        }.getOrThrow()
        val afterDegradedSaveRaw = perform { dataStore.data.first()[SettingsStore.PROVIDERS] }.getOrThrow()!!
        assertTrue("bad apiKey ciphertext on disk must be preserved verbatim", afterDegradedSaveRaw.contains(badApiKeyCipher))
        assertTrue("bad header ciphertext on disk must be preserved verbatim", afterDegradedSaveRaw.contains(badHeaderCipher))
    }

    @Test
    fun `backup export redacts secrets while new-install import restores all non-sensitive provider configs`() {
        val fullSettings = Settings(providers = configuredProviders)
        val exportedForBackup = withRedactedProviderSecretsForBackup(fullSettings)
        val backupJson = JsonInstant.encodeToString(exportedForBackup)
        assertFalse("backup settings.json must not contain plaintext apiKey", backupJson.contains(openAiSecret))
        assertFalse("backup settings.json must not contain plaintext privateKey", backupJson.contains("SYNTHETIC_VERTEX_KEY_9003"))
        assertFalse("backup settings.json must not contain plaintext header secret", backupJson.contains(headerSecret))
        assertTrue("backup settings.json preserves baseUrl", backupJson.contains("https://gateway.example.invalid/v1"))
        assertTrue("backup settings.json preserves models", backupJson.contains("gpt-4.1-custom"))
        assertTrue("backup settings.json preserves Vault reference", backupJson.contains(backendToken))

        SyntheticSettingsKeyStore.key = newKey()
        val restoredSettings = withSanitizedProvidersFromBackup(
            JsonInstant.decodeFromString<Settings>(SettingsJsonMigrator.migrate(backupJson)),
        )
        val restoredOpenAi = restoredSettings.providers.first { it.id == openAiId } as ProviderSetting.OpenAI
        val restoredBackend = restoredSettings.providers.first { it.id == backendId } as ProviderSetting.Backend
        assertEquals("CustomOpenAIGateway", restoredOpenAi.name)
        assertEquals("https://gateway.example.invalid/v1", restoredOpenAi.baseUrl)
        assertEquals("gpt-4.1-custom", restoredOpenAi.models.single().modelId)
        assertEquals("", restoredOpenAi.apiKey)
        assertFalse("sanitized backup provider is not stuck in degraded state", restoredOpenAi.isCredentialDegraded)
        assertEquals(backendToken, restoredBackend.token)
    }
}
