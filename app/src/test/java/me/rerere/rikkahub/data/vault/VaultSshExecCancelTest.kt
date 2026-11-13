package me.rerere.rikkahub.data.vault

import com.jcraft.jsch.ChannelExec
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P2-10 vault_ssh_exec 取消及时性（纯 JVM 单测，假 ChannelExec）：
 * - 旧实现用 Thread.sleep(100) 轮询且不检查取消，取消后最长等到 300s 超时才退出；
 * - 新实现用 delay + ensureActive，取消后应立即抛 CancellationException。
 * 阴性对照：把本文件的 drainChannelStreams 换回 Thread.sleep 版，本测试的取消用例会在
 * withTimeout(10s) 内失败（等到 deadline 300s），可验证测试本身能抓住回归。
 */
class VaultSshExecCancelTest {

    private class FakeChannelExec(
        private val stdoutBytes: ByteArray = ByteArray(0),
        private val stderrBytes: ByteArray = ByteArray(0),
        private val closed: () -> Boolean = { true },
    ) : ChannelExec() {
        override fun getInputStream(): InputStream = ByteArrayInputStream(stdoutBytes)
        override fun getErrStream(): InputStream = ByteArrayInputStream(stderrBytes)
        override fun isClosed(): Boolean = closed()
    }

    @Test
    fun `cancellation exits promptly instead of waiting for deadline`() = runBlocking {
        withTimeout(10_000) {
            val channel = FakeChannelExec(closed = { false })
            val job = async { drainChannelStreams(channel, System.currentTimeMillis() + 300_000) }
            delay(300)
            val start = System.currentTimeMillis()
            job.cancel()
            try {
                job.await()
                fail("expected CancellationException")
            } catch (e: CancellationException) {
                // 预期：取消后立即退出
            }
            val elapsed = System.currentTimeMillis() - start
            assertTrue("cancellation took ${elapsed}ms, expected < 2000ms", elapsed < 2_000)
        }
    }

    @Test
    fun `closed channel returns promptly with captured output`() = runBlocking {
        withTimeout(10_000) {
            val channel = FakeChannelExec(
                stdoutBytes = "hello".toByteArray(),
                stderrBytes = "oops".toByteArray(),
                closed = { true },
            )
            val (out, err, timedOut) = drainChannelStreams(channel, System.currentTimeMillis() + 300_000)
            assertEquals("hello", out.toString("UTF-8"))
            assertEquals("oops", err.toString("UTF-8"))
            assertFalse(timedOut)
        }
    }

    @Test
    fun `deadline expiry still reports timedOut`() = runBlocking {
        withTimeout(10_000) {
            val channel = FakeChannelExec(closed = { false })
            val start = System.currentTimeMillis()
            val (_, _, timedOut) = drainChannelStreams(channel, start + 200)
            assertTrue(timedOut)
            val elapsed = System.currentTimeMillis() - start
            assertTrue("deadline wait took ${elapsed}ms, expected < 5000ms", elapsed < 5_000)
        }
    }
}
