package me.rerere.rikkahub.data.tunnel

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * OkHttp [SocketFactory] 包装：当内嵌 Tailscale 隧道处于运行状态且连接目标属于
 * Tailnet（`100.64.0.0/10`、`fd7a:115c:a1e0::/48` 或 `*.ts.net` MagicDNS）时，
 * 自动通过 [TsnetBridge.proxyToBlocking] 映射到 `127.0.0.1:<localPort>` 建立连接。
 *
 * 隧道未开启或目标为常规公网/局域网地址时零开销直通底层默认 [SocketFactory]。
 */
class TsnetSocketFactory(
    private val delegate: SocketFactory = SocketFactory.getDefault(),
) : SocketFactory() {

    override fun createSocket(): Socket = TsnetRoutingSocket()

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = createSocket().apply {
        bind(InetSocketAddress(localHost, localPort))
        connect(InetSocketAddress(host, port))
    }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = createSocket().apply {
        bind(InetSocketAddress(localAddress, localPort))
        connect(InetSocketAddress(address, port))
    }

    private class TsnetRoutingSocket : Socket() {
        @Throws(IOException::class)
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            if (TsnetBridge.isRunning && endpoint is InetSocketAddress) {
                val hostName = endpoint.hostString?.trim().orEmpty()
                val ipLiteral = endpoint.address?.hostAddress?.substringBefore('%').orEmpty()
                val target = when {
                    isTailnetMagicDnsHost(hostName) -> hostName
                    TsnetBridge.isTailnetAddress(ipLiteral) -> ipLiteral
                    TsnetBridge.isTailnetAddress(hostName) -> hostName
                    else -> null
                }
                if (target != null) {
                    val localPort = TsnetBridge.proxyToBlocking(target, endpoint.port)
                    if (localPort != null && localPort > 0) {
                        super.connect(InetSocketAddress("127.0.0.1", localPort), timeout)
                        return
                    }
                }
            }
            super.connect(endpoint, timeout)
        }
    }
}
