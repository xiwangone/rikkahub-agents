package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rerere.workspace.ssh.SshHostProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * SshHostProbeStore 的缓存语义回归（Robolectric，跑真实 DataStore，不走网络）：
 * - 否定缓存：探针失败也记 24h，TTL 内自动入口跳过、过期后重探；
 * - 键含用户名：同主机不同用户互不干扰；
 * - 正向过期：30 天后的结果视作未命中；
 * - 旧键回退：老版本 host:port 条目仍可读；
 * - 成功覆盖否定：同一键解析成功后否定记录清掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class SshHostProbeStoreTest {
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** Pump Android Main while real DataStore work proceeds on IO. No coroutine implementation is mocked. */
    private fun <T> perform(block: suspend () -> T): T {
        val task = workers.async { block() }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        check(task.isCompleted) { "SshHostProbeStore operation timed out" }
        return runBlocking { task.await() }
    }

    private fun probeResult(probedAtMs: Long = System.currentTimeMillis()): SshHostProbeResult =
        SshHostProbeResult(
            platform = "LINUX",
            os = "Ubuntu 22.04",
            arch = "x86_64",
            shell = "/bin/bash",
            cpuCores = 4,
            memoryMb = 8192,
            diskFreeMb = 10240,
            tools = listOf("git"),
            probedAtMs = probedAtMs,
        )

    @Test
    fun `negative cache blocks auto re-probe within 24h`() {
        val store = SshHostProbeStore(context)
        perform { store.putFailure("neg-24h.example.com", 22, "alice") }
        assertTrue(perform { store.isNegativeCached("neg-24h.example.com", 22, "alice") })
        // 否定记录不影响正向读数（只是没成功结果可读）。
        assertNull(perform { store.get("neg-24h.example.com", 22, "alice") })
    }

    @Test
    fun `negative cache older than 24h does not block`() {
        val store = SshHostProbeStore(context)
        // 25 小时前的失败：写时即被修剪，读侧同样不过期判定。
        perform {
            store.putFailure(
                "neg-old.example.com",
                22,
                "alice",
                failedAtMs = System.currentTimeMillis() - 25L * 60 * 60 * 1000,
            )
        }
        assertFalse(perform { store.isNegativeCached("neg-old.example.com", 22, "alice") })
        // 23 小时前的失败仍在 TTL 内。
        perform {
            store.putFailure(
                "neg-recent.example.com",
                22,
                "alice",
                failedAtMs = System.currentTimeMillis() - 23L * 60 * 60 * 1000,
            )
        }
        assertTrue(perform { store.isNegativeCached("neg-recent.example.com", 22, "alice") })
        // 从未失败过的主机不在否定缓存里。
        assertFalse(perform { store.isNegativeCached("never.example.com", 22, "alice") })
    }

    @Test
    fun `different users have separate probe entries`() {
        val store = SshHostProbeStore(context)
        perform { store.put("users.example.com", 22, probeResult(), "alice") }
        assertEquals("Ubuntu 22.04", perform { store.get("users.example.com", 22, "alice") }?.os)
        assertNull(perform { store.get("users.example.com", 22, "bob") })
        // alice 的否定记录不拦 bob。
        perform { store.putFailure("users.example.com", 22, "alice") }
        assertTrue(perform { store.isNegativeCached("users.example.com", 22, "alice") })
        assertFalse(perform { store.isNegativeCached("users.example.com", 22, "bob") })
    }

    @Test
    fun `positive result expires after 30 days`() {
        val store = SshHostProbeStore(context)
        val now = System.currentTimeMillis()
        perform {
            store.put(
                "expired.example.com",
                22,
                probeResult(probedAtMs = now - 31L * 24 * 60 * 60 * 1000),
                "alice",
            )
        }
        assertNull(perform { store.get("expired.example.com", 22, "alice") })
        perform {
            store.put(
                "fresh.example.com",
                22,
                probeResult(probedAtMs = now - 29L * 24 * 60 * 60 * 1000),
                "alice",
            )
        }
        assertEquals("Ubuntu 22.04", perform { store.get("fresh.example.com", 22, "alice") }?.os)
    }

    @Test
    fun `legacy host-port entry is readable via user key`() {
        val store = SshHostProbeStore(context)
        // 老版本只写 host:port 键；新读数先查 user 键、再回退旧键。
        perform { store.put("legacy.example.com", 22, probeResult(), null) }
        assertEquals("Ubuntu 22.04", perform { store.get("legacy.example.com", 22, "alice") }?.os)
    }

    @Test
    fun `successful probe clears the negative record`() {
        val store = SshHostProbeStore(context)
        perform { store.putFailure("clear-neg.example.com", 22, "alice") }
        assertTrue(perform { store.isNegativeCached("clear-neg.example.com", 22, "alice") })
        perform { store.put("clear-neg.example.com", 22, probeResult(), "alice") }
        assertFalse(perform { store.isNegativeCached("clear-neg.example.com", 22, "alice") })
        assertEquals("Ubuntu 22.04", perform { store.get("clear-neg.example.com", 22, "alice") }?.os)
    }

    @Test
    fun `clear removes both positive and negative records`() {
        val store = SshHostProbeStore(context)
        perform { store.put("clear.example.com", 22, probeResult(), "alice") }
        perform { store.putFailure("clear.example.com", 22, "alice") }
        perform { store.clear("clear.example.com", 22, "alice") }
        assertNull(perform { store.get("clear.example.com", 22, "alice") })
        assertFalse(perform { store.isNegativeCached("clear.example.com", 22, "alice") })
    }
}
