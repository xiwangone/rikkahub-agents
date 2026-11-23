package me.rerere.rikkahub.data.sync

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SyntheticSettingsKeyStore
import me.rerere.rikkahub.data.datastore.SyntheticSettingsSecurityProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

/**
 * 备份加密"缺口令"路径：加密开关已开、但没有可用记住口令时，
 * maybeEncrypt 必须抛 [BackupMissingPassphraseException]（调用方可据此
 * 弹框让用户设口令后重试），而不是裸 IllegalStateException 淹没在
 * toast 里——WebDAV Backup Now 4/4"加密后静默丢弃"正是这个状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class BackupEncryptionManagerTest {
    private lateinit var workers: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settingsFile: File
    private var activeScope: AppScope? = null
    private var activeDataStoreScope: CoroutineScope? = null
    private lateinit var securityProvider: SyntheticSettingsSecurityProvider

    @Before
    fun setUp() {
        workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        securityProvider = SyntheticSettingsSecurityProvider()
        Security.insertProviderAt(securityProvider, 1)
        SyntheticSettingsKeyStore.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SyntheticSettingsKeyStore.unavailable = false
        stopKoin()
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        val context: Application = RuntimeEnvironment.getApplication()
        settingsFile = File(context.filesDir, "datastore/backup_enc_test_${System.nanoTime()}.preferences_pb")
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        activeDataStoreScope = seedScope
        dataStore = PreferenceDataStoreFactory.create(scope = seedScope, produceFile = { settingsFile })
    }

    @After
    fun tearDown() {
        stopCollector()
        activeDataStoreScope?.let { scope ->
            perform { scope.coroutineContext[Job]!!.cancelAndJoin() }.getOrThrow()
            activeDataStoreScope = null
        }
        stopKoin()
        workers.cancel()
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
        check(task.isCompleted) { "Backup encryption test operation timed out" }
        return runCatching { runBlocking { task.await() } }
    }

    private fun stopCollector() {
        activeScope?.let { scope -> perform { scope.coroutineContext[Job]!!.cancelAndJoin() }.getOrThrow() }
        activeScope = null
    }

    private fun openStore(): SettingsStore {
        val scope = AppScope()
        activeScope = scope
        return SettingsStore(RuntimeEnvironment.getApplication(), scope, dataStore)
    }

    private fun seedEncryption(enabled: Boolean, passwordEnc: String?) {
        perform {
            dataStore.edit { prefs ->
                prefs[SettingsStore.BACKUP_ENCRYPTION_ENABLED] = enabled
                if (passwordEnc == null) {
                    prefs.remove(SettingsStore.BACKUP_ENCRYPTION_PASSWORD_ENC)
                } else {
                    prefs[SettingsStore.BACKUP_ENCRYPTION_PASSWORD_ENC] = passwordEnc
                }
            }
        }.getOrThrow()
    }

    private fun manager(): BackupEncryptionManager {
        val store = openStore()
        perform { store.awaitInitialized() }.getOrThrow()
        return BackupEncryptionManager(store)
    }

    private fun tempZip(): File {
        val dir = RuntimeEnvironment.getApplication().cacheDir
        return File.createTempFile("backup_20261123_test_", ".zip", dir).apply { writeBytes(ByteArray(1024) { 0x41 }) }
    }

    @Test
    fun `disabled returns plain file unchanged`() {
        seedEncryption(enabled = false, passwordEnc = null)
        val plain = tempZip()
        try {
            assertEquals(plain, manager().maybeEncrypt(plain))
        } finally {
            plain.delete()
        }
    }

    @Test
    fun `enabled without remembered password throws missing passphrase`() {
        seedEncryption(enabled = true, passwordEnc = null)
        val plain = tempZip()
        try {
            assertThrows(BackupMissingPassphraseException::class.java) {
                manager().maybeEncrypt(plain)
            }
        } finally {
            plain.delete()
        }
    }

    @Test
    fun `enabled with undecryptable stored password throws missing passphrase`() {
        // 记住的密文解不开（KeyStore 密钥丢失/被替换）与"从未记住"同款：
        // decrypt 返回 null，调用方同样应弹框让用户重输口令。
        seedEncryption(enabled = true, passwordEnc = "not-valid-base64!!!")
        val plain = tempZip()
        try {
            assertThrows(BackupMissingPassphraseException::class.java) {
                manager().maybeEncrypt(plain)
            }
        } finally {
            plain.delete()
        }
    }

    @Test
    fun `enabled with valid remembered password encrypts to enc file`() {
        val enc = BackupPasswordCipher.encrypt("test-passphrase-123")
        seedEncryption(enabled = true, passwordEnc = enc)
        val plain = tempZip()
        try {
            val result = manager().maybeEncrypt(plain)
            assertNotEquals(plain, result)
            assertTrue(result.name.endsWith(".enc"))
            assertTrue(result.exists())
            assertTrue(result.length() > 0)
            result.delete()
        } finally {
            plain.delete()
        }
    }

    @Test
    fun `remembered password is null when nothing stored`() {
        seedEncryption(enabled = true, passwordEnc = null)
        assertEquals(null, manager().rememberedPassword)
    }

    @Test
    fun `settings flow sees seeded encryption flag`() {
        seedEncryption(enabled = true, passwordEnc = null)
        val store = openStore()
        perform { store.awaitInitialized() }.getOrThrow()
        val settings = runBlocking { store.settingsFlow.first() }
        assertTrue(settings.backupEncryptionEnabled)
        assertEquals("", settings.backupEncryptionPasswordEnc)
    }
}
