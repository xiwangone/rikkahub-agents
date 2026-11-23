package me.rerere.rikkahub.data.network

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the network route model: resolution precedence,
 * follow-default fallback, force-direct override, provider host mapping and
 * shell env vars. No SettingsStore / Android runtime involved.
 */
class NetworkRoutingTest {

    private val empty = NetworkRoutingSettings()

    @Test
    fun `default route is proxy`() {
        assertEquals(NetworkRoute.PROXY, empty.defaultRoute)
    }

    @Test
    fun `unconfigured routing is not configured`() {
        assertFalse(empty.isConfigured())
        assertEquals(NetworkRoute.PROXY, empty.resolveRoute(scope = NetworkScope.SHELL))
        assertEquals(NetworkRoute.PROXY, empty.resolveRoute(providerId = "any"))
    }

    @Test
    fun `force direct wins over everything`() {
        val routing = NetworkRoutingSettings(
            defaultRoute = NetworkRoute.PROXY,
            scopeRoutes = mapOf(NetworkScope.MCP.key to NetworkRoute.TAILSCALE),
            providerRoutes = mapOf("prov-1" to NetworkRoute.PROXY),
            forceDirect = true,
        )
        assertEquals(NetworkRoute.DIRECT, routing.resolveRoute(scope = NetworkScope.MCP))
        assertEquals(NetworkRoute.DIRECT, routing.resolveRoute(providerId = "prov-1"))
        assertEquals(NetworkRoute.DIRECT, routing.resolveRoute())
    }

    @Test
    fun `provider route wins over scope and default`() {
        val routing = NetworkRoutingSettings(
            defaultRoute = NetworkRoute.TAILSCALE,
            scopeRoutes = mapOf(NetworkScope.BACKUP.key to NetworkRoute.DIRECT),
            providerRoutes = mapOf("prov-1" to NetworkRoute.PROXY),
        )
        assertEquals(NetworkRoute.PROXY, routing.resolveRoute(providerId = "prov-1"))
    }

    @Test
    fun `scope route wins over default`() {
        val routing = NetworkRoutingSettings(
            defaultRoute = NetworkRoute.TAILSCALE,
            scopeRoutes = mapOf(NetworkScope.SHELL.key to NetworkRoute.DIRECT),
        )
        assertEquals(NetworkRoute.DIRECT, routing.resolveRoute(scope = NetworkScope.SHELL))
        assertEquals(NetworkRoute.TAILSCALE, routing.resolveRoute(scope = NetworkScope.MCP))
    }

    @Test
    fun `follow default falls back to the default route`() {
        val routing = NetworkRoutingSettings(defaultRoute = NetworkRoute.TAILSCALE)
        assertEquals(
            NetworkRoute.TAILSCALE,
            routing.resolveRoute(providerId = "unknown-provider"),
        )
        assertEquals(NetworkRoute.TAILSCALE, routing.resolveRoute(scope = NetworkScope.BACKUP))
    }

    @Test
    fun `any configured entry marks routing configured`() {
        assertTrue(
            empty.copy(defaultRoute = NetworkRoute.DIRECT).isConfigured(),
        )
        assertTrue(
            empty.copy(scopeRoutes = mapOf(NetworkScope.BROWSER.key to NetworkRoute.DIRECT))
                .isConfigured(),
        )
        assertTrue(
            empty.copy(providerRoutes = mapOf("p" to NetworkRoute.DIRECT)).isConfigured(),
        )
        assertTrue(empty.copy(forceDirect = true).isConfigured())
    }

    @Test
    fun `provider endpoint hosts cover known providers`() {
        assertTrue("api.openai.com" in providerEndpointHosts(ProviderSetting.OpenAI()))
        assertTrue(
            "generativelanguage.googleapis.com" in providerEndpointHosts(ProviderSetting.Google()),
        )
        assertTrue(".googleapis.com" in providerEndpointHosts(ProviderSetting.Google()))
        assertTrue("api.anthropic.com" in providerEndpointHosts(ProviderSetting.Claude()))
        assertTrue("api.x.ai" in providerEndpointHosts(ProviderSetting.Grok()))
        assertTrue(providerEndpointHosts(ProviderSetting.AICore()).isEmpty())
    }

    @Test
    fun `find provider by exact host and by suffix`() {
        val openai = ProviderSetting.OpenAI()
        val google = ProviderSetting.Google()
        assertEquals(
            openai.id.toString(),
            findProviderIdByHost(listOf(openai, google), "api.openai.com"),
        )
        // aiplatform.googleapis.com matches Google via the .googleapis.com suffix.
        assertEquals(
            google.id.toString(),
            findProviderIdByHost(listOf(openai, google), "aiplatform.googleapis.com"),
        )
        assertNull(findProviderIdByHost(listOf(openai, google), "example.com"))
    }

    @Test
    fun `shared host goes to the first provider in list order`() {
        // Codex and an OpenAI provider both use api.openai.com: the first one
        // wins and both get the same route (host matching limitation).
        val codex = ProviderSetting.Codex()
        val openai = ProviderSetting.OpenAI()
        assertEquals(
            codex.id.toString(),
            findProviderIdByHost(listOf(codex, openai), "api.openai.com"),
        )
        assertEquals(
            openai.id.toString(),
            findProviderIdByHost(listOf(openai, codex), "api.openai.com"),
        )
    }

    @Test
    fun `shell env vars are empty for direct`() {
        assertTrue(
            proxyShellEnvVars(
                route = NetworkRoute.DIRECT,
                proxyUrl = "http://proxy:8080",
                proxyUsername = "",
                proxyPassword = null,
            ).isEmpty(),
        )
    }

    @Test
    fun `shell env vars are empty when proxy is not configured`() {
        assertTrue(
            proxyShellEnvVars(
                route = NetworkRoute.PROXY,
                proxyUrl = "",
                proxyUsername = "",
                proxyPassword = null,
            ).isEmpty(),
        )
    }

    @Test
    fun `shell env vars use http scheme for http proxy`() {
        val env = proxyShellEnvVars(
            route = NetworkRoute.PROXY,
            proxyUrl = "http://proxy:8080",
            proxyUsername = "",
            proxyPassword = null,
        )
        assertEquals("http://proxy:8080", env["ALL_PROXY"])
        assertEquals("http://proxy:8080", env["HTTP_PROXY"])
        assertEquals("http://proxy:8080", env["HTTPS_PROXY"])
        assertTrue(env["NO_PROXY"]!!.contains("localhost"))
        assertTrue(env["NO_PROXY"]!!.contains("127.0.0.1"))
    }

    @Test
    fun `shell env vars use socks5h scheme for socks proxy`() {
        val env = proxyShellEnvVars(
            route = NetworkRoute.PROXY,
            proxyUrl = "socks5://proxy:1080",
            proxyUsername = "",
            proxyPassword = null,
        )
        assertEquals("socks5h://proxy:1080", env["ALL_PROXY"])
        assertEquals("socks5h://proxy:1080", env["HTTP_PROXY"])
    }

    @Test
    fun `shell env vars embed proxy credentials`() {
        val env = proxyShellEnvVars(
            route = NetworkRoute.PROXY,
            proxyUrl = "http://proxy:8080",
            proxyUsername = "user",
            proxyPassword = "secret",
        )
        assertEquals("http://user:secret@proxy:8080", env["ALL_PROXY"])
    }

    @Test
    fun `shell env vars reject a proxy url with embedded credentials`() {
        // toProxyOrNull rejects URLs carrying user info; credentials only come
        // from the username/password fields, never from the URL itself.
        assertTrue(
            proxyShellEnvVars(
                route = NetworkRoute.PROXY,
                proxyUrl = "http://old:pass@proxy:8080",
                proxyUsername = "",
                proxyPassword = null,
            ).isEmpty(),
        )
    }
}
