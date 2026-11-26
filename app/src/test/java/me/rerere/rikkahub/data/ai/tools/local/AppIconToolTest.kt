package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.ProviderCredentialCipher
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SyntheticSettingsKeyStore
import me.rerere.rikkahub.data.datastore.SyntheticSettingsSecurityProvider
import me.rerere.rikkahub.utils.AppIconPreset
import me.rerere.rikkahub.utils.AppIconManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class AppIconToolTest {
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var context: Application
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var appScope: AppScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settingsStore: SettingsStore
    private lateinit var securityProvider: SyntheticSettingsSecurityProvider

    private fun <T> perform(block: suspend () -> T): Result<T> {
        val task = workers.async { block() }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        check(task.isCompleted) { "Settings operation timed out" }
        return runCatching { runBlocking { task.await() } }
    }

    @Before
    fun setUp() {
        securityProvider = SyntheticSettingsSecurityProvider()
        Security.insertProviderAt(securityProvider, 1)
        SyntheticSettingsKeyStore.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SyntheticSettingsKeyStore.unavailable = false
        ProviderCredentialCipher.clearDegradedStateForTest()
        context = RuntimeEnvironment.getApplication()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        appScope = AppScope()
        val fileName = "test-app-icon-${System.nanoTime()}"
        dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            context.preferencesDataStoreFile(fileName)
        }
        settingsStore = SettingsStore(context, appScope, dataStore)
        AppIconManager.currentStatIconRes = AppIconPreset.FLOW_YAO.artwork.statIconRes
    }

    @After
    fun tearDown() {
        runCatching { perform { appScope.coroutineContext[Job]?.cancelAndJoin() } }
        runCatching { dataStoreScope.cancel() }
        ProviderCredentialCipher.clearDegradedStateForTest()
        SyntheticSettingsKeyStore.key = null
        SyntheticSettingsKeyStore.unavailable = false
        Security.removeProvider(securityProvider.name)
    }

    private fun parseToolText(parts: List<UIMessagePart>) =
        Json.parseToJsonElement((parts.first() as UIMessagePart.Text).text).jsonObject

    @Test
    fun `list returns all 9 presets with flow_yao active initially`() {
        val tool = updateAppIconTool(context, settingsStore)
        val result = perform {
            tool.execute(buildJsonObject { put("action", "list") })
        }.getOrThrow()

        val json = parseToolText(result)
        assertTrue(json["ok"]!!.jsonPrimitive.boolean)
        assertEquals("flow_yao", json["current"]!!.jsonPrimitive.content)
        val presets = json["presets"]!!.jsonArray
        assertEquals(AppIconPreset.entries.size, presets.size)
        val activeIds = presets.filter { it.jsonObject["active"]!!.jsonPrimitive.boolean }
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("flow_yao"), activeIds)
    }

    @Test
    fun `set updates settingsStore, launcher aliases, and notification smallIcon`() {
        val tool = updateAppIconTool(context, settingsStore)
        val result = perform {
            tool.execute(buildJsonObject {
                put("action", "set")
                put("preset", "minimal_yao")
            })
        }.getOrThrow()

        val json = parseToolText(result)
        assertTrue(json["ok"]!!.jsonPrimitive.boolean)
        assertEquals("minimal_yao", json["preset"]!!.jsonPrimitive.content)
        assertEquals("flow_yao", json["previous"]!!.jsonPrimitive.content)

        val stored = perform { settingsStore.settingsFlow.first() }.getOrThrow()
        assertEquals(AppIconPreset.MINIMAL_YAO, stored.displaySetting.appIconPreset)
        assertEquals(AppIconPreset.MINIMAL_YAO.artwork.statIconRes, AppIconManager.currentStatIconRes)

        val pm = context.packageManager
        val pkg = context.packageName
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            pm.getComponentEnabledSetting(ComponentName(pkg, "$pkg.MainActivityMinimalYao")),
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            pm.getComponentEnabledSetting(ComponentName(pkg, "$pkg.MainActivityFlowYao")),
        )
    }

    @Test
    fun `set with unknown preset returns error without mutating settings`() {
        val tool = updateAppIconTool(context, settingsStore)
        val result = perform {
            tool.execute(buildJsonObject {
                put("action", "set")
                put("preset", "does_not_exist")
            })
        }.getOrThrow()

        val json = parseToolText(result)
        assertEquals("not_found", json["data"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        val stored = perform { settingsStore.settingsFlow.first() }.getOrThrow()
        assertEquals(AppIconPreset.FLOW_YAO, stored.displaySetting.appIconPreset)
    }
}
