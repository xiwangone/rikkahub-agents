package me.rerere.rikkahub.data.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the routing-aware proxy selector: provider host mapping,
 * scope routes, proxy fallback and the tailscale/direct pass-through.
 *
 * The selector is built directly with lambdas here (the SettingsStore-backed
 * factories in RoutingProxySelector.kt need a DataStore and are covered by the
 * shared-client wiring, not by these tests). The system selector is null so
 * the missing-proxy fallback is deterministic.
 */
class RoutingProxySelectorTest {

    private val proxyUrl = "http://proxy.example.com:8080"

    private fun selector(
        routes: Map<String, NetworkRoute>,
        defaultRoute: NetworkRoute = NetworkRoute.PROXY,
        proxy: String = proxyUrl,
    ) = RoutingProxySelector(
        resolveRoute = { uri ->
            val host = uri.host ?: return@RoutingProxySelector defaultRoute
            routes[host] ?: defaultRoute
        },
        configuredProxy = { proxy.toProxyOrNull() },
        systemProxySelector = null,
    )

    private fun proxiesFor(selector: RoutingProxySelector, url: String): List<Proxy> =
        selector.select(URI(url))

    private fun assertProxy(proxies: List<Proxy>, host: String, port: Int, type: Proxy.Type) {
        assertEquals(1, proxies.size)
        assertEquals(type, proxies[0].type())
        val address = proxies[0].address() as InetSocketAddress
        assertEquals(host, address.hostString)
        assertEquals(port, address.port)
    }

    @Test
    fun `provider proxy route selects the configured proxy`() {
        val sel = selector(mapOf("api.openai.com" to NetworkRoute.PROXY))
        assertProxy(
            proxiesFor(sel, "https://api.openai.com/v1/chat/completions"),
            "proxy.example.com",
            8080,
            Proxy.Type.HTTP,
        )
    }

    @Test
    fun `provider direct route selects no proxy`() {
        val sel = selector(mapOf("api.openai.com" to NetworkRoute.DIRECT))
        val proxies = proxiesFor(sel, "https://api.openai.com/v1/chat/completions")
        assertEquals(listOf(Proxy.NO_PROXY), proxies)
    }

    @Test
    fun `provider tailscale route selects no proxy`() {
        // Tailscale destinations are handled by the socket factory, not by a proxy.
        val sel = selector(mapOf("api.openai.com" to NetworkRoute.TAILSCALE))
        val proxies = proxiesFor(sel, "https://api.openai.com/v1/chat/completions")
        assertEquals(listOf(Proxy.NO_PROXY), proxies)
    }

    @Test
    fun `unknown host falls back to the default route`() {
        val sel = selector(emptyMap(), defaultRoute = NetworkRoute.PROXY)
        assertProxy(
            proxiesFor(sel, "https://unknown.example.com/v1"),
            "proxy.example.com",
            8080,
            Proxy.Type.HTTP,
        )
    }

    @Test
    fun `unknown host with default direct selects no proxy`() {
        val sel = selector(emptyMap(), defaultRoute = NetworkRoute.DIRECT)
        val proxies = proxiesFor(sel, "https://unknown.example.com/v1")
        assertEquals(listOf(Proxy.NO_PROXY), proxies)
    }

    @Test
    fun `proxy route without a configured proxy selects no proxy`() {
        val sel = selector(
            mapOf("api.openai.com" to NetworkRoute.PROXY),
            proxy = "",
        )
        val proxies = proxiesFor(sel, "https://api.openai.com/v1")
        assertEquals(listOf(Proxy.NO_PROXY), proxies)
    }

    @Test
    fun `socks proxy is selected with socks type`() {
        val sel = selector(
            mapOf("api.openai.com" to NetworkRoute.PROXY),
            proxy = "socks5://proxy.example.com:1080",
        )
        assertProxy(
            proxiesFor(sel, "https://api.openai.com/v1"),
            "proxy.example.com",
            1080,
            Proxy.Type.SOCKS,
        )
    }

    @Test
    fun `fixed route selector routes every uri to its route`() {
        // Mirrors the scope-bound selector (MCP / backup): one fixed route.
        val direct = RoutingProxySelector(
            resolveRoute = { NetworkRoute.DIRECT },
            configuredProxy = { proxyUrl.toProxyOrNull() },
            systemProxySelector = null,
        )
        assertEquals(
            listOf(Proxy.NO_PROXY),
            proxiesFor(direct, "https://example.com/sync"),
        )

        val proxy = RoutingProxySelector(
            resolveRoute = { NetworkRoute.PROXY },
            configuredProxy = { proxyUrl.toProxyOrNull() },
            systemProxySelector = null,
        )
        assertProxy(
            proxiesFor(proxy, "https://example.com/sync"),
            "proxy.example.com",
            8080,
            Proxy.Type.HTTP,
        )
    }

    @Test
    fun `connect failed is a no-op`() {
        val sel = selector(mapOf("api.openai.com" to NetworkRoute.PROXY))
        val proxies = proxiesFor(sel, "https://api.openai.com/v1")
        sel.connectFailed(
            URI("https://api.openai.com/v1"),
            proxies[0].address(),
            IOException("boom"),
        )
        // No exception: a failed proxy never changes routing by itself.
        assertTrue(true)
    }
}
