package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.network.NetworkRoute
import me.rerere.rikkahub.data.network.NetworkRoutingSettings
import me.rerere.rikkahub.data.network.NetworkScope
import me.rerere.rikkahub.data.network.WebViewProxy
import me.rerere.rikkahub.data.network.isOnDeviceProvider
import me.rerere.rikkahub.data.network.resolveProxyPasswordForAuth
import me.rerere.rikkahub.data.network.toProxyOrNull
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import org.koin.androidx.compose.koinViewModel
import androidx.compose.material3.TopAppBar

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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // On-device providers make no network requests, so they have no route to set.
    val routableProviders = settings.providers.filterNot(::isOnDeviceProvider)
    val customizedProviders =
        routableProviders.filter { routing.hasProviderOverride(it.id.toString()) }
    val followingDefaultProviders =
        routableProviders.filterNot { routing.hasProviderOverride(it.id.toString()) }
    // Fold state is UI-local only; it is not persisted and does not touch the schema.
    var providerListExpanded by remember { mutableStateOf(false) }
    var batchDialogVisible by remember { mutableStateOf(false) }

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
            TopAppBar(
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
            if (routableProviders.isNotEmpty()) {
                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.setting_page_network_routing_provider_title))
                                TextButton(
                                    onClick = { batchDialogVisible = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text(stringResource(R.string.setting_page_network_routing_batch))
                                }
                            }
                        },
                    ) {
                        customizedProviders.forEach { provider ->
                            ProviderRouteItem(
                                provider = provider,
                                routing = routing,
                                proxyConfigured = proxyConfigured,
                                onRouteSelect = { providerId, route ->
                                    updateRouting { it.withProviderRoutes(listOf(providerId), route) }
                                },
                            )
                        }
                        if (providerListExpanded) {
                            followingDefaultProviders.forEach { provider ->
                                ProviderRouteItem(
                                    provider = provider,
                                    routing = routing,
                                    proxyConfigured = proxyConfigured,
                                    onRouteSelect = { providerId, route ->
                                        updateRouting { it.withProviderRoutes(listOf(providerId), route) }
                                    },
                                )
                            }
                        }
                        if (followingDefaultProviders.isNotEmpty()) {
                            item(
                                onClick = { providerListExpanded = !providerListExpanded },
                                headlineContent = {
                                    Text(
                                        stringResource(
                                            R.string.setting_page_network_routing_provider_rest,
                                            followingDefaultProviders.size,
                                        ),
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = if (providerListExpanded) {
                                            HugeIcons.ArrowUp01
                                        } else {
                                            HugeIcons.ArrowDown01
                                        },
                                        contentDescription = null,
                                    )
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
        if (batchDialogVisible) {
            ProviderBatchRouteDialog(
                providers = routableProviders,
                routing = routing,
                proxyConfigured = proxyConfigured,
                onDismiss = { batchDialogVisible = false },
                onApply = { providerIds, route ->
                    updateRouting { it.withProviderRoutes(providerIds, route) }
                    batchDialogVisible = false
                },
            )
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

/** One provider row: name + effective route + the four route buttons. */
@Composable
private fun CardGroupScope.ProviderRouteItem(
    provider: ProviderSetting,
    routing: NetworkRoutingSettings,
    proxyConfigured: Boolean,
    onRouteSelect: (providerId: String, route: NetworkRoute) -> Unit,
) {
    val providerId = provider.id.toString()
    val selected = routing.providerRoutes[providerId] ?: NetworkRoute.FOLLOW_DEFAULT
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
            onSelect = { route -> onRouteSelect(providerId, route) },
        )
    }
}

/**
 * Batch route dialog: check any number of providers, pick a route, apply it to
 * all of them at once. Picking "Default" clears the override for the selection.
 */
@Composable
private fun ProviderBatchRouteDialog(
    providers: List<ProviderSetting>,
    routing: NetworkRoutingSettings,
    proxyConfigured: Boolean,
    onDismiss: () -> Unit,
    onApply: (providerIds: List<String>, route: NetworkRoute) -> Unit,
) {
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var batchRoute by remember { mutableStateOf(routing.defaultRoute) }
    val allSelected = providers.isNotEmpty() && selectedIds.size == providers.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_page_network_routing_batch)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_page_network_routing_batch_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { checked ->
                            selectedIds = if (checked) {
                                providers.map { it.id.toString() }.toSet()
                            } else {
                                emptySet()
                            }
                        },
                    )
                    Text(stringResource(R.string.setting_page_network_routing_batch_select_all))
                }
                providers.forEach { provider ->
                    val providerId = provider.id.toString()
                    val selected =
                        routing.providerRoutes[providerId] ?: NetworkRoute.FOLLOW_DEFAULT
                    ListSelectableItem(
                        key = providerId,
                        selectedKeys = selectedIds.toList(),
                        onSelectChange = { key ->
                            val id = key as String
                            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                        },
                    ) {
                        Column {
                            Text(provider.name)
                            Text(
                                text = stringResource(
                                    R.string.setting_page_network_routing_effective,
                                    routeName(routing.resolveRoute(providerId = providerId)),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (selected == NetworkRoute.PROXY && !proxyConfigured) {
                                Text(
                                    text = stringResource(R.string.setting_page_network_routing_no_proxy),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                RouteChoiceButtons(
                    selected = batchRoute,
                    options = listOf(
                        NetworkRoute.DIRECT,
                        NetworkRoute.TAILSCALE,
                        NetworkRoute.PROXY,
                        NetworkRoute.FOLLOW_DEFAULT,
                    ),
                    onSelect = { batchRoute = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(selectedIds.toList(), batchRoute) },
                enabled = selectedIds.isNotEmpty(),
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
