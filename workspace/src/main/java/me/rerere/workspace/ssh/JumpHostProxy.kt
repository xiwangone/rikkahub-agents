package me.rerere.workspace.ssh

import com.jcraft.jsch.Channel as JschChannel
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory as JschSocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 跳板隧道代理（SSH 主机与 SSH 工作区共用的唯一实现）。
 *
 * 经一个已连的跳板会话把目标 host:port 的字节流串起来；隧道没有 socket，JSch 设不了读超时，
 * 所以接收管道自带 [readTimeoutMillis] 读超时：握手时目标不回应会失败而不是挂死，连上后失联能触发 keepalive。
 * 生命周期：close() 会一并断开跳板会话。
 */
class JumpHostProxy(private val jumpSession: Session, readTimeoutMillis: Long) : Proxy {
    private val input = TunnelInputStream(readTimeoutMillis)

    @Volatile
    private var channel: JschChannel? = null

    @Volatile
    private var output: OutputStream? = null

    override fun connect(socketFactory: JschSocketFactory?, host: String, port: Int, timeout: Int) {
        val channel = jumpSession.getStreamForwarder(host, port)
        this.channel = channel
        // 收到的数据写进管道；必须在 connect 之前设置，否则开头的数据会丢
        channel.setOutputStream(input.sink)
        try {
            channel.connect(timeout)
        } catch (e: JSchException) {
            // 常见原因：跳板机连不到目标主机，或者 sshd 设置了 AllowTcpForwarding no
            throw JSchException("jump host could not open a tunnel to $host:$port (${e.message})", e)
        }
        output = channel.outputStream
    }

    override fun getInputStream(): InputStream = input

    override fun getOutputStream(): OutputStream = output ?: throw IOException("Tunnel is not connected")

    override fun getSocket(): Socket? = null

    override fun close() {
        channel?.disconnect()
        jumpSession.disconnect()
        input.close()
    }
}

private class TunnelInputStream(private val readTimeoutMillis: Long) : InputStream() {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray = EMPTY
    private var position = 0

    @Volatile
    private var closed = false

    val sink: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Tunnel closed")
            if (len > 0) chunks.put(b.copyOfRange(off, off + len))
        }

        override fun close() = this@TunnelInputStream.close()
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position == current.size) {
            current = nextChunk() ?: return -1
            position = 0
        }
        val count = minOf(len, current.size - position)
        System.arraycopy(current, position, b, off, count)
        position += count
        return count
    }

    override fun available(): Int = current.size - position

    override fun close() {
        closed = true
        chunks.offer(END)
    }

    private fun nextChunk(): ByteArray? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMillis)
        var interrupted = false
        try {
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw SocketTimeoutException("Read timed out")
                val chunk = try {
                    chunks.poll(remaining, TimeUnit.NANOSECONDS)
                } catch (e: InterruptedException) {
                    interrupted = true
                    continue
                } ?: continue
                if (chunk === END) {
                    // 之后的读取同样返回 EOF
                    chunks.offer(END)
                    return null
                }
                return chunk
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
        val END = ByteArray(0)
    }
}
