package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.NetworkSetting
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DNS 草稿拼临时配置（[buildDnsTestSetting]）：保存与弹窗内测试共用同一口径；
 * 草稿字段归一化（去空白、空值回落默认），其余字段沿用原配置。
 */
class SettingNetworkDnsTest {

    private val base = NetworkSetting(
        proxyUrl = "http://127.0.0.1:7890",
        dnsMode = NetworkSetting.DNS_MODE_SYSTEM,
    )

    private fun build(
        mode: String = NetworkSetting.DNS_MODE_DOH,
        dohUrl: String = "https://dns.example.com/dns-query",
        dotHost: String = "dot.example.com",
        customServers: String = "1.1.1.1, 8.8.8.8",
        fallback: Boolean = true,
        ipv6: Boolean = true,
        testDomain: String = "example.org",
    ) = buildDnsTestSetting(
        base = base,
        mode = mode,
        dohUrl = dohUrl,
        dotHost = dotHost,
        customServers = customServers,
        fallbackToSystem = fallback,
        enableIpv6 = ipv6,
        testDomain = testDomain,
    )

    @Test
    fun `draft values pass through and other fields are kept from base`() {
        val result = build()
        assertEquals(NetworkSetting.DNS_MODE_DOH, result.dnsMode)
        assertEquals("https://dns.example.com/dns-query", result.dohUrl)
        assertEquals("dot.example.com", result.dotHost)
        assertEquals("1.1.1.1, 8.8.8.8", result.customDnsServers)
        assertEquals(true, result.dnsFallbackToSystem)
        assertEquals(true, result.dnsEnableIpv6)
        assertEquals("example.org", result.dnsTestDomain)
        assertEquals("http://127.0.0.1:7890", result.proxyUrl)
    }

    @Test
    fun `blank draft fields fall back to defaults`() {
        val result = build(dohUrl = "", dotHost = "  ", customServers = "", testDomain = "")
        assertEquals(NetworkSetting.DEFAULT_DOH_URL, result.dohUrl)
        assertEquals(NetworkSetting.DEFAULT_DOT_HOST, result.dotHost)
        assertEquals(NetworkSetting.DEFAULT_BOOTSTRAP_SERVERS, result.customDnsServers)
        assertEquals(NetworkSetting.DEFAULT_DNS_TEST_DOMAIN, result.dnsTestDomain)
    }

    @Test
    fun `draft values are trimmed`() {
        val result = build(
            dohUrl = "  https://dns.example.com/dns-query  ",
            testDomain = " example.org ",
        )
        assertEquals("https://dns.example.com/dns-query", result.dohUrl)
        assertEquals("example.org", result.dnsTestDomain)
    }

    @Test
    fun `all four modes pass through`() {
        listOf(
            NetworkSetting.DNS_MODE_SYSTEM,
            NetworkSetting.DNS_MODE_DOH,
            NetworkSetting.DNS_MODE_DOT,
            NetworkSetting.DNS_MODE_CUSTOM,
        ).forEach { mode ->
            assertEquals(mode, build(mode = mode).dnsMode)
        }
    }

    @Test
    fun `draft setting differs from saved when drafts differ`() {
        // 弹窗内测试用的就是这份草稿配置，不是已保存的配置。
        val saved = NetworkSetting(dnsMode = NetworkSetting.DNS_MODE_SYSTEM)
        val draft = buildDnsTestSetting(
            base = saved,
            mode = NetworkSetting.DNS_MODE_DOH,
            dohUrl = "https://dns.example.com/dns-query",
            dotHost = NetworkSetting.DEFAULT_DOT_HOST,
            customServers = NetworkSetting.DEFAULT_BOOTSTRAP_SERVERS,
            fallbackToSystem = true,
            enableIpv6 = false,
            testDomain = "example.org",
        )
        assertEquals(NetworkSetting.DNS_MODE_DOH, draft.dnsMode)
        assertEquals(NetworkSetting.DNS_MODE_SYSTEM, saved.dnsMode)
    }
}
