package me.rerere.rikkahub.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ProviderSetting
import java.net.URI

/**
 * Network route value, borrowed from the Minis "Network Route" settings page
 * (interaction model only — no Minis code is used here).
 *
 * - [DIRECT]: direct connection, no HTTP proxy.
 * - [TAILSCALE]: via the in-process Tailscale tunnel. Tailnet destinations always
 *   go through the tunnel when it is running (the socket factory enforces this for
 *   every route, because a tailnet address cannot be dialled directly). Public
 *   destinations currently go direct: the app has no exit-node dial path, so a
 *   "tailscale" route behaves like "direct" for public hosts. That gap is a known
 *   limitation, not a silent fallback — see [NetworkRoutingSettings].
 * - [PROXY]: via the configured HTTP proxy ([me.rerere.rikkahub.data.datastore.NetworkSetting.proxyUrl]).
 *   When no proxy is configured the route falls back to direct (missing-proxy rule:
 *   never fail, never error).
 * - [FOLLOW_DEFAULT]: follow the default route (only meaningful for a scope or
 *   provider entry; the default route itself never uses it).
 */
@Serializable
enum class NetworkRoute {
    @SerialName("direct")
    DIRECT,

    @SerialName("tailscale")
    TAILSCALE,

    @SerialName("proxy")
    PROXY,

    @SerialName("default")
    FOLLOW_DEFAULT,
}

/**
 * Traffic scope a route can be attached to. Mirrors the four Minis scopes.
 * The [key] is the stable string stored in [NetworkRoutingSettings.scopeRoutes].
 */
@Serializable
enum class NetworkScope(val key: String) {
    @SerialName("shell")
    SHELL("shell"),

    @SerialName("browser")
    BROWSER("browser"),

    @SerialName("mcp")
    MCP("mcp"),

    @SerialName("backup")
    BACKUP("backup"),
}

/**
 * Network routing settings (the "Network Route" settings page).
 *
 * Stored inside [me.rerere.rikkahub.data.datastore.NetworkSetting] so it rides the
 * existing DataStore flow; every field has a default, so old stored settings keep
 * working.
 *
 * Default route is [NetworkRoute.PROXY] on purpose: the proxy used to be a single
 * global setting applied to all shared-client traffic, and PROXY with the
 * missing-proxy fallback reproduces that legacy behaviour exactly (proxy
 * configured → traffic via proxy; none configured → direct). A fresh user without
 * a proxy therefore sees no behaviour change, and an existing proxy user keeps
 * working after this lands. Set the default to DIRECT explicitly to stop using
 * the proxy by default.
 *
 * Known limitation: a [TAILSCALE] route does not change anything for public
 * destinations (no exit-node dial path in the app yet). It is still the correct
 * setting for a tailnet-hosted endpoint, and it documents intent for the future
 * exit-node path.
 */
@Serializable
data class NetworkRoutingSettings(
    val defaultRoute: NetworkRoute = NetworkRoute.PROXY,
    /** Per-scope routes, keyed by [NetworkScope.key]; absent = follow the default. */
    val scopeRoutes: Map<String, NetworkRoute> = emptyMap(),
    /** Per-provider routes, keyed by [ProviderSetting.id] string; absent = follow the default. */
    val providerRoutes: Map<String, NetworkRoute> = emptyMap(),
    /**
     * One-click restore direct: when true every route resolves to [NetworkRoute.DIRECT].
     * All settings are kept and can be resumed by turning this off again.
     */
    val forceDirect: Boolean = false,
) {
    /**
     * Resolve the effective route. Decision order (same as Minis): provider tag →
     * scope tag → default route → direct. A [NetworkRoute.FOLLOW_DEFAULT] entry is
     * skipped as if it were absent.
     */
    fun resolveRoute(scope: NetworkScope? = null, providerId: String? = null): NetworkRoute {
        if (forceDirect) return NetworkRoute.DIRECT
        providerId?.let { id ->
            providerRoutes[id]?.takeIf { it != NetworkRoute.FOLLOW_DEFAULT }?.let { return it }
        }
        scope?.let { s ->
            scopeRoutes[s.key]?.takeIf { it != NetworkRoute.FOLLOW_DEFAULT }?.let { return it }
        }
        return if (defaultRoute == NetworkRoute.FOLLOW_DEFAULT) NetworkRoute.DIRECT else defaultRoute
    }

    /** True when the user changed anything from the defaults (i.e. the module is in use). */
    fun isConfigured(): Boolean =
        defaultRoute != NetworkRoute.PROXY || scopeRoutes.isNotEmpty() ||
            providerRoutes.isNotEmpty() || forceDirect

    /** True when [providerId] has a route of its own; a stored FOLLOW_DEFAULT entry counts as none. */
    fun hasProviderOverride(providerId: String): Boolean =
        providerRoutes[providerId]?.takeIf { it != NetworkRoute.FOLLOW_DEFAULT } != null

    /**
     * Apply [route] to every provider in [providerIds]. FOLLOW_DEFAULT clears the
     * override, matching what tapping "Default" on a single provider row does.
     */
    fun withProviderRoutes(
        providerIds: Collection<String>,
        route: NetworkRoute,
    ): NetworkRoutingSettings {
        val routes = providerRoutes.toMutableMap()
        for (id in providerIds) {
            if (route == NetworkRoute.FOLLOW_DEFAULT) routes.remove(id) else routes[id] = route
        }
        return copy(providerRoutes = routes)
    }
}

/**
 * On-device providers make no network requests, so routes are meaningless for them.
 * Mirrors [providerEndpointHosts], which returns an empty host list for the same set.
 */
fun isOnDeviceProvider(provider: ProviderSetting): Boolean = when (provider) {
    is ProviderSetting.AICore,
    is ProviderSetting.LiteRtLocal,
    is ProviderSetting.LlamaCppLocal,
    -> true

    else -> false
}

/**
 * Endpoint hosts a provider talks to, used to map a request host back to its
 * provider for route resolution. An entry is either an exact host
 * (e.g. `api.openai.com`) or a suffix pattern starting with `.`
 * (e.g. `.googleapis.com`, matching any subdomain). Local providers
 * (on-device inference) return an empty list: they make no network requests.
 */
fun providerEndpointHosts(provider: ProviderSetting): List<String> = when (provider) {
    is ProviderSetting.OpenAI -> listOfNotNull(provider.baseUrl.toHostOrNull())
    is ProviderSetting.Google -> listOfNotNull(
        provider.baseUrl.toHostOrNull(),
        // Vertex AI and the OAuth token endpoint share the googleapis.com suffix.
        ".googleapis.com",
    )
    is ProviderSetting.Claude -> listOfNotNull(provider.baseUrl.toHostOrNull())
    is ProviderSetting.Backend -> listOfNotNull(provider.baseUrl.toHostOrNull())
    // Account-based providers use fixed SaaS domains (no user-configurable baseUrl).
    is ProviderSetting.Codex -> listOf("chatgpt.com", "api.openai.com")
    is ProviderSetting.Grok -> listOf("api.x.ai")
    is ProviderSetting.GeminiOAuth -> listOf(".googleapis.com")
    // On-device providers: no network endpoint.
    is ProviderSetting.AICore,
    is ProviderSetting.LiteRtLocal,
    is ProviderSetting.LlamaCppLocal,
    -> emptyList()
}

private fun String.toHostOrNull(): String? {
    val value = trim()
    if (value.isEmpty()) return null
    val normalized = if ("://" in value) value else "https://$value"
    return runCatching { URI(normalized).host }.getOrNull()
        ?.takeIf { it.isNotBlank() }?.lowercase()
}

private fun String.matchesHostPattern(pattern: String): Boolean {
    val host = lowercase().removeSurrounding("[", "]")
    val normalized = pattern.lowercase().removeSurrounding("[", "]")
    return if (normalized.startsWith(".")) {
        host == normalized.removePrefix(".") || host.endsWith(normalized)
    } else {
        host == normalized
    }
}

/**
 * Find the provider id whose endpoint hosts match [host], or null when no
 * provider matches. Providers are checked in list order; when two providers
 * share a host (e.g. Codex and an OpenAI provider both on api.openai.com) the
 * first one wins and both get the same route — a documented limitation of
 * host-based matching.
 */
fun findProviderIdByHost(providers: List<ProviderSetting>, host: String): String? {
    val needle = host.trim()
    if (needle.isEmpty()) return null
    for (provider in providers) {
        if (providerEndpointHosts(provider).any { needle.matchesHostPattern(it) }) {
            return provider.id.toString()
        }
    }
    return null
}

/**
 * Shell proxy environment variables for the terminal scope, following the Minis
 * layout: SOCKS5 uses `socks5h` (remote DNS, MagicDNS resolves through the
 * route), HTTP(S) uses `http`. Credentials are embedded when configured
 * (the shell is the user's own process, same as Minis' per-process password).
 * Returns an empty map when the route is not [NetworkRoute.PROXY] or no proxy
 * is configured. `NO_PROXY` always covers loopback so local services are
 * never sent to the proxy.
 */
fun proxyShellEnvVars(
    route: NetworkRoute,
    proxyUrl: String,
    proxyUsername: String,
    proxyPassword: String?,
): Map<String, String> {
    if (route != NetworkRoute.PROXY) return emptyMap()
    val proxy = proxyUrl.toProxyOrNull() ?: return emptyMap()
    val address = (proxy.address() as? java.net.InetSocketAddress)?.let { addr ->
        val host = addr.hostString
        val port = addr.port
        val credentials = if (proxyUsername.isNotBlank() && !proxyPassword.isNullOrEmpty()) {
            "${proxyUsername}:${proxyPassword}@"
        } else {
            ""
        }
        when (proxy.type()) {
            java.net.Proxy.Type.SOCKS -> "socks5h://$credentials$host:$port"
            else -> "http://$credentials$host:$port"
        }
    } ?: return emptyMap()
    return mapOf(
        "ALL_PROXY" to address,
        "all_proxy" to address,
        "HTTP_PROXY" to address,
        "http_proxy" to address,
        "HTTPS_PROXY" to address,
        "https_proxy" to address,
        "NO_PROXY" to "localhost,127.0.0.1,::1",
        "no_proxy" to "localhost,127.0.0.1,::1",
    )
}
