package me.rerere.rikkahub.data.network

import me.rerere.rikkahub.data.datastore.SettingsStore
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Route-aware [ProxySelector]: every connection asks the current routing
 * settings which route applies, then returns the configured HTTP proxy or
 * [Proxy.NO_PROXY].
 *
 * The route is resolved per URI, so one shared client can serve providers with
 * different routes: the resolver maps the request host back to its provider
 * (see [findProviderIdByHost]) and falls back to the default route when no
 * provider matches. A scope-bound selector (MCP / backup) resolves every URI
 * to its scope's route instead.
 *
 * Behaviour notes, all deliberate:
 * - `PROXY` with no proxy configured falls back to the system selector
 *   (effectively direct on Android) — the missing-proxy rule: never fail.
 * - `DIRECT` / `TAILSCALE` return [Proxy.NO_PROXY] outright and never consult
 *   the system selector: explicit direct means direct.
 * - The tunnel is not decided here. Tailnet targets are handled by
 *   [me.rerere.rikkahub.data.tunnel.TsnetSocketFactory] on every client, so a
 *   tailnet address always goes through the tunnel when it is running,
 *   whatever the route says.
 * - Reads [SettingsStore.settingsFlow] live, so a route change applies to the
 *   next connection without rebuilding the client. The existing pool-eviction
 *   interceptor in the DI client still drops pooled connections on any
 *   network-setting change.
 */
class RoutingProxySelector(
    private val resolveRoute: (URI) -> NetworkRoute,
    private val configuredProxy: () -> Proxy?,
    private val systemProxySelector: ProxySelector? = ProxySelector.getDefault(),
) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        val route = runCatching { resolveRoute(uri) }.getOrNull() ?: NetworkRoute.DIRECT
        if (route == NetworkRoute.PROXY) {
            configuredProxy()?.let { return listOf(it) }
            // No proxy configured: fall through to the system selector, which is
            // effectively direct on Android. Never fail the request here.
        } else {
            return listOf(Proxy.NO_PROXY)
        }
        return runCatching { systemProxySelector?.select(uri) }
            .getOrNull()
            .orEmpty()
            .ifEmpty { listOf(Proxy.NO_PROXY) }
    }

    override fun connectFailed(uri: URI, socketAddress: SocketAddress, exception: IOException) {
        if (configuredProxy() == null) {
            systemProxySelector?.connectFailed(uri, socketAddress, exception)
        }
    }
}

/**
 * Selector for the shared client: the route is resolved per request host.
 * A host belonging to a provider uses that provider's route; any other host
 * (model catalog, token endpoints, ...) uses the default route.
 */
fun routingProxySelectorForProviders(settingsStore: SettingsStore): RoutingProxySelector =
    RoutingProxySelector(
        resolveRoute = { uri ->
            val settings = settingsStore.settingsFlow.value
            val providerId = uri.host?.let { findProviderIdByHost(settings.providers, it) }
            settings.networkSetting.routing.resolveRoute(providerId = providerId)
        },
        configuredProxy = {
            settingsStore.settingsFlow.value.networkSetting.proxyUrl.toProxyOrNull()
        },
    )

/** Selector bound to one scope (MCP / backup): every URI uses that scope's route. */
fun routingProxySelectorForScope(
    settingsStore: SettingsStore,
    scope: NetworkScope,
): RoutingProxySelector =
    RoutingProxySelector(
        resolveRoute = {
            settingsStore.settingsFlow.value.networkSetting.routing.resolveRoute(scope = scope)
        },
        configuredProxy = {
            settingsStore.settingsFlow.value.networkSetting.proxyUrl.toProxyOrNull()
        },
    )
