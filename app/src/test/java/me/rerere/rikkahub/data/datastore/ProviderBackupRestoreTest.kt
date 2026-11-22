package me.rerere.rikkahub.data.datastore

import android.app.Application
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * Covers the provider loss-prevention trio:
 * 1. A provider change writes an encrypted snapshot to a separate file.
 * 2. A settings file with no providers key restores providers from the snapshot on startup.
 * 3. A corrupted settings file restores providers from the snapshot on startup.
 *
 * Runs the production cipher and SettingsStore with synthetic preferences only. No Room or device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class ProviderBackupRestoreTest {
    private lateinit var workers: CoroutineScope
    private var activeScope: AppScope? = null
    private var activeDataStoreScope: CoroutineScope? = null
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settingsFile: File
    private lateinit var securityProvider: SyntheticSettingsSecurityProvider

    private val openAiId = Uuid.parse("55555555-5555-4555-8555-555555555555")
    private val claudeId = Uuid.parse("66666666-6666-4666-8666-666666666666")
    private val modelId = Uuid.parse("77777777-7777-4777-8777-777777777777")

    private val openAiSecret = "synthetic-backup-openai-secret-9101"
    private val headerSecret = "Bearer synthetic-backup-header-secret-9102"
    private val claudeSecret = "synthetic-backup-claude-secret-9103"

    private val customProviders = listOf<ProviderSetting>(
        ProviderSetting.OpenAI(
            id = openAiId,
            name = "BackupOpenAIGateway",
            baseUrl = "https://backup-gateway.example.invalid/v1",
            apiKey = openAiSecret,
            customHeaders = listOf(CustomHeader("X-Backup-Auth", headerSecret)),
            models = listOf(
                Model(id = modelId, modelId = "gpt-4.1-backup", displayName = "GPT-4.1 Backup"),
            ),
        ),
        ProviderSetting.Claude(
            id = claudeId,
            name = "BackupClaude",
            apiKey = claudeSecret,
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
        ProviderBackupStore.backupFile(RuntimeEnvironment.getApplication()).delete()
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
        ProviderBackupStore.backupFile(RuntimeEnvironment.getApplication()).delete()
        if (this::settingsFile.isInitialized && settingsFile.exists()) settingsFile.delete()
    }

    private fun <T> perform(block: suspend () -> T): Result<T> {
        val task = workers.async { block() }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        check(task.isCompleted) { "Provider backup restore test operation timed out" }
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

    private fun newDataStore(corruptionHandler: androidx.datastore.core.handlers.ReplaceFileCorruptionHandler<Preferences>? = null): DataStore<Preferences> {
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        activeDataStoreScope = seedScope
        return PreferenceDataStoreFactory.create(
            scope = seedScope,
            produceFile = { settingsFile },
            corruptionHandler = corruptionHandler,
        )
    }

    private fun assertCustomProvidersRestored(providers: List<ProviderSetting>) {
        val restoredOpenAi = providers.first { it.id == openAiId } as ProviderSetting.OpenAI
        val restoredClaude = providers.first { it.id == claudeId } as ProviderSetting.Claude
        assertEquals("BackupOpenAIGateway", restoredOpenAi.name)
        assertEquals("https://backup-gateway.example.invalid/v1", restoredOpenAi.baseUrl)
        assertEquals(openAiSecret, restoredOpenAi.apiKey)
        assertEquals(headerSecret, restoredOpenAi.customHeaders.single().value)
        assertEquals("gpt-4.1-backup", restoredOpenAi.models.single().modelId)
        assertEquals("BackupClaude", restoredClaude.name)
        assertEquals(claudeSecret, restoredClaude.apiKey)
    }

    @Test
    fun `provider change writes encrypted snapshot without plaintext secrets`() {
        val context: Application = RuntimeEnvironment.getApplication()
        settingsFile = File(context.filesDir, "datastore/provider_backup_test_${System.nanoTime()}.preferences_pb")
        dataStore = newDataStore()
        val store = openStore()
        assertTrue(perform { store.awaitInitialized() }.isSuccess)

        perform { store.update { it.copy(providers = customProviders) } }.getOrThrow()

        val backupFile = ProviderBackupStore.backupFile(context)
        assertTrue("backup snapshot must exist after provider change", backupFile.isFile)
        val backupText = backupFile.readText()
        assertFalse("backup snapshot must not contain plaintext apiKey", backupText.contains(openAiSecret))
        assertFalse("backup snapshot must not contain plaintext header secret", backupText.contains(headerSecret))
        assertFalse("backup snapshot must not contain plaintext claude key", backupText.contains(claudeSecret))
        val snapshotProviders = ProviderBackupStore.readBackupProviders(context)
        assertEquals(
            customProviders.map { it.id }.toSet(),
            snapshotProviders.map { it.id }.toSet(),
        )
        assertCustomProvidersRestored(snapshotProviders)

        // Unrelated settings writes must not rewrite the snapshot.
        val bytesBefore = backupFile.readBytes()
        perform { store.update { it.copy(titlePrompt = "Unrelated change") } }.getOrThrow()
        assertTrue("unrelated update must not rewrite the snapshot", bytesBefore.contentEquals(backupFile.readBytes()))
    }

    @Test
    fun `missing settings file restores providers from backup on startup`() {
        val context: Application = RuntimeEnvironment.getApplication()
        val backupJson = encodeProvidersFieldEncrypted(customProviders)
        assertTrue(ProviderBackupStore.writeBackup(context, backupJson))
        // No settings file at all: simulates a lost settings file.
        settingsFile = File(context.filesDir, "datastore/provider_restore_test_${System.nanoTime()}.preferences_pb")
        assertFalse(settingsFile.exists())
        dataStore = newDataStore()
        val store = openStore()
        assertTrue(perform { store.awaitInitialized() }.isSuccess)

        assertCustomProvidersRestored(store.settingsFlow.value.providers)
        // The restore must have persisted the providers key back into the settings file.
        val storedRaw = perform { dataStore.data.first()[SettingsStore.PROVIDERS] }.getOrThrow()
        assertTrue("restored providers key must be persisted", storedRaw != null)
        val storedProviders = decodeProvidersFieldEncrypted(storedRaw)
        assertTrue(storedProviders.any { it.id == openAiId })
    }

    @Test
    fun `corrupted settings file restores providers from backup on startup`() {
        val context: Application = RuntimeEnvironment.getApplication()
        val backupJson = encodeProvidersFieldEncrypted(customProviders)
        assertTrue(ProviderBackupStore.writeBackup(context, backupJson))
        settingsFile = File(context.filesDir, "datastore/provider_corrupt_test_${System.nanoTime()}.preferences_pb")
        settingsFile.parentFile?.mkdirs()
        // Unterminated varint bytes: cannot parse as protobuf, must trigger the corruption handler.
        settingsFile.writeBytes(ByteArray(64) { 0xFF.toByte() })
        dataStore = newDataStore(corruptionHandler = settingsCorruptionHandler)
        val store = openStore()
        assertTrue("corrupted file must still initialize", perform { store.awaitInitialized() }.isSuccess)

        assertCustomProvidersRestored(store.settingsFlow.value.providers)
        val storedRaw = perform { dataStore.data.first()[SettingsStore.PROVIDERS] }.getOrThrow()
        assertTrue("restored providers key must be persisted after corruption", storedRaw != null)
    }

    @Test
    fun `no backup means empty providers stay empty on startup`() {
        val context: Application = RuntimeEnvironment.getApplication()
        assertFalse(ProviderBackupStore.backupFile(context).exists())
        settingsFile = File(context.filesDir, "datastore/provider_nobackup_test_${System.nanoTime()}.preferences_pb")
        dataStore = newDataStore(corruptionHandler = settingsCorruptionHandler)
        val store = openStore()
        assertTrue(perform { store.awaitInitialized() }.isSuccess)
        // Only the built-in defaults are seeded; no custom provider is conjured from nothing.
        assertFalse(store.settingsFlow.value.providers.any { it.id == openAiId })
    }
}
