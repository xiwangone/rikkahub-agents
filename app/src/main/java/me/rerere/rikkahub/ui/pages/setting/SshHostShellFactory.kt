package me.rerere.rikkahub.ui.pages.setting

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session

/**
 * Opens an interactive login shell on an already connected [session].
 *
 * The "shell" channel (rather than an exec channel with a guessed shell
 * command) lets the server start the user's login shell, which works the
 * same on Linux and Windows hosts. The streams are taken before connect,
 * otherwise the first output is lost.
 */
fun openSshHostShell(session: Session, columns: Int, rows: Int): SshHostShell {
    val channel = session.openChannel("shell") as ChannelShell
    channel.setPty(true)
    channel.setPtyType(TERMINAL_TYPE, columns, rows, 0, 0)
    channel.setTerminalMode(TERMINAL_MODES)
    val input = channel.inputStream
    val output = channel.outputStream
    channel.connect(CHANNEL_CONNECT_TIMEOUT_MS)
    return SshHostShell(
        inputStream = input,
        outputStream = output,
        channel = object : ShellChannel {
            override fun isClosed(): Boolean = channel.isClosed
            override fun exitStatus(): Int = channel.exitStatus
            override fun resize(columns: Int, rows: Int) {
                runCatching { channel.setPtySize(columns, rows, 0, 0) }
            }
            override fun close() {
                runCatching { channel.disconnect() }
            }
            override fun isConnectionLost(): Boolean = !session.isConnected
        },
    )
}

// Same terminal type as the remote workspace shell, so color programs behave alike.
private const val TERMINAL_TYPE = "xterm-256color"
private const val CHANNEL_CONNECT_TIMEOUT_MS = 10_000

// RFC 4254 PTY modes: IUTF8 (RFC 8160 opcode 42) on, so line editing deletes
// whole UTF-8 characters; ends with TTY_OP_END.
private val TERMINAL_MODES = byteArrayOf(42, 0, 0, 0, 1, 0)
