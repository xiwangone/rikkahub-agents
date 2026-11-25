package me.rerere.rikkahub.ui.pages.setting

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.IOException

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
    try {
        channel.connect(CHANNEL_CONNECT_TIMEOUT_MS)
    } catch (e: JSchException) {
        runCatching { channel.disconnect() }
        if (isShellRejected(e.message)) {
            throw SshShellRejectedException(e)
        }
        throw e
    }
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

class SshShellRejectedException(cause: Throwable) : IOException(cause.message, cause)

/**
 * JSch 文案是否说明"服务端拒绝打开交互式 shell"。
 *
 * 三条原文均已按 mwiede jsch 2.28.8 实物核对（`Request.class` / `Channel.class`）：
 * - `failed to send channel request`：服务端回 failure 拒绝 shell 请求；
 * - `channel is not opened`：通道打开被拒；
 * - `channel request: timeout`：`Request.request()` 等不到服务端回包——
 *   认证已通过、会话已建立，只是不回 shell 请求；静默丢弃请求的服务端
 *   （仅 SFTP / 强制 command 的主机）正是本提示的目标用户。不纳入时这类
 *   主机只剩一句裸超时，看不出原因。
 *
 * 与普通网络超时可区分：建连/TCP 层面的超时是 `timeout: socket is not
 *   established`、`SocketTimeoutException` 等其它文案，不含本函数的匹配串。
 * 残留歧义：通道请求窗口内恰好遇到网络停顿也会命中；提示文案写的是"常见原因"
 *   而非定论，误判成本低。null 与空串一律不命中。
 */
internal fun isShellRejected(message: String?): Boolean {
    val msg = message.orEmpty()
    return msg.contains("failed to send channel request", ignoreCase = true) ||
        msg.contains("channel is not opened", ignoreCase = true) ||
        msg.contains("channel request: timeout", ignoreCase = true)
}
