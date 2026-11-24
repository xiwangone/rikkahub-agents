package me.rerere.rikkahub.data.datastore

import android.app.Application
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.pebbletemplates.pebble.PebbleEngine
import java.io.File
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

/**
 * 深挖-E1：单个已存字段损坏时整库不再停在 warm seed 上——
 * 坏字段被隔离（用默认值）、原始值快照到恢复目录、其余字段照常解码；
 * 内存基线仍是 warm seed 时 persistSettings 拒绝落盘（防覆盖用户真实设置）。
 * 只用真实 SettingsStore 与临时 DataStore 文件，不涉及 Room 与设备端。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class SettingsQuarantineTest {
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeScope: AppScope? = null
    private var activeDataStoreScope: CoroutineScope? = null
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settingsFile: File

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

    private fun openStore(fileName: String, seed: (MutablePreferences) -> Unit = {}): SettingsStore {
        stopKoin()
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        val context: Application = RuntimeEnvironment.getApplication()
        settingsFile = File(context.filesDir, "datastore/$fileName")
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        activeDataStoreScope = seedScope
        dataStore = PreferenceDataStoreFactory.create(scope = seedScope, produceFile = { settingsFile })
        perform {
            dataStore.edit { values ->
                values.clear()
                values[SettingsStore.TITLE_PROMPT] = "Synthetic original title prompt"
                seed(values)
            }
        }.getOrThrow()
        val scope = AppScope()
        activeScope = scope
        return SettingsStore(context, scope, dataStore)
    }

    private fun teardown() {
        runCatching { stopCollector() }
        runCatching { stopDataStoreScope() }
        runCatching { stopKoin() }
        runCatching { workers.cancel() }
    }

    @Test
    fun `one corrupt field is quarantined and the rest still loads`() {
        val store =
            openStore("quarantine-single.preferences_pb") { values ->
                values[SettingsStore.ASSISTANTS] = "not-json"
            }
        try {
            assertTrue("store initializes despite the corrupt field", perform { store.awaitInitialized() }.isSuccess)
            val quarantined =
                perform {
                    store.quarantinedFields.first { it.isNotEmpty() }
                }.getOrThrow()
            assertEquals("only the corrupt field is quarantined", listOf("assistants"), quarantined.map { it.keyName })
            assertEquals(
                "title prompt survives the quarantine",
                "Synthetic original title prompt",
                store.settingsFlow.value.titlePrompt,
            )
            assertEquals(
                "quarantined assistants fall back to the seeded defaults",
                DEFAULT_ASSISTANTS,
                store.settingsFlow.value.assistants,
            )
            val snapshot =
                perform {
                    store.quarantineSnapshot.first { it != null }
                }.getOrThrow()
            assertTrue("snapshot file exists", snapshot!!.exists())
            assertTrue("snapshot keeps the raw corrupt value", snapshot.readText().contains("not-json"))
        } finally {
            teardown()
        }
    }

    @Test
    fun `two corrupt fields are both quarantined`() {
        val store =
            openStore("quarantine-double.preferences_pb") { values ->
                values[SettingsStore.ASSISTANTS] = "not-json"
                values[SettingsStore.SELECT_MODEL] = "not-a-uuid"
            }
        try {
            assertTrue("store initializes despite two corrupt fields", perform { store.awaitInitialized() }.isSuccess)
            val quarantined =
                perform {
                    store.quarantinedFields.first { it.size >= 2 }
                }.getOrThrow()
            assertEquals(
                // 隔离记录记的是 Preferences.Key 的名字：SELECT_MODEL 的键名是 chat_model。
                listOf("assistants", "chat_model"),
                quarantined.map { it.keyName }.sorted(),
            )
            assertEquals(
                "corrupt model id falls back to the auto model",
                DEFAULT_AUTO_MODEL_ID,
                store.settingsFlow.value.chatModelId,
            )
            assertEquals(
                "title prompt survives the quarantine",
                "Synthetic original title prompt",
                store.settingsFlow.value.titlePrompt,
            )
        } finally {
            teardown()
        }
    }

    /**
     * 加密字段坏值不隔离、仍 fail-loud：解密失败可能是暂时的（错 key / key 不可用），
     * 密文本身可能完好；默认回退 + 随后写盘会销毁可恢复的密文。钉死
     * `MediaGenerationSettingsPersistenceTest` 的同一契约在 quarantine 侧。
     */
    @Test
    fun `corrupt media ciphertext stays fail-loud instead of being quarantined`() {
        val store =
            openStore("quarantine-encrypted-media.preferences_pb") { values ->
                values[SettingsStore.MEDIA_GENERATION_PROVIDERS] = "synthetic-not-json-or-valid-base64!"
            }
        try {
            assertTrue("corrupt media ciphertext must fail initialization", perform { store.awaitInitialized() }.isFailure)
            assertTrue("no field is quarantined on the fail-loud path", store.quarantinedFields.value.isEmpty())
            assertTrue("store stays on the warm seed", store.settingsFlow.value.init)
        } finally {
            teardown()
        }
    }

    @Test
    fun `corrupt providers value stays fail-loud instead of being quarantined`() {
        val store =
            openStore("quarantine-encrypted-providers.preferences_pb") { values ->
                values[SettingsStore.PROVIDERS] = "not-json"
            }
        try {
            assertTrue("corrupt providers value must fail initialization", perform { store.awaitInitialized() }.isFailure)
            assertTrue("no field is quarantined on the fail-loud path", store.quarantinedFields.value.isEmpty())
            assertTrue("store stays on the warm seed", store.settingsFlow.value.init)
        } finally {
            teardown()
        }
    }

    @Test
    fun `persistSettings refuses a warm-seed baseline instead of overwriting real settings`() {
        val store = openStore("quarantine-guard.preferences_pb")
        try {
            assertTrue("store initializes", perform { store.awaitInitialized() }.isSuccess)
            val good = store.settingsFlow.value
            assertTrue("seeded settings are initialized", !good.init)
            // 模拟“磁盘从未成功解码、内存仍是 warm seed”的状态。
            store.settingsFlow.value = Settings.dummy()
            val failure = perform { store.replaceSettings(good) }
            assertTrue("persist must fail loudly", failure.isFailure)
            assertTrue(
                "failure names the warm-seed baseline, not a generic error",
                failure.exceptionOrNull().toString().contains("warm-seed"),
            )
            assertTrue(
                "no partial mutation: memory still holds the dummy baseline",
                store.settingsFlow.value.init,
            )
        } finally {
            teardown()
        }
    }
}
