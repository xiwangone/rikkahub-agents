package me.rerere.rikkahub.ui.pages.setting

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import me.rerere.workspace.ssh.TerminalShell

/**
 * The channel behind an interactive shell: the terminal drives it through
 * this interface, so [SshHostShell] only depends on the standard library and
 * stays unit-testable without a live SSH server.
 */
interface ShellChannel {
    fun isClosed(): Boolean
    fun exitStatus(): Int
    fun resize(columns: Int, rows: Int)
    fun close()
    fun isConnectionLost(): Boolean
}

/**
 * Interactive shell on a remote host, backed by a PTY channel.
 *
 * [inputStream] carries the shell's output (the PTY merges stderr into it);
 * bytes written to [outputStream] go to the shell's input. [close] ends the
 * shell. The remaining methods may use the network and must not run on the
 * main thread.
 */
class SshHostShell(
    override val inputStream: InputStream,
    override val outputStream: OutputStream,
    private val channel: ShellChannel,
) : Closeable, TerminalShell {
    private val closed = AtomicBoolean(false)

    /** The shell ended while the SSH connection was already down, as opposed to a clean exit. */
    override val connectionLost: Boolean
        get() = channel.isConnectionLost()

    override fun resize(columns: Int, rows: Int) = channel.resize(columns, rows)

    /** Blocks until the shell ends; returns the exit code, or null when none arrived. */
    override fun waitFor(): Int? {
        while (!channel.isClosed()) Thread.sleep(POLL_INTERVAL_MS)
        return channel.exitStatus().takeIf { it >= 0 }
    }

    /** Ends the shell; closing twice is a no-op. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channel.close()
    }

    companion object {
        const val POLL_INTERVAL_MS = 50L
    }
}
