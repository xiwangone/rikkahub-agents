package me.rerere.rikkahub.data.vault

import android.app.Application
import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
 * P1-3 授权名白名单回归（Robolectric，跑真实 DataStore）：
 * - 受限会话只能访问白名单内的凭证名，白名单外的名字一律拒绝（阴性断言）；
 * - 「一直有效」会话同样受名字过滤——永不过期不等于可访问全部凭证；
 * - 不限制的会话（allowedNames 为空）保持既有行为；
 * - 过期会话对任何名字都拒绝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class VaultSessionManagerTest {
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = VaultSessionManager(context)

    /** Pump Android Main while real DataStore work proceeds on IO. No coroutine implementation is mocked. */
    private fun <T> perform(block: suspend () -> T): T {
        val task = workers.async { block() }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        check(task.isCompleted) { "VaultSessionManager operation timed out" }
        return runBlocking { task.await() }
    }

    @Before
    fun clearSessions() {
        perform { manager.revokeAll() }
    }

    @Test
    fun `no session denies any name`() {
        perform {
            assertFalse(manager.hasActiveAuthorization("ANY_CRED"))
            assertFalse(manager.hasActiveAuthorization())
        }
    }

    @Test
    fun `unrestricted session allows any name`() {
        perform {
            manager.issueSessionToken(ttlMs = VaultSessionManager.TTL_MS)
            assertTrue(manager.hasActiveAuthorization("ANY_CRED"))
            assertTrue(manager.hasActiveAuthorization("OTHER_CRED"))
            assertTrue(manager.hasActiveAuthorization())
        }
    }

    @Test
    fun `restricted session allows only whitelisted name`() {
        perform {
            manager.issueSessionToken(
                ttlMs = VaultSessionManager.TTL_MS,
                allowedNames = listOf("ALLOWED_CRED"),
            )
            assertTrue(manager.hasActiveAuthorization("ALLOWED_CRED"))
            // 阴性断言：白名单外的名字必须拒绝——这就是 P1-3 修的 bug（旧实现只看有没有会话）。
            assertFalse(manager.hasActiveAuthorization("OTHER_CRED"))
            assertFalse(manager.hasActiveAuthorization("allowed_cred"))
        }
    }

    @Test
    fun `always valid session is still name filtered`() {
        perform {
            manager.issueSessionToken(
                ttlMs = VaultSessionManager.TTL_SESSION_MS,
                allowedNames = listOf("ALLOWED_CRED"),
            )
            assertTrue(manager.hasActiveAuthorization("ALLOWED_CRED"))
            // 「一直有效」只豁免过期，不豁免名字过滤。
            assertFalse(manager.hasActiveAuthorization("OTHER_CRED"))
            assertTrue(manager.hasActiveAuthorization())
        }
    }

    @Test
    fun `expired session denies any name`() {
        perform {
            manager.issueSessionToken(ttlMs = VaultSessionManager.TTL_MS)
            val farFuture = System.currentTimeMillis() + VaultSessionManager.TTL_MS + 1_000L
            assertFalse(manager.hasActiveAuthorization("ANY_CRED", now = farFuture))
            assertFalse(manager.hasActiveAuthorization(now = farFuture))
        }
    }

    @Test
    fun `multiple sessions union by name`() {
        perform {
            manager.issueSessionToken(
                ttlMs = VaultSessionManager.TTL_MS,
                allowedNames = listOf("CRED_A"),
            )
            manager.issueSessionToken(
                ttlMs = VaultSessionManager.TTL_MS,
                allowedNames = listOf("CRED_B"),
            )
            assertTrue(manager.hasActiveAuthorization("CRED_A"))
            assertTrue(manager.hasActiveAuthorization("CRED_B"))
            assertFalse(manager.hasActiveAuthorization("CRED_C"))
        }
    }
}
