package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.network.NetworkRoute
import me.rerere.rikkahub.data.network.NetworkRoutingSettings
import me.rerere.rikkahub.data.network.NetworkScope
import me.rerere.rikkahub.data.network.WebViewProxy
import me.rerere.rikkahub.data.network.resolveProxyPasswordForAuth
import me.rerere.rikkahub.data.network.toProxyOrNull
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import org.koin.androidx.compose.koinViewModel

/**
 * Network Route settings page (Minis "Network Route" page, interaction model
 * only — no Minis code): default route + per-scope routes (terminal / browser /
 * MCP / backup) + per-provider routes + one-click restore direct.
 *
 * Enforcement lives in the routing-aware proxy selector (providers), the
 * scope-bound selectors (MCP / backup), shell env vars (terminal) and the
 * WebView proxy override (browser). See NetworkRouting.kt.
 */
@Composable
fun SettingNetworkRoutingPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val net = settings.networkSetting
    val routing = net.routing
    val proxyConfigured = net.proxyUrl.toProxyOrNull() != null
    val webViewSupported = remember { WebViewProxy.isSupported() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun updateRouting(transform: (NetworkRoutingSettings) -> NetworkRoutingSettings) {
        // Compute the new routing first: the browser proxy is applied with the
        // fresh values right away (no need to wait for the DataStore round-trip).
        val newRouting = transform(routing)
        vm.updateSettings { current ->
            current.copy(networkSetting = current.networkSetting.copy(routing = newRouting))
        }
        WebViewProxy.apply(
            route = newRouting.resolveRoute(scope = NetworkScope.BROWSER),
            proxyUrl = net.proxyUrl,
            proxyUsername = net.proxyUsername,
            proxyPassword = resolveProxyPasswordForAuth(net.proxyPassword),
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_network_routing_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (routing.forceDirect) {
                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_page_network_routing_force_direct_on))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.setting_page_network_routing_force_direct_desc))
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                headlineColor = MaterialTheme.colorScheme.onErrorContainer,
                                supportingColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        )
                    }
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    formItem(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_network_routing_default))
                        },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_page_network_routing_default_desc))
                                if (!proxyConfigured) {
                                    Text(stringResource(R.string.setting_page_network_routing_no_proxy))
                                }
                            }
                        },
                    ) {
                        RouteChoiceButtons(
                            selected = routing.defaultRoute,
                            options = listOf(
                                NetworkRoute.DIRECT,
                                NetworkRoute.TAILSCALE,
                                NetworkRoute.PROXY,
                            ),
                            onSelect = { route ->
                                updateRouting { it.copy(defaultRoute = route) }
                            },
                        )
                    }
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_network_routing_scope_title)) },
                ) {
                    NetworkScope.entries.forEach { scope ->
                        val selected = routing.scopeRoutes[scope.key] ?: NetworkRoute.FOLLOW_DEFAULT
                        formItem(
                            headlineContent = {
                                Text(scopeName(scope))
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        stringResource(
                                            R.string.setting_page_network_routing_effective,
                                            routeName(routing.resolveRoute(scope = scope)),
                                        ),
                                    )
                                    if (selected == NetworkRoute.PROXY && !proxyConfigured) {
                                        Text(stringResource(R.string.setting_page_network_routing_no_proxy))
                                    }
                                    if (selected == NetworkRoute.TAILSCALE) {
                                        Text(stringResource(R.string.setting_page_network_routing_tailscale_note))
                                    }
                                    if (scope == NetworkScope.BROWSER && !webViewSupported) {
                                        Text(stringResource(R.string.setting_page_network_routing_webview_unsupported))
                                    }
                                }
                            },
                        ) {
                            RouteChoiceButtons(
                                selected = selected,
                                options = listOf(
                                    NetworkRoute.DIRECT,
                                    NetworkRoute.TAILSCALE,
                                    NetworkRoute.PROXY,
                                    NetworkRoute.FOLLOW_DEFAULT,
                                ),
                                onSelect = { route ->
                                    updateRouting { current ->
                                        val routes = current.scopeRoutes.toMutableMap()
                                        if (route == NetworkRoute.FOLLOW_DEFAULT) {
                                            routes.remove(scope.key)
                                        } else {
                                            routes[scope.key] = route
                                        }
                                        current.copy(scopeRoutes = routes)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_network_routing_provider_title)) },
                ) {
                    settings.providers.forEach { provider ->
                        val providerId = provider.id.toString()
                        val selected =
                            routing.providerRoutes[providerId] ?: NetworkRoute.FOLLOW_DEFAULT
                        formItem(
                            headlineContent = {
                                Text(provider.name)
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        stringResource(
                                            R.string.setting_page_network_routing_effective,
                                            routeName(routing.resolveRoute(providerId = providerId)),
                                        ),
                                    )
                                    if (selected == NetworkRoute.PROXY && !proxyConfigured) {
                                        Text(stringResource(R.string.setting_page_network_routing_no_proxy))
                                    }
                                    if (selected == NetworkRoute.TAILSCALE) {
                                        Text(stringResource(R.string.setting_page_network_routing_tailscale_note))
                                    }
                                }
                            },
                        ) {
                            RouteChoiceButtons(
                                selected = selected,
                                options = listOf(
                                    NetworkRoute.DIRECT,
                                    NetworkRoute.TAILSCALE,
                                    NetworkRoute.PROXY,
                                    NetworkRoute.FOLLOW_DEFAULT,
                                ),
                                onSelect = { route ->
                                    updateRouting { current ->
                                        val routes = current.providerRoutes.toMutableMap()
                                        if (route == NetworkRoute.FOLLOW_DEFAULT) {
                                            routes.remove(providerId)
                                        } else {
                                            routes[providerId] = route
                                        }
                                        current.copy(providerRoutes = routes)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        onClick = {
                            updateRouting { it.copy(forceDirect = !it.forceDirect) }
                        },
                        headlineContent = {
                            Text(
                                stringResource(
                                    if (routing.forceDirect) {
                                        R.string.setting_page_network_routing_resume
                                    } else {
                                        R.string.setting_page_network_routing_force_direct
                                    },
                                ),
                            )
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_network_routing_force_direct_desc))
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            headlineColor = MaterialTheme.colorScheme.onErrorContainer,
                            supportingColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteChoiceButtons(
    selected: NetworkRoute,
    options: List<NetworkRoute>,
    onSelect: (NetworkRoute) -> Unit,
) {
    ConnectedChoiceButtons(
        options = options,
        selected = selected,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth(),
    ) { option, _ ->
        Text(routeName(option))
    }
}

@Composable
private fun routeName(route: NetworkRoute): String = stringResource(
    when (route) {
        NetworkRoute.DIRECT -> R.string.setting_page_network_routing_route_direct
        NetworkRoute.TAILSCALE -> R.string.setting_page_network_routing_route_tailscale
        NetworkRoute.PROXY -> R.string.setting_page_network_routing_route_proxy
        NetworkRoute.FOLLOW_DEFAULT -> R.string.setting_page_network_routing_route_default
    },
)

@Composable
private fun scopeName(scope: NetworkScope): String = stringResource(
    when (scope) {
        NetworkScope.SHELL -> R.string.setting_page_network_routing_scope_shell
        NetworkScope.BROWSER -> R.string.setting_page_network_routing_scope_browser
        NetworkScope.MCP -> R.string.setting_page_network_routing_scope_mcp
        NetworkScope.BACKUP -> R.string.setting_page_network_routing_scope_backup
    },
)
