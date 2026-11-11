package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Router
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@Composable
fun SettingPreferencesNetworkPage(vm: SettingVM = koinViewModel()) {
    val nav = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val net = settings.networkSetting
    var userAgent by remember(net.userAgent) {
        mutableStateOf(net.userAgent)
    }
    val defaultUserAgent = "RikkaHub-Agents-Android/${BuildConfig.VERSION_NAME}"
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun updateUserAgent(value: String) {
        userAgent = value
        vm.updateSettings { current ->
            current.copy(
                networkSetting = current.networkSetting.copy(userAgent = value),
            )
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_preferences_network)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(stringResource(R.string.setting_page_preferences_network_auto_retry))
                    },
                ) {
                    item(
                        onClick = {
                            val next = !settings.enableAutoRetry
                            vm.updateSettings { current -> current.copy(enableAutoRetry = next) }
                        },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Refresh,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_auto_retry_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.enableAutoRetry,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings { current -> current.copy(enableAutoRetry = enabled) }
                                },
                            )
                        },
                    )
                    if (settings.enableAutoRetry) {
                        var retryDraft by remember(settings.responseStreamMaxRetries) {
                            mutableStateOf(settings.responseStreamMaxRetries)
                        }
                        item(
                            headlineContent = {
                                Text(
                                    stringResource(
                                        R.string.setting_page_preferences_network_auto_retry_count_desc,
                                        retryDraft,
                                    )
                                )
                            },
                            trailingContent = {
                                Slider(
                                    value = retryDraft.toFloat(),
                                    onValueChange = { v ->
                                        retryDraft = v.roundToInt()
                                    },
                                    onValueChangeFinished = {
                                        val target = retryDraft
                                        vm.updateSettings { current -> current.copy(responseStreamMaxRetries = target) }
                                    },
                                    valueRange = 0f..10f,
                                    steps = 9,
                                    modifier = Modifier.width(180.dp),
                                )
                            },
                        )
                    }
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(stringResource(R.string.setting_page_preferences_network_user_agent))
                    },
                ) {
                    item(
                        headlineContent = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = userAgent,
                                    onValueChange = ::updateUserAgent,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        Text(stringResource(R.string.setting_page_preferences_network_user_agent))
                                    },
                                    placeholder = { Text(defaultUserAgent) },
                                    supportingText = {
                                        Text(
                                            stringResource(
                                                R.string.setting_page_preferences_network_user_agent_desc,
                                                defaultUserAgent,
                                            )
                                        )
                                    },
                                    singleLine = true,
                                )
                                TextButton(
                                    onClick = { updateUserAgent("") },
                                    enabled = userAgent.isNotEmpty(),
                                ) {
                                    Text(stringResource(R.string.setting_model_page_reset_to_default))
                                }
                            }
                        },
                    )
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        onClick = { nav.navigate(Screen.SettingNetworkProxy) },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Router,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_proxy))
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    item(
                        onClick = { nav.navigate(Screen.SettingNetworkDns) },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Shield01,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_dns_title))
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    item(
                        onClick = { nav.navigate(Screen.SettingNetworkHostOverrides) },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.ServerStack01,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_host_overrides))
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    item(
                        onClick = { nav.navigate(Screen.SettingNetworkTunnel) },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Internet,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        },
                        headlineContent = {
                            Text(stringResource(R.string.tunnel_enable_title))
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                }
            }
        }
    }
}
