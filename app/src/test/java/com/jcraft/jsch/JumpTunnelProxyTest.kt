package com.jcraft.jsch

import me.rerere.rikkahub.data.ai.tools.local.JumpTunnelProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * [JumpTunnelProxy] 的建连顺序与失败清理。
 *
 * 放在 com.jcraft.jsch 包里：JSch 的 Session 与 ChannelDirectTCPIP 构造器是
 * 包内可见，测试要造一个可控的假跳板会话与假通道只能待在这个包。测试不走
 * 网络：假会话的 openChannel 直接返回假通道，假通道的 connect 按需成功或
 * 抛 [JSchException]。
 */
class JumpTunnelProxyTest {

    /** 记录调用顺序与断开的假 direct-tcpip 通道。 */
    private class FakeChannel(
        private val connectFailure: JSchException? = null,
    ) : ChannelDirectTCPIP() {
        val calls = mutableListOf<String>()
        var disconnected = false
        val fakeInput: InputStream = ByteArrayInputStream(ByteArray(0))
        val fakeOutput: OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream {
            calls += "getInputStream"
            return fakeInput
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

    /** openChannel 永远返回假通道的假跳板会话。 */
    private class FakeSession(
        private val channel: ChannelDirectTCPIP,
    ) : Session(JSch(), "jump", "jump.example", 22) {
        var disconnected = false

        override fun openChannel(type: String): Channel = channel

        override fun disconnect() {
            disconnected = true
        }
    }

    @Test
    fun `streams are taken before connect`() {
        val channel = FakeChannel()
        val session = FakeSession(channel)
        val proxy = JumpTunnelProxy(session, "target.example", 22)

        proxy.connect(null, "target.example", 22, 10_000)

        assertTrue(
            "input stream must be taken before connect, was: ${channel.calls}",
            channel.calls.indexOf("getInputStream") < channel.calls.indexOf("connect"),
        )
        assertTrue(
            "output stream must be taken before connect, was: ${channel.calls}",
            channel.calls.indexOf("getOutputStream") < channel.calls.indexOf("connect"),
        )
        assertSame(channel.fakeInput, proxy.inputStream)
        assertSame(channel.fakeOutput, proxy.outputStream)
        assertEquals(listOf("getInputStream", "getOutputStream", "connect"), channel.calls)

        proxy.close()
        assertTrue(channel.disconnected)
        assertTrue(session.disconnected)
    }

    @Test
    fun `connect failure disconnects channel and rethrows`() {
        val channel = FakeChannel(JSchException("simulated open failure"))
        val session = FakeSession(channel)
        val proxy = JumpTunnelProxy(session, "target.example", 22)

        assertThrows(JSchException::class.java) {
            proxy.connect(null, "target.example", 22, 10_000)
        }

        assertTrue("failed channel must be disconnected, was: ${channel.calls}", channel.disconnected)
        assertEquals(
            listOf("getInputStream", "getOutputStream", "connect", "disconnect"),
            channel.calls,
        )
        // 失败后代理仍报告"未连接"，不留半开通道的流引用。
        assertThrows(IllegalStateException::class.java) { proxy.inputStream }
        assertThrows(IllegalStateException::class.java) { proxy.outputStream }
    }

    @Test
    fun `close after failed connect is safe`() {
        val channel = FakeChannel(JSchException("simulated open failure"))
        val session = FakeSession(channel)
        val proxy = JumpTunnelProxy(session, "target.example", 22)

        assertThrows(JSchException::class.java) {
            proxy.connect(null, "target.example", 22, 10_000)
        }
        proxy.close()

        assertTrue(session.disconnected)
    }
}
