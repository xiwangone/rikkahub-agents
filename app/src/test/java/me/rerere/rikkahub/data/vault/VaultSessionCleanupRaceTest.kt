package me.rerere.rikkahub.data.vault

import android.app.Application
import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P2-13 readSessions 惰性清理并发安全（Robolectric，跑真实 DataStore）：
 * - 旧实现：读时快照算出 valid，清理时 `updateSessions { valid }` 整体覆盖——
 *   读与写之间并发签发的会话会被快照冲掉；
 * - 新实现：清理写时按当前列表重新过滤，并发签发的会话保留。
 * 阴性对照：把 readSessions 改回快照覆盖版，本测试在 20 轮 × 5 并发签发下应大概率失败
 * （某轮新签发的 token 校验失败），可验证测试本身能抓住回归。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class VaultSessionCleanupRaceTest {
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
        check(task.isCompleted) { "VaultSession operation timed out" }
        return runBlocking { task.await() }
    }

    @Before
    fun clearSessions() {
        perform { manager.revokeAll() }
    }

    @Test
    fun `concurrent issue during lazy cleanup is not dropped`() {
        repeat(20) { round ->
            perform { manager.revokeAll() }
            // 先放一个已过期会话，让下一轮 readSessions 走到惰性清理分支
            perform {
                manager.issueSessionToken(ttlMs = 1)
                Thread.sleep(10)
            }
            val tokens = perform {
                coroutineScope {
                    val issued = (1..5).map { async { manager.issueSessionToken(label = "race-$round") } }
                    val cleanup = async { manager.listSessions() }
                    cleanup.await()
                    issued.map { it.await() }
                }
            }
            perform {
                for (token in tokens) {
                    assertTrue(
                        "round $round: session issued during cleanup was dropped",
                        manager.verifyToken(token),
                    )
                }
            }
        }
    }
}
