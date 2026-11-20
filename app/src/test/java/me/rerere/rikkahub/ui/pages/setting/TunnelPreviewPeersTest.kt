package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡片预览排序（[List.previewPeers]）：只列在线、最近活跃排前、再按 IP、取前 5 台。
 */
class TunnelPreviewPeersTest {

    private fun peer(
        name: String,
        ip: String,
        online: Boolean = true,
        active: Boolean = true,
        os: String = "",
    ) = TunnelPeer(
        hostName = name,
        dnsName = "$name.example.ts.net",
        ips = listOf(ip),
        online = online,
        canBeExitNode = false,
        active = active,
        os = os,
    )

    private fun self() = TunnelPeer(
        hostName = "yao",
        dnsName = "yao.example.ts.net",
        ips = listOf("192.0.2.0.9"),
        online = true,
        canBeExitNode = false,
        active = true,
        os = "android",
        isSelf = true,
    )

    @Test
    fun `active peers come first regardless of IP`() {
        val peers = listOf(
            peer("idle-low", "192.0.2.0.2", active = false),
            peer("active-high", "192.0.2.0.10", active = true),
            peer("active-low", "192.0.2.0.3", active = true),
        )
        assertEquals(
            listOf("active-low", "active-high", "idle-low"),
            peers.previewPeers().map { it.hostName },
        )
    }

    @Test
    fun `offline peers are excluded`() {
        val peers = listOf(
            peer("offline-active", "192.0.2.0.1", online = false, active = true),
            peer("online", "192.0.2.0.2"),
        )
        assertEquals(listOf("online"), peers.previewPeers().map { it.hostName })
    }

    @Test
    fun `IP order is numeric not lexicographic`() {
        val peers = listOf(
            peer("p10", "192.0.2.0.10"),
            peer("p9", "192.0.2.0.9"),
            peer("p2", "192.0.2.0.2"),
        )
        assertEquals(listOf("p2", "p9", "p10"), peers.previewPeers().map { it.hostName })
    }

    @Test
    fun `at most five peers are returned`() {
        val peers = (1..7).map { peer("p$it", "192.0.2.0.$it") }
        val result = peers.previewPeers()
        assertEquals(5, result.size)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), result.map { it.hostName })
    }

    @Test
    fun `peers without an IPv4 address sort last`() {
        val peers = listOf(
            peer("no-ip", ""),
            peer("p1", "192.0.2.0.1"),
        )
        assertEquals(listOf("p1", "no-ip"), peers.previewPeers().map { it.hostName })
    }

    @Test
    fun `dialog second line appends OS when present`() {
        assertEquals(
            "aaa  192.0.2.0.14 · linux",
            peer("aaa", "192.0.2.0.14", os = "linux").dialogSecondLine(),
        )
    }

    @Test
    fun `dialog second line without OS matches old output`() {
        assertEquals(
            "aaa  192.0.2.0.14",
            peer("aaa", "192.0.2.0.14").dialogSecondLine(),
        )
    }

    @Test
    fun `dialog second line without dnsName is IP plus OS`() {
        val noDns = TunnelPeer(
            hostName = "aaa",
            dnsName = "",
            ips = listOf("192.0.2.0.14"),
            online = true,
            canBeExitNode = false,
            os = "windows",
        )
        assertEquals("192.0.2.0.14 · windows", noDns.dialogSecondLine())
        assertEquals("192.0.2.0.14", noDns.copy(os = "").dialogSecondLine())
    }

    @Test
    fun `dialog second line is empty when nothing is known`() {
        val empty = TunnelPeer(
            hostName = "",
            dnsName = "",
            ips = emptyList(),
            online = false,
            canBeExitNode = false,
        )
        assertEquals("", empty.dialogSecondLine())
    }

    @Test
    fun `self peer is built from status`() {
        val status = TunnelStatus(
            running = true,
            selfIps = listOf("192.0.2.0.9"),
            dnsName = "yao.example.ts.net",
            hostName = "yao",
            peers = emptyList(),
        )
        val self = selfPeer(status)
        assertEquals("yao", self?.hostName)
        assertEquals(listOf("192.0.2.0.9"), self?.ips)
        assertEquals("android", self?.os)
        assertEquals(true, self?.isSelf)
        assertEquals(true, self?.online)
    }

    @Test
    fun `self peer is null without status or identity`() {
        assertEquals(null, selfPeer(null))
        assertEquals(null, selfPeer(TunnelStatus()))
    }

    @Test
    fun `dialog peers pin self first and keep IP order`() {
        val peers = listOf(
            peer("p10", "192.0.2.0.10"),
            peer("p2", "192.0.2.0.2"),
        )
        val result = dialogPeers(peers, self())
        assertEquals(listOf("yao", "p2", "p10"), result.map { it.hostName })
        assertEquals(true, result.first().isSelf)
    }

    @Test
    fun `dialog peers without self keep existing order`() {
        val peers = listOf(
            peer("p10", "192.0.2.0.10"),
            peer("p2", "192.0.2.0.2"),
        )
        assertEquals(listOf("p2", "p10"), dialogPeers(peers, null).map { it.hostName })
    }

    @Test
    fun `dialog peers with only self returns just self`() {
        assertEquals(listOf("yao"), dialogPeers(emptyList(), self()).map { it.hostName })
    }
}
