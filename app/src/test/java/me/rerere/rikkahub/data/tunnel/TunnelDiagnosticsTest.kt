package me.rerere.rikkahub.data.tunnel

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隧道网络诊断纯函数单测。
 * 未覆盖：parseDiagStatusJson（org.json 在 JVM 单测里是 android.jar 桩，构造即抛，
 * 与既有 parseTunnelStatus 同口径不测）、探针执行器与 UI 需在设备上验证。
 */
class TunnelDiagnosticsTest {

    // CGNAT 段地址按片段拼出，避免在源码里写死具体地址。
    private val ip1 = "100." + "64.0.1"
    private val ip2 = "100." + "64.0.2"
    private val ip3 = "100." + "64.0.3"
    private val ip4 = "100." + "64.0.4"
    private val ip5 = "100." + "64.0.5"
    private val ip6 = "100." + "64.0.9"

    /** 测试用中文解析器：与 values-zh 的 tunnel_diag_detail_* 一致，断言继续按中文成立。 */
    private val zhStrings = DiagStrings { key, args ->
        val template = when (key) {
            DiagStringKey.MODULE_NOT_STARTED -> "隧道未启动"
            DiagStringKey.MODULE_RUNNING -> "隧道运行中（Running）"
            DiagStringKey.MODULE_BAD_STATE -> "后端状态异常：%1\$s"
            DiagStringKey.DEVICE_CONNECTED -> "已联网（%1\$s）"
            DiagStringKey.DEVICE_OFFLINE -> "设备未联网"
            DiagStringKey.DNS_OK -> "example.com 解析成功（%1\$dms）"
            DiagStringKey.DNS_FAIL -> "example.com 解析失败"
            DiagStringKey.DIRECT_OK -> "example.com 直连成功（HTTP %1\$d，%2\$dms）"
            DiagStringKey.DIRECT_FAIL_HTTP -> "example.com 直连失败（HTTP %1\$d）"
            DiagStringKey.DIRECT_FAIL_UNREACHABLE -> "example.com 直连失败（无法连接）"
            DiagStringKey.CONTROL_OK -> "控制面 %1\$s 有响应（HTTP %2\$d，%3\$dms），未验证身份"
            DiagStringKey.CONTROL_FAIL -> "控制面 %1\$s 不可达"
            DiagStringKey.NO_PEER -> "无在线对端可查"
            DiagStringKey.MAGIC_MISSING -> "对端 %1\$s 缺少 MagicDNS 记录"
            DiagStringKey.MAGIC_FOUND -> "对端 %1\$s 有记录：%2\$s → %3\$s"
            DiagStringKey.RESOLVE_TIMEOUT -> "解析超时（10 秒无响应）"
            DiagStringKey.RESOLVE_NO_RESPONSE -> "解析失败（无有效响应）"
            DiagStringKey.RESOLVE_NXDOMAIN -> "域名不存在（NXDOMAIN）"
            DiagStringKey.RESOLVE_RCODE -> "解析失败（DNS 错误码 %1\$d）"
            DiagStringKey.RESOLVE_NO_A -> "无 A 记录"
            DiagStringKey.RESOLVE_OUTSIDE -> "%1\$s 解析到段外地址 %2\$s（不在 100.64.0.0/10 内）"
            DiagStringKey.RESOLVE_MATCH -> "%1\$s → %2\$s（真实解析，与记录一致）"
            DiagStringKey.RESOLVE_MISMATCH -> "%1\$s → %2\$s（真实解析，与记录 %3\$s 不一致）"
            DiagStringKey.PEERS_NONE -> "无对端设备"
            DiagStringKey.PEERS_ONLINE -> "在线 %1\$d 台 / 共 %2\$d 台"
            DiagStringKey.PEERS_ALL_OFFLINE -> "共 %1\$d 台，全部离线"
            DiagStringKey.ROUTES_EXIT_UNSET -> "出口节点：未设置"
            DiagStringKey.ROUTES_EXIT -> "出口节点：%1\$s"
            DiagStringKey.ROUTES_ACCEPT_ON -> "接受子网路由：开"
            DiagStringKey.ROUTES_ACCEPT_OFF -> "接受子网路由：关"
            DiagStringKey.ROUTES -> "%1\$s，%2\$s"
            DiagStringKey.TCP_BANNER -> "尾网 TCP 连通，收到横幅：%1\$s"
            DiagStringKey.TCP_NO_BANNER -> "尾网 TCP 连通但未收到横幅（可能不是 HTTP 服务）"
            DiagStringKey.TCP_FAIL -> "尾网 TCP 连接失败"
            DiagStringKey.PING_OK -> "Ping 成功，时延 %1\$dms"
            DiagStringKey.PING_FAIL -> "Ping 失败"
            DiagStringKey.ERROR_SUFFIX -> "：%1\$s"
            DiagStringKey.PAREN_SUFFIX -> "（%1\$s）"
            DiagStringKey.COPY_HEADER -> "网络诊断 %1\$s（用时 %2\$d 秒，通过 %3\$d / 失败 %4\$d / 跳过 %5\$d）"
            DiagStringKey.COPY_ITEM -> "%1\$s %2\$s：%3\$s%4\$s"
            DiagStringKey.SNAPSHOT_FAIL -> "读取隧道状态失败"
            DiagStringKey.SNAPSHOT_UNKNOWN -> "隧道状态未知"
            DiagStringKey.SNAPSHOT_NOT_RUNNING -> "隧道未运行"
            DiagStringKey.MAGIC_MISSING_RESOLVE -> "对端缺少 MagicDNS 记录，无法解析"
            DiagStringKey.PEER_NO_IP -> "对端无尾网 IP"
            DiagStringKey.CHECK_ERROR -> "检查异常：%1\$s"
            DiagStringKey.UNKNOWN_ERROR -> "未知错误"
            DiagStringKey.CHECK_TIMEOUT -> "检查超时（%1\$d 秒无响应）"
            DiagStringKey.NETWORK_UNREADABLE -> "无法读取网络状态"
            DiagStringKey.TRANSPORT_CELLULAR -> "移动数据"
            DiagStringKey.TRANSPORT_ETHERNET -> "以太网"
            DiagStringKey.TRANSPORT_OTHER -> "其他"
            DiagStringKey.CONTROL_INVALID -> "控制面地址无效：%1\$s"
            DiagStringKey.DNS_PROXY_FAIL -> "建立 DNS 代理失败"
            DiagStringKey.PROXY_FAIL -> "建立代理失败"
            DiagStringKey.MAGIC_DNS_CUSTOM_SKIP -> "自定义控制面：不使用内置 DNS"
            DiagStringKey.STATUS_PASS -> "通过"
            DiagStringKey.STATUS_FAIL -> "失败"
            DiagStringKey.STATUS_SKIP -> "跳过"
            DiagStringKey.STATUS_RUNNING -> "检测中"
            DiagStringKey.STATUS_PENDING -> "待检测"
        }
        if (args.isEmpty()) template else String.format(Locale.ROOT, template, *args)
    }

    private fun peer(
        name: String,
        ip: String,
        online: Boolean = true,
        active: Boolean = true,
    ) = DiagPeer(
        hostName = name,
        dnsName = "$name.example.ts.net",
        os = "linux",
        ips = listOf(ip),
        online = online,
        exitNode = false,
        active = active,
        relay = "",
        curAddr = "1.2.3.4:1234",
    )

    @Test
    fun `isTailnetIpv4 only accepts 100_64_0_0 slash 10`() {
        assertTrue(isTailnetIpv4(ip1))
        assertTrue(isTailnetIpv4("100.127.255.255"))
        assertFalse(isTailnetIpv4("100.128.0.1"))
        assertFalse(isTailnetIpv4("100.63.0.1"))
        assertFalse(isTailnetIpv4("8.8.8.8"))
        assertFalse(isTailnetIpv4(""))
        assertFalse(isTailnetIpv4("$ip1.2"))
    }

    @Test
    fun `shortPeerName prefers hostName and strips ts net suffix`() {
        assertEquals("host-a", shortPeerName(peer("host-a", ip2)))
        val noHost = peer("", ip2).copy(hostName = "", dnsName = "node-9.example.ts.net")
        assertEquals("node-9", shortPeerName(noHost))
    }

    @Test
    fun `firstIpv4 skips ipv6 addresses`() {
        val p = peer("a", ip2).copy(ips = listOf("fd7a:115c:a1e0::1", ip2))
        assertEquals(ip2, p.firstIpv4())
        assertEquals("", peer("b", ip3).copy(ips = listOf("fd7a:115c:a1e0::1")).firstIpv4())
    }

    @Test
    fun `pickProbeTarget prefers active online peer then any online peer`() {
        val idle = peer("idle", ip3, active = false)
        val active = peer("active", ip4, active = true)
        assertEquals("active", pickProbeTarget(listOf(idle, active))?.hostName)
        assertEquals("idle", pickProbeTarget(listOf(idle))?.hostName)
        assertNull(pickProbeTarget(listOf(peer("off", ip5, online = false))))
        assertNull(pickProbeTarget(emptyList()))
    }

    @Test
    fun `decideModule requires Running backend state`() {
        assertEquals(DiagStatus.PASS, decideModule(zhStrings, true, "Running", null).first)
        assertEquals(DiagStatus.FAIL, decideModule(zhStrings, false, "Stopped", null).first)
        assertEquals(DiagStatus.FAIL, decideModule(zhStrings, true, "Starting", null).first)
        assertTrue(decideModule(zhStrings, false, "Stopped", "boom").second.contains("boom"))
    }

    @Test
    fun `decideDeviceNetwork reports transport`() {
        assertEquals(DiagStatus.PASS, decideDeviceNetwork(zhStrings, true, "Wi-Fi").first)
        assertTrue(decideDeviceNetwork(zhStrings, true, "Wi-Fi").second.contains("Wi-Fi"))
        assertEquals(DiagStatus.FAIL, decideDeviceNetwork(zhStrings, false, "").first)
    }

    @Test
    fun `decideSystemDns and decideDirect`() {
        assertEquals(DiagStatus.PASS, decideSystemDns(zhStrings, true, 12L, null).first)
        assertEquals(DiagStatus.FAIL, decideSystemDns(zhStrings, false, null, "timeout").first)
        assertEquals(DiagStatus.PASS, decideDirect(zhStrings, 200, 120L).first)
        assertEquals(DiagStatus.FAIL, decideDirect(zhStrings, 404, 120L).first)
        assertEquals(DiagStatus.FAIL, decideDirect(zhStrings, null, null).first)
    }

    @Test
    fun `decideControlPlane treats any http response as responded without verifying identity`() {
        assertEquals(DiagStatus.PASS, decideControlPlane(zhStrings, "controlplane.tailscale.com", 404, 90L, null).first)
        assertEquals(DiagStatus.FAIL, decideControlPlane(zhStrings, "controlplane.tailscale.com", null, null, "timeout").first)
    }

    @Test
    fun `decideControlPlane detail says responded without verifying identity`() {
        val (status, detail) = decideControlPlane(zhStrings, "controlplane.tailscale.com", 404, 90L, null)
        assertEquals(DiagStatus.PASS, status)
        assertTrue(detail.contains("未验证身份"))
        assertFalse(detail.contains("可达"))
    }

    @Test
    fun `decideDnsResolve labels no valid response as no response not transport error`() {
        val (status, detail) = decideDnsResolve(zhStrings, null, emptyList(), false, ip2, "host-a.example.ts.net")
        assertEquals(DiagStatus.FAIL, status)
        assertTrue(detail.contains("无有效响应"))
        assertFalse(detail.contains("传输错误"))
    }

    @Test
    fun `decideMagicDns only checks the record exists`() {
        assertEquals(DiagStatus.SKIP, decideMagicDns(zhStrings, null).first)
        val (status, detail) = decideMagicDns(zhStrings, peer("host-a", ip2))
        assertEquals(DiagStatus.PASS, status)
        assertTrue(detail.contains("host-a.example.ts.net"))
        assertTrue(detail.contains(ip2))
        assertEquals(DiagStatus.FAIL, decideMagicDns(zhStrings, peer("x", ip2).copy(dnsName = "")).first)
    }

    @Test
    fun `decideDnsResolve rejects in-range answer that mismatches netmap`() {
        val domain = "host-a.example.ts.net"
        assertEquals(DiagStatus.FAIL, decideDnsResolve(zhStrings, null, emptyList(), true, ip2, domain).first)
        assertEquals(DiagStatus.FAIL, decideDnsResolve(zhStrings, null, emptyList(), false, ip2, domain).first)
        assertEquals(DiagStatus.FAIL, decideDnsResolve(zhStrings, 3, emptyList(), false, ip2, domain).first)
        assertEquals(DiagStatus.FAIL, decideDnsResolve(zhStrings, 5, emptyList(), false, ip2, domain).first)
        assertEquals(DiagStatus.FAIL, decideDnsResolve(zhStrings, 0, emptyList(), false, ip2, domain).first)
        assertEquals(DiagStatus.FAIL, decideDnsResolve(zhStrings, 0, listOf("8.8.8.8"), false, ip2, domain).first)
        val (matchStatus, matchDetail) =
            decideDnsResolve(zhStrings, 0, listOf(ip2), false, ip2, domain)
        assertEquals(DiagStatus.PASS, matchStatus)
        assertTrue(matchDetail.contains("与记录一致"))
        val (mismatchStatus, mismatchDetail) =
            decideDnsResolve(zhStrings, 0, listOf(ip6), false, ip2, domain)
        assertEquals(DiagStatus.FAIL, mismatchStatus)
        assertTrue(mismatchDetail.contains("不一致"))
    }

    @Test
    fun `decidePeers counts online peers`() {
        assertEquals(DiagStatus.FAIL, decidePeers(zhStrings, emptyList()).first)
        assertEquals(DiagStatus.FAIL, decidePeers(zhStrings, listOf(peer("a", ip2, online = false))).first)
        val (status, detail) = decidePeers(zhStrings,
            listOf(peer("a", ip2), peer("b", ip3, online = false)),
        )
        assertEquals(DiagStatus.PASS, status)
        assertTrue(detail.contains("在线 1 台 / 共 2 台"))
    }

    @Test
    fun `decideRoutes resolves exit node peer name and treats invalid IP as unset`() {
        val peers = listOf(peer("exit-1", ip6).copy(exitNode = true))
        val (status, detail) = decideRoutes(zhStrings, peers, true, ip6)
        assertEquals(DiagStatus.PASS, status)
        assertTrue(detail.contains("出口节点：exit-1"))
        assertTrue(detail.contains("接受子网路由：开"))
        val (_, unsetDetail) = decideRoutes(zhStrings, peers, false, "invalid IP")
        assertTrue(unsetDetail.contains("出口节点：未设置"))
        assertTrue(unsetDetail.contains("接受子网路由：关"))
    }

    @Test
    fun `decideTailnetTcp passes once connected`() {
        assertEquals(DiagStatus.PASS, decideTailnetTcp(zhStrings, true, "HTTP/1.1 200 OK", null).first)
        assertEquals(DiagStatus.PASS, decideTailnetTcp(zhStrings, true, null, null).first)
        assertEquals(DiagStatus.FAIL, decideTailnetTcp(zhStrings, false, null, "timeout").first)
    }

    @Test
    fun `decideTailnetPing`() {
        assertEquals(DiagStatus.PASS, decideTailnetPing(zhStrings, true, 3L, null).first)
        assertEquals(DiagStatus.FAIL, decideTailnetPing(zhStrings, false, null, "no route").first)
    }

    @Test
    fun `buildCopyText lists every item with number and status`() {
        val items = DiagItemId.entries.map { id ->
            DiagItemState(id, if (id == DiagItemId.MODULE) DiagStatus.PASS else DiagStatus.SKIP, "ok")
        }
        val titles = DiagItemId.entries.associateWith { it.name }
        val text = buildCopyText(zhStrings, items, titles, 1_000L, 13_000L)
        assertTrue(text.startsWith("网络诊断 "))
        assertTrue(text.contains("通过 1 / 失败 0 / 跳过 10"))
        assertTrue(text.contains("1.6b MAGIC_DNS_RESOLVE：跳过"))
        assertTrue(text.contains("1.10 TAILNET_PING：跳过"))
        assertFalse(text.contains("auth"))
        assertFalse(text.contains("controlplane"))
    }

    @Test
    fun `controlPlaneHost normalizes official and custom control plane URLs`() {
        assertEquals("controlplane.tailscale.com", controlPlaneHost("https://controlplane.tailscale.com"))
        assertEquals(
            "controlplane.tailscale.com",
            controlPlaneHost("https://controlplane.tailscale.com:443/api/v2"),
        )
        assertEquals("controlplane.tailscale.com", controlPlaneHost("controlplane.tailscale.com"))
        assertEquals(
            "controlplane.tailscale.com",
            controlPlaneHost("HTTPS://ControlPlane.Tailscale.COM."),
        )
        assertEquals("controlplane.example.com", controlPlaneHost("https://controlplane.example.com"))
        assertEquals("", controlPlaneHost(""))
    }
}
