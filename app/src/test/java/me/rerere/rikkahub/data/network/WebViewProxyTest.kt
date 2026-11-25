package me.rerere.rikkahub.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebViewProxyTest {
    @Test
    fun `loopback bypass uses bracketed ipv6 literal`() {
        // Chromium bypass 语法里 IPv6 字面量要括号；裸 ::1 不会被认成环回。
        val bypassRules = buildWebViewProxyConfig("http://127.0.0.1:8080").bypassRules
        assertEquals(listOf("localhost", "127.0.0.1", "[::1]"), bypassRules)
        assertFalse(bypassRules.contains("::1"))
    }

    @Test
    fun `proxy rule is kept`() {
        val config = buildWebViewProxyConfig("socks5://127.0.0.1:1080")
        // proxyRules 是 ProxyRule 对象（getProxyRules 不返回原始字符串）。
        assertEquals("socks5://127.0.0.1:1080", config.proxyRules.single().url)
    }
}
