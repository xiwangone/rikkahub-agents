package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.ui.pages.extensions.workspace.ShellTerminalTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for the [ShellTerminalTransport] state machine: start, resize
 * forwarding, exit-status mapping and kill-before-start handling.
 *
 * The transport only talks to a [me.rerere.workspace.ssh.TerminalShell], so the shell
 * behind it is a fake built on piped streams; opening a real shell needs a live
 * SSH server and is verified on-device.
 */
class ShellTerminalTransportTest {

    private class FakeShell(
        status: Int? = 0,
        connectionLost: Boolean = false,
    ) {
        val input = PipedInputStream()
        val output = PipedOutputStream()
        val closed = AtomicBoolean(false)
        val resizes = mutableListOf<Pair<Int, Int>>()
        val resizeLatch = CountDownLatch(1)
        val closeCalls = AtomicInteger(0)
        private val statusValue = status
        private val lostValue = connectionLost

        fun shell() = SshHostShell(
            inputStream = input,
            outputStream = output,
            channel = object : ShellChannel {
                override fun isClosed(): Boolean = true
                override fun exitStatus(): Int = statusValue ?: -1
                override fun resize(columns: Int, rows: Int) {
                    synchronized(resizes) { resizes += columns to rows }
                    resizeLatch.countDown()
                }
                override fun close() {
                    if (closed.compareAndSet(false, true)) closeCalls.incrementAndGet()
                }
                override fun isConnectionLost(): Boolean = lostValue
            },
        )
    }

    private fun transport(
        fake: FakeShell,
        openCalls: AtomicInteger = AtomicInteger(0),
    ) = ShellTerminalTransport("user@host:22") { _, _ ->
        openCalls.incrementAndGet()
        fake.shell()
    }

    @Test
    fun `start opens the shell and exposes its streams`() {
        val fake = FakeShell()
        val t = transport(fake)
        t.start(80, 24)
        assertSame(fake.input, t.inputStream)
        assertSame(fake.output, t.outputStream)
        t.close()
    }

    @Test
    fun `streams before start fail`() {
        val t = transport(FakeShell())
        try {
            t.inputStream
            fail("expected an error")
        } catch (_: IllegalStateException) {
            // expected: the shell is not started yet
        }
        t.close()
    }

    @Test
    fun `setWindowSize before start is a no-op`() {
        val fake = FakeShell()
        val t = transport(fake)
        t.setWindowSize(100, 30)
        t.close()
        assertTrue(fake.resizes.isEmpty())
    }

    @Test
    fun `setWindowSize after start forwards the resize`() {
        val fake = FakeShell()
        val t = transport(fake)
        t.start(80, 24)
        t.setWindowSize(120, 40)
        assertTrue(fake.resizeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(120 to 40), fake.resizes)
        t.close()
    }

    @Test
    fun `waitFor returns the shell exit code`() {
        val t = transport(FakeShell(status = 2))
        t.start(80, 24)
        assertEquals(2, t.waitFor())
        t.close()
    }

    @Test
    fun `waitFor without an exit code returns minus one`() {
        val t = transport(FakeShell(status = null))
        t.start(80, 24)
        assertEquals(-1, t.waitFor())
        t.close()
    }

    @Test
    fun `describeExit reports a lost connection`() {
        val t = transport(FakeShell(status = null, connectionLost = true))
        t.start(80, 24)
        t.waitFor()
        assertEquals("Connection lost", t.describeExit(-1))
        t.close()
    }

    @Test
    fun `describeExit reports a plain close when the connection is fine`() {
        val t = transport(FakeShell(status = null, connectionLost = false))
        t.start(80, 24)
        t.waitFor()
        assertEquals("Session closed", t.describeExit(-1))
        t.close()
    }

    @Test
    fun `describeExit falls back to the default text for a real exit code`() {
        val t = transport(FakeShell(status = 0))
        t.start(80, 24)
        t.waitFor()
        assertEquals("Process completed - press Enter", t.describeExit(0))
        t.close()
    }

    @Test
    fun `describeStart names the connection target`() {
        val t = transport(FakeShell())
        assertEquals("Connecting to user@host:22...", t.describeStart())
        t.close()
    }

    @Test
    fun `kill before start closes the shell once it opens and fails start`() {
        val fake = FakeShell()
        val openCalls = AtomicInteger(0)
        val t = transport(fake, openCalls)
        t.kill()
        try {
            t.start(80, 24)
            fail("expected start to fail after kill")
        } catch (_: IOException) {
            // expected: the terminal was closed while connecting
        }
        assertEquals(1, openCalls.get())
        assertTrue(fake.closed.get())
        t.close()
    }

    @Test
    fun `kill after start closes the shell`() {
        val fake = FakeShell()
        val t = transport(fake)
        t.start(80, 24)
        t.kill()
        val deadline = System.currentTimeMillis() + 5_000
        while (!fake.closed.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue(fake.closed.get())
        t.close()
    }
}
