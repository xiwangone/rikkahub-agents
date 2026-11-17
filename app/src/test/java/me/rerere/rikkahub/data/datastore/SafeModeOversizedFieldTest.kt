package me.rerere.rikkahub.data.datastore

import android.app.Application
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
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

/**
 * 超大设置字段在安全模式里列出并清除：seed 一个 6M 字符的 lorebooks，
 * 断言 flow 列出它，reset 后内存与盘上均为缺省、其余设置不受影响。
 * 只用真实 SettingsStore 与临时 DataStore 文件，不涉及 Room 与设备端。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class SafeModeOversizedFieldTest {
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeScope: AppScope? = null
    private var activeDataStoreScope: CoroutineScope? = null
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settingsFile: File
    private val assistantId = Uuid.parse("11111111-2222-4333-8444-555555555555")
    private val modelId = Uuid.parse("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
    private val assistant = Assistant(id = assistantId, name = "Synthetic preserved assistant")

    /** Pump Android Main while real DataStore work proceeds on IO. No coroutine implementation is mocked. */
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

    private fun open(): SettingsStore {
        val scope = AppScope()
        activeScope = scope
        return SettingsStore(RuntimeEnvironment.getApplication(), scope, dataStore)
    }

    @Test
    fun oversizedLorebooksListedThenResetToDefault() {
        // resetStoredField 走 persistSettings 写盘：盘上没有的加密字段会被写缺省加密值，
        // 需要 synthetic keystore，否则 ProviderCredentialCipher.encrypt 直接抛。
        val provider = SyntheticSettingsSecurityProvider()
        Security.insertProviderAt(provider, 1)
        SyntheticSettingsKeyStore.key = newKey()
        SyntheticSettingsKeyStore.unavailable = false
        stopKoin()
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        try {
            val context: Application = RuntimeEnvironment.getApplication()
            settingsFile = File(context.filesDir, "datastore/settings.preferences_pb")
            val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            activeDataStoreScope = seedScope
            dataStore = PreferenceDataStoreFactory.create(scope = seedScope, produceFile = { settingsFile })

            val bigDescription = "x".repeat(6_000_000)
            val bigLorebooks = JsonInstant.encodeToString(
                listOf(Lorebook(name = "Synthetic big lorebook", description = bigDescription)),
            )
            perform {
                dataStore.edit { values ->
                    values.clear()
                    values[SettingsStore.VERSION] = 3
                    values[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(listOf(assistant))
                    values[SettingsStore.SELECT_ASSISTANT] = assistantId.toString()
                    values[SettingsStore.SELECT_MODEL] = modelId.toString()
                    values[SettingsStore.TITLE_PROMPT] = "Synthetic original title prompt"
                    values[SettingsStore.DYNAMIC_COLOR] = false
                    values[SettingsStore.LOREBOOKS] = bigLorebooks
                }
            }.getOrThrow()

            val store = open()
            assertTrue("seeded store initializes", perform { store.awaitInitialized() }.isSuccess)
            assertEquals("seeded lorebook decodes", 1, store.settingsFlow.value.lorebooks.size)
            assertEquals(bigDescription, store.settingsFlow.value.lorebooks.single().description)

            val listed = perform {
                store.largeStoredFieldsFlow().first { fields -> fields.any { it.keyName == "lorebooks" } }
            }.getOrThrow()
            assertEquals("only the oversized field is listed", 1, listed.size)
            assertEquals("lorebooks", listed.single().keyName)
            assertTrue("listed size covers the seeded JSON", listed.single().charCount >= bigLorebooks.length)

            perform { store.resetStoredField("lorebooks") }.getOrThrow()

            val cleared = perform { store.largeStoredFieldsFlow().first { it.isEmpty() } }.getOrThrow()
            assertTrue("no oversized field remains", cleared.isEmpty())
            assertTrue("memory lorebooks reset to default", store.settingsFlow.value.lorebooks.isEmpty())
            val stored = perform { dataStore.data.first()[SettingsStore.LOREBOOKS] }.getOrThrow()
            assertTrue("disk lorebooks is gone or the default empty encoding", stored == null || stored == "[]")
            assertTrue("assistant survives the reset", store.settingsFlow.value.assistants.any { it.id == assistantId })
            assertEquals(
                "title prompt survives the reset",
                "Synthetic original title prompt",
                store.settingsFlow.value.titlePrompt,
            )
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
