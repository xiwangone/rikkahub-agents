package com.jcraft.jsch

import me.rerere.workspace.ssh.JumpHostProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * [JumpHostProxy] 的建连顺序与失败清理。
 *
 * 放在 com.jcraft.jsch 包里：JSch 的 Session 与 ChannelDirectTCPIP 构造器是
 * 包内可见，测试要造一个可控的假跳板会话与假通道只能待在这个包。测试不走
 * 网络：假会话的 openChannel 直接返回假通道，假通道的 connect 按需成功或
 * 抛 [JSchException]。
 *
 * 与已删的 JumpTunnelProxyTest 的区别：新实现不取通道的输入/输出流，
 * 改为 connect 之前先 setOutputStream（接收管道的 sink），connect 成功后才
 * 取 outputStream；失败后同样断开通道、不留半开引用。
 */
class JumpHostProxyTest {

    /** 记录调用顺序与断开的假 direct-tcpip 通道。 */
    private class FakeChannel(
        private val connectFailure: JSchException? = null,
    ) : ChannelDirectTCPIP() {
        val calls = mutableListOf<String>()
        var disconnected = false
        val fakeOutput: OutputStream = ByteArrayOutputStream()

        override fun setOutputStream(out: OutputStream) {
            calls += "setOutputStream"
        }

        override fun getOutputStream(): OutputStream {
            calls += "getOutputStream"
            return fakeOutput
        }

        override fun connect(timeout: Int) {
            calls += "connect"
            connectFailure?.let { throw it }
        }

        override fun disconnect() {
            calls += "disconnect"
            disconnected = true
        }
    }

    /** openChannel 永远返回假通道的假跳板会话；按需让 openChannel 抛错。 */
    private class FakeSession(
        private val channel: ChannelDirectTCPIP,
        private val openChannelFailure: JSchException? = null,
    ) : Session(JSch(), "jump", "jump.example", 22) {
        var disconnected = false

        override fun openChannel(type: String): Channel {
            openChannelFailure?.let { throw it }
            return channel
        }

        override fun disconnect() {
            disconnected = true
        }
    }

    @Test
    fun `sink is set before connect and output stream taken after`() {
        val channel = FakeChannel()
        val session = FakeSession(channel)
        val proxy = JumpHostProxy(session, 10_000L)

        proxy.connect(null, "target.example", 22, 10_000)

        assertEquals(
            listOf("setOutputStream", "connect", "getOutputStream"),
            channel.calls,
        )
        assertSame(channel.fakeOutput, proxy.outputStream)

        proxy.close()
        assertTrue(channel.disconnected)
        assertTrue(session.disconnected)
    }

    @Test
    fun `connect failure disconnects channel and rethrows`() {
        val channel = FakeChannel(JSchException("simulated open failure"))
        val session = FakeSession(channel)
        val proxy = JumpHostProxy(session, 10_000L)

        val thrown = assertThrows(JSchException::class.java) {
            proxy.connect(null, "target.example", 22, 10_000)
        }

        assertTrue(
            "failure must be wrapped with jump context, was: ${thrown.message}",
            thrown.message?.contains("jump host could not open a tunnel to target.example:22") == true,
        )
        assertTrue("failed channel must be disconnected, was: ${channel.calls}", channel.disconnected)
        assertEquals(
            listOf("setOutputStream", "connect", "disconnect"),
            channel.calls,
        )
        // 失败后代理仍报告“未连接”，不留半开通道的流引用。
        assertThrows(IOException::class.java) { proxy.outputStream }
    }

    @Test
    fun `getStreamForwarder failure is wrapped with jump context`() {
        val session = FakeSession(FakeChannel(), JSchException("session is down"))
        val proxy = JumpHostProxy(session, 10_000L)

        val thrown = assertThrows(JSchException::class.java) {
            proxy.connect(null, "target.example", 22, 10_000)
        }

        assertTrue(
            "failure must be wrapped with jump context, was: ${thrown.message}",
            thrown.message?.contains("jump host could not open a tunnel to target.example:22") == true,
        )
        assertThrows(IOException::class.java) { proxy.outputStream }
    }

    @Test
    fun `close after failed connect is safe`() {
        val channel = FakeChannel(JSchException("simulated open failure"))
        val session = FakeSession(channel)
        val proxy = JumpHostProxy(session, 10_000L)

        assertThrows(JSchException::class.java) {
            proxy.connect(null, "target.example", 22, 10_000)
        }
        proxy.close()

        assertTrue(session.disconnected)
    }
}
