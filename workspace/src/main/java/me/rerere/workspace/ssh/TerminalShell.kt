package me.rerere.workspace.ssh

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * 终端后面带 PTY 的交互式 shell, 供终端会话使用。
 *
 * 远程工作区（[SshShell]，exec 通道 + 命令）和 SSH 主机终端
 * （`SshHostShell`，shell 通道）是两套不同的建连方式，但对终端来说
 * 都是同一个形态：输出流、输入流、尺寸调整、等待退出、关闭。
 * 两个 transport（`ShellTerminalTransport`）只认这个接口，不认具体实现。
 *
 * [inputStream] 是 shell 的输出（PTY 已合并 stderr），[outputStream] 写入 shell 的输入。
 * 除这两个流外的方法都会走网络，不能在主线程调用。
 */
interface TerminalShell : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream

    /** shell 结束时 SSH 连接是否已经断开，用来区分正常退出和掉线 */
    val connectionLost: Boolean

    fun resize(columns: Int, rows: Int)

    /** 阻塞到 shell 结束，返回退出码；没收到退出码（掉线、被信号结束或被 [close]）时返回 null */
    fun waitFor(): Int?
}
