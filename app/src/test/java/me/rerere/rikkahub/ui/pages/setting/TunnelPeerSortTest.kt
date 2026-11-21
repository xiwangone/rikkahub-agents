package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 聊天页卡片预览排序：在线、active 优先、再按 IP，IP 平局时按域名/主机名再排一轮
 * （与 dialogPeers 同口径），否则回落 Go map 顺序、每次刷新都可能乱跳。
 */
class TunnelPeerSortTest {

    private fun peer(
        hostName: String,
        dnsName: String,
        ip: String,
        active: Boolean = true,
    ) = TunnelPeer(
        hostName = hostName,
        dnsName = dnsName,
        ips = listOf(ip),
        online = true,
        canBeExitNode = false,
        active = active,
    )

    @Test
    fun `previewPeers breaks IP ties by dnsName`() {
        val peers = listOf(
            peer("b", "b.ts.net", "192.0.2.9"),
            peer("a", "a.ts.net", "192.0.2.9"),
        )
        assertEquals(listOf("a.ts.net", "b.ts.net"), peers.previewPeers().map { it.dnsName })
    }

    @Test
    fun `previewPeers keeps active first then IP order`() {
        val peers = listOf(
            peer("idle", "idle.ts.net", "192.0.2.1", active = false),
            peer("b", "b.ts.net", "192.0.2.9"),
            peer("a", "a.ts.net", "192.0.2.2"),
        )
        assertEquals(
            listOf("a.ts.net", "b.ts.net", "idle.ts.net"),
            peers.previewPeers().map { it.dnsName },
        )
    }

    @Test
    fun `previewPeers only lists online peers and takes at most five`() {
        val peers = (1..7).map { peer("h$it", "h$it.ts.net", "192.0.2.$it") } +
            peer("offline", "offline.ts.net", "192.0.2.99").copy(online = false)
        assertEquals(5, peers.previewPeers().size)
        assertEquals(
            listOf("h1.ts.net", "h2.ts.net", "h3.ts.net", "h4.ts.net", "h5.ts.net"),
            peers.previewPeers().map { it.dnsName },
        )
    }
}
