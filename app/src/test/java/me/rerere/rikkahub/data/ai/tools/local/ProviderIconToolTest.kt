package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import java.io.InterruptedIOException
import java.security.Security
import javax.crypto.KeyGenerator
import me.rerere.rikkahub.data.datastore.ProviderCredentialCipher
import me.rerere.rikkahub.data.datastore.SyntheticSettingsKeyStore
import me.rerere.rikkahub.data.datastore.SyntheticSettingsSecurityProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * update_provider_icon 端到端（Robolectric）：名字解析、下载校验、覆盖表写入与清除。
 * 下载用 OkHttp interceptor 伪造，不发真实网络请求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class ProviderIconToolTest {
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var context: Application
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var appScope: AppScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settingsStore: SettingsStore
    private lateinit var securityProvider: SyntheticSettingsSecurityProvider

    private val pngBytes: ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)
    private val svgBytes: ByteArray =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><circle cx="5" cy="5" r="4"/></svg>""".toByteArray()

    /** Pump Android Main while real DataStore work proceeds on IO. No coroutine implementation is mocked. */
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
        // 持久化提供商会加密 apiKey，Robolectric 下没有 AndroidKeyStore，
        // 用仓内现成的 synthetic 夹具代替（同 ProviderFieldEncryptionTest）。
        securityProvider = SyntheticSettingsSecurityProvider()
        Security.insertProviderAt(securityProvider, 1)
        SyntheticSettingsKeyStore.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SyntheticSettingsKeyStore.unavailable = false
        ProviderCredentialCipher.clearDegradedStateForTest()
        context = RuntimeEnvironment.getApplication()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        appScope = AppScope()
        val fileName = "test-settings-${System.nanoTime()}"
        dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            context.preferencesDataStoreFile(fileName)
        }
        settingsStore = SettingsStore(context, appScope, dataStore)
        perform {
            settingsStore.update {
                it.copy(
                    providers = listOf(
                        ProviderSetting.OpenAI(name = "mazal", baseUrl = "https://mazal.lol/v1"),
                        ProviderSetting.OpenAI(name = "Test", baseUrl = "https://example.com/v1"),
                    ),
                )
            }
        }.getOrThrow()
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

    private fun toolWith(client: OkHttpClient) =
        updateProviderIconTool(context, settingsStore, client)

    private fun fakeClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain -> handler(chain.request()) }.build()

    private fun okResponse(request: Request, bytes: ByteArray, contentType: String = "image/png") =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody(contentType.toMediaType()))
            .build()

    private fun args(provider: String, icon: String) = buildJsonObject {
        put("provider", provider)
        put("icon", icon)
    }.toString()

    private fun overrides(): Map<String, String> = runBlocking {
        settingsStore.settingsFlow.first().providerIconOverrides
    }

    @Test
    fun `asset icon sets override`() {
        val out = execTool(toolWith(fakeClient { error("must not download") }), args("mazal", "openrouter.svg"))
        assertTrue(out, out.contains("asset:openrouter.svg"))
        assertEquals("asset:openrouter.svg", overrides()["mazal"])
    }

    @Test
    fun `unknown asset name returns invalid_argument`() {
        val out = execTool(toolWith(fakeClient { error("must not download") }), args("mazal", "not-a-brand.svg"))
        assertTrue(out, out.contains("invalid_argument"))
        assertFalse(overrides().containsKey("mazal"))
    }

    @Test
    fun `unknown provider returns not_found with available names`() {
        val out = execTool(toolWith(fakeClient { error("must not download") }), args("nope", "openrouter.svg"))
        assertTrue(out, out.contains("not_found"))
        assertTrue(out, out.contains("mazal"))
        assertTrue(out, out.contains("Test"))
    }

    @Test
    fun `case-insensitive unique name resolves`() {
        val out = execTool(toolWith(fakeClient { error("must not download") }), args("MAZAL", "openrouter.svg"))
        assertTrue(out, out.contains("asset:openrouter.svg"))
        assertEquals("asset:openrouter.svg", overrides()["mazal"])
    }

    @Test
    fun `ambiguous provider returns invalid_argument with matches`() {
        perform {
            settingsStore.update {
                it.copy(
                    providers = it.providers + ProviderSetting.OpenAI(name = "MAZAL", baseUrl = "https://x.example/v1"),
                )
            }
        }.getOrThrow()
        val out = execTool(toolWith(fakeClient { error("must not download") }), args("Mazal", "openrouter.svg"))
        assertTrue(out, out.contains("invalid_argument"))
        assertTrue(out, out.contains("mazal"))
        assertTrue(out, out.contains("MAZAL"))
    }

    @Test
    fun `url png download stores file and override`() {
        val client = fakeClient { okResponse(it, pngBytes) }
        val out = execTool(toolWith(client), args("mazal", "https://example.com/icon.png"))
        assertTrue(out, out.contains("file:provider-icons/mazal.png"))
        assertEquals("file:provider-icons/mazal.png", overrides()["mazal"])
        assertTrue(File(context.filesDir, "provider-icons/mazal.png").exists())
    }

    @Test
    fun `url svg download stores file`() {
        val client = fakeClient { okResponse(it, svgBytes, "image/svg+xml") }
        val out = execTool(toolWith(client), args("Test", "https://example.com/icon.svg"))
        assertTrue(out, out.contains("file:provider-icons/Test.svg"))
        assertEquals("file:provider-icons/Test.svg", overrides()["Test"])
        assertTrue(File(context.filesDir, "provider-icons/Test.svg").exists())
    }

    @Test
    fun `url 404 returns error and writes nothing`() {
        val client = fakeClient {
            Response.Builder().request(it).protocol(Protocol.HTTP_1_1).code(404).message("Not Found")
                .body(ByteArray(0).toResponseBody("text/plain".toMediaType())).build()
        }
        val out = execTool(toolWith(client), args("mazal", "https://example.com/missing.png"))
        assertTrue(out, out.contains("tool_failed"))
        assertFalse(overrides().containsKey("mazal"))
        assertFalse(File(context.filesDir, "provider-icons").exists())
    }

    @Test
    fun `url non-image returns invalid_argument`() {
        val client = fakeClient { okResponse(it, "<html><body>nope</body></html>".toByteArray(), "text/html") }
        val out = execTool(toolWith(client), args("mazal", "https://example.com/page.html"))
        assertTrue(out, out.contains("invalid_argument"))
        assertFalse(overrides().containsKey("mazal"))
    }

    @Test
    fun `url oversize returns invalid_argument`() {
        val big = ByteArray(PROVIDER_ICON_MAX_BYTES + 1) { 0x41 }
        pngBytes.copyInto(big)
        val client = fakeClient { okResponse(it, big) }
        val out = execTool(toolWith(client), args("mazal", "https://example.com/big.png"))
        assertTrue(out, out.contains("invalid_argument"))
        assertTrue(out, out.contains("2 MiB"))
        assertFalse(overrides().containsKey("mazal"))
    }

    @Test
    fun `url timeout returns timeout`() {
        val client = fakeClient { throw InterruptedIOException("simulated timeout") }
        val out = execTool(toolWith(client), args("mazal", "https://example.com/slow.png"))
        assertTrue(out, out.contains("timeout"))
        assertFalse(overrides().containsKey("mazal"))
    }

    @Test
    fun `clear override restores auto and deletes downloaded file`() {
        val client = fakeClient { okResponse(it, pngBytes) }
        execTool(toolWith(client), args("mazal", "https://example.com/icon.png"))
        assertEquals("file:provider-icons/mazal.png", overrides()["mazal"])
        assertTrue(File(context.filesDir, "provider-icons/mazal.png").exists())

        val out = execTool(toolWith(client), args("mazal", ""))
        assertTrue(out, out.contains("cleared"))
        assertFalse(overrides().containsKey("mazal"))
        assertFalse(File(context.filesDir, "provider-icons/mazal.png").exists())
    }

    @Test
    fun `clear override of asset value removes entry only`() {
        execTool(toolWith(fakeClient { error("must not download") }), args("mazal", "openrouter.svg"))
        assertEquals("asset:openrouter.svg", overrides()["mazal"])
        val out = execTool(toolWith(fakeClient { error("must not download") }), args("mazal", ""))
        assertTrue(out, out.contains("cleared"))
        assertFalse(overrides().containsKey("mazal"))
    }
}
