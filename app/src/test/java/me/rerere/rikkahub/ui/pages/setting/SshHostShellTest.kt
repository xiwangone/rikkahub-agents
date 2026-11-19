package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for the [SshHostShell] wrapper contract: close idempotence,
 * exit-code reporting, resize forwarding and connection-loss delegation.
 *
 * The live channel behind it needs a real SSH server and is verified
 * on-device; these tests only cover the wrapper itself.
 */
class SshHostShellTest {

    private fun fakeShell(
        closed: AtomicBoolean = AtomicBoolean(true),
        status: Int = 0,
        connectionLost: Boolean = false,
    ): Triple<SshHostShell, AtomicInteger, AtomicReference<Pair<Int, Int>>> {
        val closeCalls = AtomicInteger(0)
        val resizes = AtomicReference<Pair<Int, Int>>(null)
        val channel = object : ShellChannel {
            override fun isClosed(): Boolean = closed.get()
            override fun exitStatus(): Int = status
            override fun resize(columns: Int, rows: Int) {
                resizes.set(columns to rows)
            }
            override fun close() {
                closeCalls.incrementAndGet()
            }
            override fun isConnectionLost(): Boolean = connectionLost
        }
        val shell = SshHostShell(
            inputStream = PipedInputStream(),
            outputStream = PipedOutputStream(),
            channel = channel,
        )
        return Triple(shell, closeCalls, resizes)
    }

    @Test
    fun `close runs the close callback exactly once`() {
        val (shell, closeCalls, _) = fakeShell()
        shell.close()
        shell.close()
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun `waitFor returns the exit code once the shell is closed`() {
        val (shell, _, _) = fakeShell(status = 3)
        assertEquals(3, shell.waitFor())
    }

    @Test
    fun `waitFor returns null when no exit code arrived`() {
        val (shell, _, _) = fakeShell(status = -1)
        assertNull(shell.waitFor())
    }

    @Test
    fun `waitFor blocks until the shell closes`() {
        val closed = AtomicBoolean(false)
        val (shell, _, _) = fakeShell(closed = closed, status = 0)
        Thread {
            Thread.sleep(200)
            closed.set(true)
        }.start()
        val startedAt = System.currentTimeMillis()
        assertEquals(0, shell.waitFor())
        assertTrue(System.currentTimeMillis() - startedAt >= 150)
    }

    @Test
    fun `resize forwards columns and rows`() {
        val (shell, _, resizes) = fakeShell()
        shell.resize(120, 40)
        assertEquals(120 to 40, resizes.get())
    }

    @Test
    fun `connectionLost delegates to the connection state`() {
        val (lostShell, _, _) = fakeShell(connectionLost = true)
        val (cleanShell, _, _) = fakeShell(connectionLost = false)
        assertTrue(lostShell.connectionLost)
        assertFalse(cleanShell.connectionLost)
    }

    @Test
    fun `streams are the ones handed to the shell`() {
        val input = PipedInputStream()
        val output = PipedOutputStream()
        val channel = object : ShellChannel {
            override fun isClosed(): Boolean = true
            override fun exitStatus(): Int = 0
            override fun resize(columns: Int, rows: Int) = Unit
            override fun close() = Unit
            override fun isConnectionLost(): Boolean = false
        }
        val shell = SshHostShell(
            inputStream = input,
            outputStream = output,
            channel = channel,
        )
        assertSame(input, shell.inputStream)
        assertSame(output, shell.outputStream)
    }
}
