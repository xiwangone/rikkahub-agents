package me.rerere.rikkahub.ui.pages.extensions.workspace

import com.termux.terminal.TerminalTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Unit tests for [WelcomeTerminalTransport]: the welcome text is reported as
 * the transport's start description, and everything else delegates through.
 */
class WelcomeTerminalTransportTest {
    private class FakeTransport : TerminalTransport {
        val input: InputStream = ByteArrayInputStream(ByteArray(0))
        val output: OutputStream = ByteArrayOutputStream()
        var started = false
        var killed = false

        override fun start(columns: Int, rows: Int) {
            started = true
        }

        override fun getInputStream(): InputStream = input

        override fun getOutputStream(): OutputStream = output

        override fun setWindowSize(columns: Int, rows: Int) {}

        override fun waitFor(): Int = 0

        override fun kill() {
            killed = true
        }

        override fun close() {}
    }

    @Test
    fun `describeStart returns the welcome text`() {
        val transport = WelcomeTerminalTransport(FakeTransport(), "Welcome to the workspace terminal")
        assertEquals("Welcome to the workspace terminal", transport.describeStart())
    }

    @Test
    fun `describeStart is empty when the wrapped transport has none`() {
        val transport = WelcomeTerminalTransport(FakeTransport(), "")
        assertEquals("", transport.describeStart())
    }

    @Test
    fun `start kill and streams delegate to the wrapped transport`() {
        val fake = FakeTransport()
        val transport = WelcomeTerminalTransport(fake, "welcome")
        transport.start(80, 24)
        assertTrue(fake.started)
        transport.kill()
        assertTrue(fake.killed)
        assertSame(fake.input, transport.getInputStream())
        assertSame(fake.output, transport.getOutputStream())
        assertEquals(0, transport.waitFor())
    }
}
