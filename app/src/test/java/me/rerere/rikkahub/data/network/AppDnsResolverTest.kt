package me.rerere.rikkahub.data.network

import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.tunnel.TsnetBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class AppDnsResolverTest {

    @Test
    fun `buildQuery and parseResponse round-trip A and AAAA records with compression pointer`() {
        val query = AppDnsResolver.buildQuery(0x1234, "api.openai.com", AppDnsResolver.QTYPE_A)
        assertEquals(0x12, query[0].toInt() and 0xFF)
        assertEquals(0x34, query[1].toInt() and 0xFF)

        // Build a synthetic response packet with compression pointer 0xC00C -> api.openai.com
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34)) // ID
        out.write(byteArrayOf(0x81.toByte(), 0x80.toByte())) // Flags: QR=1, RD=1, RA=1, RCODE=0
        out.write(byteArrayOf(0x00, 0x01)) // QDCOUNT = 1
        out.write(byteArrayOf(0x00, 0x02)) // ANCOUNT = 2
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00)) // NSCOUNT, ARCOUNT
        // Copy question from query (starting at byte 12)
        out.write(query, 12, query.size - 12)

        // Answer 1: A record 203.0.113.10, TTL = 180
        out.write(byteArrayOf(0xC0.toByte(), 0x0C)) // Name pointer to offset 12
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01)) // TYPE A, CLASS IN
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0xB4.toByte())) // TTL 180s
        out.write(byteArrayOf(0x00, 0x04)) // RDLENGTH 4
        out.write(byteArrayOf(203.toByte(), 0, 113, 10))

        // Answer 2: AAAA record 2001:db8::1, TTL = 90
        out.write(byteArrayOf(0xC0.toByte(), 0x0C))
        out.write(byteArrayOf(0x00, 0x1C, 0x00, 0x01)) // TYPE AAAA (28), CLASS IN
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x5A)) // TTL 90s
        out.write(byteArrayOf(0x00, 0x10)) // RDLENGTH 16
        out.write(
            byteArrayOf(
                0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 1,
            ),
        )

        val packet = out.toByteArray()
        val parsed = AppDnsResolver.parseResponse(
            expectedId = 0x1234,
            buf = packet,
            length = packet.size,
            requestedHost = "api.openai.com",
        )
        assertNotNull(parsed)
        assertFalse(parsed!!.truncated)
        assertEquals(0, parsed.rcode)
        assertEquals(2, parsed.addresses.size)
        assertEquals("203.0.113.10", parsed.addresses[0].hostAddress)
        assertEquals("api.openai.com", parsed.addresses[0].hostName)
        assertEquals(90L, parsed.minTtlSeconds)

        // Wrong transaction ID must be rejected
        assertNull(AppDnsResolver.parseResponse(expectedId = 0x9999, buf = packet))
    }

    @Test
    fun `well-known DoH and DoT hosts resolve via built-in bootstrap IPs without network`() {
        val ali = AppDnsResolver.resolveBootstrapAddresses("dns.alidns.com", emptyList())
        assertTrue(ali.isNotEmpty())
        assertEquals("223.5.5.5", ali.first().hostAddress)
        assertEquals("dns.alidns.com", ali.first().hostName)

        val cf = AppDnsResolver.resolveBootstrapAddresses("cloudflare-dns.com", emptyList())
        assertTrue(cf.isNotEmpty())
        assertEquals("1.1.1.1", cf.first().hostAddress)
    }

    @Test
    fun `parseHostPort handles plain host, host with port, and IPv6 bracketed host`() {
        assertEquals("dns.alidns.com" to 853, AppDnsResolver.parseHostPort("dns.alidns.com", 853))
        assertEquals("dns.alidns.com" to 8853, AppDnsResolver.parseHostPort("dns.alidns.com:8853", 853))
        assertEquals("2400:3200::1" to 853, AppDnsResolver.parseHostPort("[2400:3200::1]", 853))
        assertEquals("2400:3200::1" to 8853, AppDnsResolver.parseHostPort("[2400:3200::1]:8853", 853))
    }

    @Test
    fun `Tailscale address matcher covers CGNAT IPv4, IPv6 ULA, and MagicDNS`() {
        assertTrue(TsnetBridge.isTailnetAddress("100.64.5.1"))
        assertTrue(TsnetBridge.isTailnetAddress("100.127.255.254"))
        assertFalse(TsnetBridge.isTailnetAddress("100.63.255.1"))
        assertFalse(TsnetBridge.isTailnetAddress("100.128.0.1"))
        assertTrue(TsnetBridge.isTailnetAddress("fd7a:115c:a1e0::1234"))
        assertTrue(TsnetBridge.isTailnetAddress("my-node.tail1234.ts.net"))
        assertFalse(TsnetBridge.isTailnetAddress("example.com"))
    }

    @Test
    fun `tailnet DNS server is recognized by exact address only`() {
        assertTrue(isTailnetDnsServer("100.100.100.100"))
        assertTrue(isTailnetDnsServer("  100.100.100.100  "))
        assertFalse(isTailnetDnsServer("100.100.100.101"))
        assertFalse(isTailnetDnsServer("1.1.1.1"))
        assertFalse(isTailnetDnsServer(""))
    }

    @Test
    fun `tailnet DNS is used only in custom mode with the server in the list`() {
        val tailnetOnly = NetworkSetting(
            dnsMode = NetworkSetting.DNS_MODE_CUSTOM,
            customDnsServers = "100.100.100.100",
        )
        assertTrue(usesTailnetDns(tailnetOnly))

        val mixed = NetworkSetting(
            dnsMode = NetworkSetting.DNS_MODE_CUSTOM,
            customDnsServers = "1.1.1.1, 100.100.100.100",
        )
        assertTrue(usesTailnetDns(mixed))

        val otherCustom = NetworkSetting(
            dnsMode = NetworkSetting.DNS_MODE_CUSTOM,
            customDnsServers = "1.1.1.1, 8.8.8.8",
        )
        assertFalse(usesTailnetDns(otherCustom))

        val dohWithTailnetBootstrap = NetworkSetting(
            dnsMode = NetworkSetting.DNS_MODE_DOH,
            customDnsServers = "100.100.100.100",
        )
        assertFalse(usesTailnetDns(dohWithTailnetBootstrap))

        val system = NetworkSetting(dnsMode = NetworkSetting.DNS_MODE_SYSTEM)
        assertFalse(usesTailnetDns(system))
    }

    @Test
    fun `tailnet MagicDNS names skip public DNS unless tailnet DNS is configured`() {
        val doh = NetworkSetting(dnsMode = NetworkSetting.DNS_MODE_DOH)
        assertTrue(shouldSkipPublicDnsForTailnetHost("host.ts.net", doh))
        assertTrue(shouldSkipPublicDnsForTailnetHost("host.beta.tailscale.net", doh))
        assertFalse(shouldSkipPublicDnsForTailnetHost("example.com", doh))

        val publicCustom = NetworkSetting(
            dnsMode = NetworkSetting.DNS_MODE_CUSTOM,
            customDnsServers = "1.1.1.1, 8.8.8.8",
        )
        assertTrue(shouldSkipPublicDnsForTailnetHost("host.ts.net", publicCustom))

        val tailnetDns = NetworkSetting(
            dnsMode = NetworkSetting.DNS_MODE_CUSTOM,
            customDnsServers = "100.100.100.100",
        )
        assertFalse(shouldSkipPublicDnsForTailnetHost("host.ts.net", tailnetDns))
        assertFalse(shouldSkipPublicDnsForTailnetHost("example.com", tailnetDns))
    }

    @Test
    fun `tailnet preset is present with the tailnet DNS server and custom-only`() {
        val presets = AppDnsResolver.PRESETS.filter { it.id == AppDnsResolver.TAILNET_PRESET_ID }
        assertEquals(1, presets.size)
        val preset = presets.first()
        assertEquals("100.100.100.100", preset.bootstrapIps)
        assertTrue(preset.customOnly)
    }

    @Test
    fun `parseServerList accepts IPv4 with port suffix and ignores the port`() {
        assertEquals(listOf("100.100.100.100"), AppDnsResolver.parseServerList("100.100.100.100:53"))
        assertEquals(
            listOf("1.1.1.1", "8.8.8.8"),
            AppDnsResolver.parseServerList("1.1.1.1:5353, 8.8.8.8"),
        )
        // 非数字端口、超出范围的端口、主机名带端口一律丢弃，不静默吞掉整条配置。
        assertEquals(emptyList<String>(), AppDnsResolver.parseServerList("1.1.1.1:abc"))
        assertEquals(emptyList<String>(), AppDnsResolver.parseServerList("1.1.1.1:99999"))
        assertEquals(emptyList<String>(), AppDnsResolver.parseServerList("example.com:53"))
        // IPv6 字面量含冒号，不做剥离，原样保留。
        assertEquals(listOf("2001:db8::1"), AppDnsResolver.parseServerList("2001:db8::1"))
    }

    @Test
    fun `MagicDNS names only query tailnet DNS servers in custom mode`() {
        assertEquals(
            listOf("100.100.100.100"),
            effectiveCustomServers(listOf("1.1.1.1", "100.100.100.100"), "host.ts.net"),
        )
        assertEquals(
            listOf("100.100.100.100"),
            effectiveCustomServers(listOf("1.1.1.1", "100.100.100.100"), "host.beta.tailscale.net"),
        )
        // 列表里没有尾网服务器时不问公共 DNS，返回空列表（调用方按解析失败处理）。
        assertEquals(
            emptyList<String>(),
            effectiveCustomServers(listOf("1.1.1.1", "8.8.8.8"), "host.ts.net"),
        )
        // 非尾网域名不受影响，全部服务器都问。
        assertEquals(
            listOf("1.1.1.1", "8.8.8.8"),
            effectiveCustomServers(listOf("1.1.1.1", "8.8.8.8"), "example.com"),
        )
    }
}
