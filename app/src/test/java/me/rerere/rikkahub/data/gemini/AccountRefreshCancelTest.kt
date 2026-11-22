package me.rerere.rikkahub.data.gemini

import android.app.Application
import java.security.Security
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SyntheticSettingsKeyStore
import me.rerere.rikkahub.data.datastore.SyntheticSettingsSecurityProvider
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Guards the G7 fix: `runCatching` around the account refresh chain must rethrow
 * [CancellationException] instead of swallowing it. Cancellation is simulated for real — the
 * request is left in flight and the calling coroutine is cancelled. The launched coroutine
 * records whether the call returned normally or threw, because a job cancelled with
 * [CancellationException] completes as cancelled rather than exceptionally, so `join()`
 * returns normally either way and cannot tell the two apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class AccountRefreshCancelTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var provider: SyntheticSettingsSecurityProvider

    @Before
    fun setUp() {
        provider = SyntheticSettingsSecurityProvider()
        Security.insertProviderAt(provider, 1)
        SyntheticSettingsKeyStore.key =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SyntheticSettingsKeyStore.unavailable = false
    }

    @After
    fun tearDown() {
        SyntheticSettingsKeyStore.key = null
        Security.removeProvider(provider.name)
    }

    private fun account() = GeminiAccount(
        id = "a",
        name = "a",
        projectId = "project-a",
        accessToken = "access-a",
        refreshToken = "refresh-a",
        expiresAt = Long.MAX_VALUE,
    )

    private fun repository(client: OkHttpClient): GeminiAccountRepository {
        val store = GeminiCredentialStore(RuntimeEnvironment.getApplication(), json)
        store.write(GeminiAccountState(accounts = listOf(account())))
        return GeminiAccountRepository(store = store, client = client, json = json)
    }

    private fun jsonResponse(request: Request, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun clientFor(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()

    @Test
    fun `refreshAll rethrows cancellation instead of swallowing it`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = clientFor {
            started.complete(Unit)
            runBlocking { release.await() }
            error("unreachable: request is cancelled before proceed")
        }
        val repo = repository(client)
        var outcome = "not-run"
        val job = launch {
            try {
                repo.refreshAll()
                outcome = "returned-normally"
            } catch (e: CancellationException) {
                outcome = "cancelled"
                throw e
            }
        }
        withTimeout(10_000) { started.await() }
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals("refreshAll swallowed the cancellation", "cancelled", outcome)
    }

    @Test
    fun `refreshAccount rethrows cancellation from usage fetch`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = clientFor { request ->
            val url = request.url.toString()
            when {
                url.contains("oauth2.googleapis.com/token") ->
                    jsonResponse(
                        request,
                        """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""",
                    )
                url.contains("fetchAvailableModels") -> {
                    started.complete(Unit)
                    runBlocking { release.await() }
                    error("unreachable: request is cancelled before proceed")
                }
                else -> error("unexpected request: $url")
            }
        }
        val repo = repository(client)
        var outcome = "not-run"
        val job = launch {
            try {
                repo.refreshAccount("a")
                outcome = "returned-normally"
            } catch (e: CancellationException) {
                outcome = "cancelled"
                throw e
            }
        }
        withTimeout(10_000) { started.await() }
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals("refreshAccount swallowed the cancellation from usage fetch", "cancelled", outcome)
    }
}
