package me.rerere.rikkahub.data.codex

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
import okhttp3.OkHttpClient
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
 *
 * Only `refreshAll` is covered here: Codex `refreshAccount` already calls
 * `fetchUsageLocked` directly with no `runCatching`, so there is nothing to guard there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class CodexAccountRefreshCancelTest {
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

    private fun repository(client: OkHttpClient): CodexAccountRepository {
        val store = CodexCredentialStore(RuntimeEnvironment.getApplication(), json)
        store.write(
            CodexAccountState(
                accounts = listOf(
                    CodexAccount(
                        id = "a",
                        name = "a",
                        chatgptAccountId = "account-1",
                        accessToken = "access-a",
                        refreshToken = "refresh-a",
                        expiresAt = Long.MAX_VALUE,
                    )
                )
            )
        )
        return CodexAccountRepository(store = store, client = client, json = json)
    }

    @Test
    fun `refreshAll rethrows cancellation instead of swallowing it`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor {
                    started.complete(Unit)
                    runBlocking { release.await() }
                    error("unreachable: request is cancelled before proceed")
                }
            )
            .build()
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
}
