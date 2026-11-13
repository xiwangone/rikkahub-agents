package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.network.AppDnsResolver
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.setting.DraftState
import me.rerere.rikkahub.ui.components.setting.dohUrlValid
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.workspace.ShellDnsConfig
import org.koin.androidx.compose.koinViewModel

/** DNS 弹窗草稿：模式 + 各模式地址 + 回退 / IPv6 开关 + 测试域名（打开弹窗时从已保存值同步初始化，取消即丢弃）。 */
internal data class DnsDraft(
    val mode: String = NetworkSetting.DNS_MODE_SYSTEM,
    val dohUrl: String = NetworkSetting.DEFAULT_DOH_URL,
    val dotHost: String = NetworkSetting.DEFAULT_DOT_HOST,
    val customServers: String = NetworkSetting.DEFAULT_BOOTSTRAP_SERVERS,
    val fallbackToSystem: Boolean = true,
    val enableIpv6: Boolean = false,
    val testDomain: String = NetworkSetting.DEFAULT_DNS_TEST_DOMAIN,
) {
    companion object {
        fun from(net: NetworkSetting) = DnsDraft(
            mode = net.dnsMode,
            dohUrl = net.dohUrl,
            dotHost = net.dotHost,
            customServers = net.customDnsServers,
            fallbackToSystem = net.dnsFallbackToSystem,
            enableIpv6 = net.dnsEnableIpv6,
            testDomain = net.dnsTestDomain,
        )
    }
}

@Composable
fun SettingNetworkDnsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val net = settings.networkSetting
    var dnsDialogVisible by remember { mutableStateOf(false) }
    val dnsDraft = remember { DraftState(DnsDraft.from(net)) }
    var dnsTesting by remember { mutableStateOf(false) }

    val savedShellDnsMode = ShellDnsConfig.normalizeMode(settings.shellDnsMode)
    val savedShellDnsText = remember(settings.shellDnsServers) { settings.shellDnsServers.joinToString("\n") }
    var shellDnsModeDraft by remember(savedShellDnsMode) { mutableStateOf(savedShellDnsMode) }
    var shellDnsServersDraft by remember(savedShellDnsText) { mutableStateOf(savedShellDnsText) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val toaster = LocalToaster.current

    fun loadDnsDrafts() {
        dnsDraft.load(DnsDraft.from(net))
    }

    fun saveDnsConfig() {
        AppDnsResolver.invalidateCache()
        val saved = dnsDraft.save()
        vm.updateSettings { current ->
            current.copy(
                networkSetting = buildDnsTestSetting(
                    base = current.networkSetting,
                    mode = saved.mode,
                    dohUrl = saved.dohUrl,
                    dotHost = saved.dotHost,
                    customServers = saved.customServers,
                    fallbackToSystem = saved.fallbackToSystem,
                    enableIpv6 = saved.enableIpv6,
                    testDomain = saved.testDomain,
                ),
            )
        }
    }

    fun saveShellDns() {
        val normalizedMode = ShellDnsConfig.normalizeMode(shellDnsModeDraft)
        val parsedServers = ShellDnsConfig.parseServers(shellDnsServersDraft)
        vm.updateSettings { current ->
            current.copy(
                shellDnsMode = normalizedMode,
                shellDnsServers = parsedServers,
            )
        }
        toaster.show(
            resources.getString(R.string.setting_page_preferences_network_shell_dns_saved),
            type = ToastType.Success,
        )
    }

    fun testDns(targetSetting: NetworkSetting = net) {
        if (dnsTesting) return
        dnsTesting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AppDnsResolver.testResolve(
                        domain = targetSetting.dnsTestDomain.ifBlank { NetworkSetting.DEFAULT_DNS_TEST_DOMAIN },
                        setting = targetSetting,
                    )
                }
            }
            result.onSuccess { outcome ->
                // IPv4 / IPv6 分组展示：堆在一起时前缀相同，看不出哪条是 v6，也不便判断 AAAA 是否真的拿到了。
                val ipSummary =
                    buildString {
                        val v4 = outcome.addresses.filter { ':' !in it }
                        val v6 = outcome.addresses.filter { ':' in it }
                        if (v4.isNotEmpty()) append("IPv4 ").append(v4.take(2).joinToString(", "))
                        if (v6.isNotEmpty()) {
                            if (isNotEmpty()) append("  ·  ")
                            append("IPv6 ").append(v6.take(2).joinToString(", "))
                        }
                        if (isEmpty()) append("—")
                    }
                toaster.show(
                    resources.getString(
                        R.string.setting_page_preferences_network_dns_test_success,
                        outcome.domain,
                        ipSummary,
                        outcome.elapsedMs,
                    ),
                    type = ToastType.Success,
                )
            }.onFailure { error ->
                toaster.show(
                    resources.getString(
                        R.string.backup_page_connection_failed,
                        error.message.orEmpty(),
                    ),
                    type = ToastType.Error,
                )
            }
            dnsTesting = false
        }
    }

    if (dnsDialogVisible) {
        val customServersInvalid = dnsDraft.draft.customServers.isNotBlank() &&
            AppDnsResolver.parseServerList(dnsDraft.draft.customServers).isEmpty()
        AlertDialog(
            onDismissRequest = {
                dnsDraft.discard()
                dnsDialogVisible = false
            },
            modifier = Modifier.imePadding(),
            title = {
                Text(stringResource(R.string.setting_page_preferences_network_dns_title))
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_preferences_network_dns_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        listOf(
                            NetworkSetting.DNS_MODE_SYSTEM to R.string.setting_page_preferences_network_dns_mode_system,
                            NetworkSetting.DNS_MODE_DOH to R.string.setting_page_preferences_network_dns_mode_doh,
                            NetworkSetting.DNS_MODE_DOT to R.string.setting_page_preferences_network_dns_mode_dot,
                            NetworkSetting.DNS_MODE_CUSTOM to R.string.setting_page_preferences_network_dns_mode_custom,
                        ).forEach { (modeKey, labelRes) ->
                            FilterChip(
                                selected = dnsDraft.draft.mode == modeKey,
                                onClick = { dnsDraft.edit { it.copy(mode = modeKey) } },
                                label = { Text(stringResource(labelRes)) },
                            )
                        }
                    }

                    if (dnsDraft.draft.mode != NetworkSetting.DNS_MODE_SYSTEM) {
                        Text(
                            text = stringResource(R.string.setting_page_preferences_network_dns_presets),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        ) {
                            AppDnsResolver.PRESETS.forEach { preset ->
                                val selected = when (dnsDraft.draft.mode) {
                                    NetworkSetting.DNS_MODE_DOH -> dnsDraft.draft.dohUrl.trim() == preset.dohUrl
                                    NetworkSetting.DNS_MODE_DOT -> dnsDraft.draft.dotHost.trim() == preset.dotHost
                                    else -> dnsDraft.draft.customServers.trim() == preset.bootstrapIps
                                }
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        dnsDraft.edit {
                                            it.copy(
                                                dohUrl = preset.dohUrl,
                                                dotHost = preset.dotHost,
                                                customServers = preset.bootstrapIps,
                                            )
                                        }
                                    },
                                    label = { Text(preset.label) },
                                )
                            }
                        }
                    }

                    if (dnsDraft.draft.mode == NetworkSetting.DNS_MODE_DOH) {
                        OutlinedTextField(
                            value = dnsDraft.draft.dohUrl,
                            onValueChange = { newValue -> dnsDraft.edit { it.copy(dohUrl = newValue) } },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(stringResource(R.string.setting_page_preferences_network_dns_doh_url))
                            },
                            placeholder = { Text(NetworkSetting.DEFAULT_DOH_URL) },
                            isError = dnsDraft.draft.dohUrl.isNotBlank() && !dohUrlValid(dnsDraft.draft.dohUrl),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true,
                        )
                    }

                    if (dnsDraft.draft.mode == NetworkSetting.DNS_MODE_DOT) {
                        OutlinedTextField(
                            value = dnsDraft.draft.dotHost,
                            onValueChange = { newValue -> dnsDraft.edit { it.copy(dotHost = newValue) } },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(stringResource(R.string.setting_page_preferences_network_dns_dot_host))
                            },
                            placeholder = { Text(NetworkSetting.DEFAULT_DOT_HOST) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true,
                        )
                    }

                    if (dnsDraft.draft.mode != NetworkSetting.DNS_MODE_SYSTEM) {
                        OutlinedTextField(
                            value = dnsDraft.draft.customServers,
                            onValueChange = { newValue -> dnsDraft.edit { it.copy(customServers = newValue) } },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(stringResource(R.string.setting_page_preferences_network_dns_custom_servers))
                            },
                            placeholder = { Text(NetworkSetting.DEFAULT_BOOTSTRAP_SERVERS) },
                            supportingText = {
                                Text(
                                    stringResource(
                                        if (customServersInvalid) {
                                            R.string.setting_page_preferences_network_host_overrides_invalid
                                        } else {
                                            R.string.setting_page_preferences_network_dns_custom_servers_desc
                                        }
                                    )
                                )
                            },
                            isError = customServersInvalid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.setting_page_preferences_network_dns_fallback_system),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = dnsDraft.draft.fallbackToSystem,
                                onCheckedChange = { newValue -> dnsDraft.edit { it.copy(fallbackToSystem = newValue) } },
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.setting_page_preferences_network_dns_enable_ipv6),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = dnsDraft.draft.enableIpv6,
                                onCheckedChange = { newValue -> dnsDraft.edit { it.copy(enableIpv6 = newValue) } },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = dnsDraft.draft.testDomain,
                        onValueChange = { newValue -> dnsDraft.edit { it.copy(testDomain = newValue) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(stringResource(R.string.setting_page_preferences_network_dns_test_domain))
                        },
                        placeholder = { Text(NetworkSetting.DEFAULT_DNS_TEST_DOMAIN) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                testDns(
                                    buildDnsTestSetting(
                                        base = net,
                                        mode = dnsDraft.draft.mode,
                                        dohUrl = dnsDraft.draft.dohUrl,
                                        dotHost = dnsDraft.draft.dotHost,
                                        customServers = dnsDraft.draft.customServers,
                                        fallbackToSystem = dnsDraft.draft.fallbackToSystem,
                                        enableIpv6 = dnsDraft.draft.enableIpv6,
                                        testDomain = dnsDraft.draft.testDomain,
                                    ),
                                )
                            },
                            enabled = !dnsTesting,
                        ) {
                            if (dnsTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(stringResource(R.string.setting_provider_page_test))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveDnsConfig()
                        dnsDialogVisible = false
                    },
                    enabled = !customServersInvalid,
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dnsDraft.discard()
                        dnsDialogVisible = false
                    },
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_preferences_network_dns_title)) },
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
                val dnsSummary = when (net.dnsMode) {
                    NetworkSetting.DNS_MODE_DOH ->
                        stringResource(R.string.setting_page_preferences_network_dns_mode_doh) +
                            " · " + net.dohUrl.ifBlank { NetworkSetting.DEFAULT_DOH_URL }
                    NetworkSetting.DNS_MODE_DOT ->
                        stringResource(R.string.setting_page_preferences_network_dns_mode_dot) +
                            " · " + net.dotHost.ifBlank { NetworkSetting.DEFAULT_DOT_HOST }
                    NetworkSetting.DNS_MODE_CUSTOM ->
                        stringResource(R.string.setting_page_preferences_network_dns_mode_custom) +
                            " · " + net.customDnsServers.ifBlank { NetworkSetting.DEFAULT_BOOTSTRAP_SERVERS }
                    else -> stringResource(R.string.setting_page_preferences_network_dns_mode_system)
                }
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(stringResource(R.string.setting_page_preferences_network_dns_title))
                    },
                ) {
                    item(
                        onClick = {
                            loadDnsDrafts()
                            dnsDialogVisible = true
                        },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Shield01,
                                containerColor = if (net.dnsMode != NetworkSetting.DNS_MODE_SYSTEM) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                contentColor = if (net.dnsMode != NetworkSetting.DNS_MODE_SYSTEM) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_dns_desc))
                        },
                        supportingContent = {
                            Text(dnsSummary)
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_provider_page_test_connection))
                        },
                        supportingContent = {
                            Text(net.dnsTestDomain.ifBlank { NetworkSetting.DEFAULT_DNS_TEST_DOMAIN })
                        },
                        trailingContent = {
                            TextButton(
                                onClick = { testDns(net) },
                                enabled = !dnsTesting,
                            ) {
                                if (dnsTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(stringResource(R.string.setting_provider_page_test))
                                }
                            }
                        },
                    )
                }
            }
            item {
                val effectiveServers =
                    ShellDnsConfig.effectiveServers(
                        settings.shellDnsMode,
                        settings.shellDnsServers.joinToString("\n"),
                    )
                val effectiveSummary =
                    if (effectiveServers.isEmpty()) {
                        stringResource(R.string.setting_page_preferences_network_shell_dns_effective_auto)
                    } else {
                        effectiveServers.joinToString(", ")
                    }
                val parsedDraftServers = ShellDnsConfig.parseServers(shellDnsServersDraft)
                val customDraftInvalid =
                    shellDnsModeDraft == ShellDnsConfig.MODE_CUSTOM && parsedDraftServers.isEmpty()

                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(stringResource(R.string.setting_page_preferences_network_shell_dns_title))
                    },
                ) {
                    item(
                        headlineContent = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(
                                    Triple(
                                        ShellDnsConfig.MODE_AUTO,
                                        R.string.setting_page_preferences_network_shell_dns_mode_auto,
                                        R.string.setting_page_preferences_network_shell_dns_mode_auto_desc,
                                    ),
                                    Triple(
                                        ShellDnsConfig.MODE_CUSTOM,
                                        R.string.setting_page_preferences_network_shell_dns_mode_custom,
                                        R.string.setting_page_preferences_network_shell_dns_mode_custom_desc,
                                    ),
                                ).forEach { (modeKey, titleRes, descRes) ->
                                    val selected = shellDnsModeDraft == modeKey
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { shellDnsModeDraft = modeKey }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = selected,
                                            onClick = { shellDnsModeDraft = modeKey },
                                        )
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(start = 8.dp),
                                        ) {
                                            Text(
                                                text = stringResource(titleRes),
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                            Text(
                                                text = stringResource(descRes),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = shellDnsServersDraft,
                                    onValueChange = { shellDnsServersDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = shellDnsModeDraft == ShellDnsConfig.MODE_CUSTOM,
                                    label = {
                                        Text(stringResource(R.string.setting_page_preferences_network_shell_dns_servers_label))
                                    },
                                    placeholder = {
                                        Text("223.5.5.5\n119.29.29.29")
                                    },
                                    supportingText = {
                                        Text(
                                            stringResource(
                                                if (customDraftInvalid) {
                                                    R.string.setting_page_preferences_network_shell_dns_servers_invalid
                                                } else {
                                                    R.string.setting_page_preferences_network_shell_dns_servers_hint
                                                },
                                            ),
                                        )
                                    },
                                    isError = customDraftInvalid,
                                    minLines = 2,
                                    maxLines = 5,
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.setting_page_preferences_network_shell_dns_effective,
                                            effectiveSummary,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    FilledTonalButton(
                                        onClick = ::saveShellDns,
                                        enabled = !customDraftInvalid,
                                    ) {
                                        Text(stringResource(R.string.setting_page_preferences_network_shell_dns_save))
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 用弹窗草稿拼一份待测试/待保存的 [NetworkSetting]：其余字段沿用 [base]，
 * 草稿字段按保存时同一口径归一化（去空白、空值回落默认）。
 *
 * 保存与弹窗内测试共用它，避免两处归一化口径漂移：弹窗里测的就是将要保存的配置。
 */
internal fun buildDnsTestSetting(
    base: NetworkSetting,
    mode: String,
    dohUrl: String,
    dotHost: String,
    customServers: String,
    fallbackToSystem: Boolean,
    enableIpv6: Boolean,
    testDomain: String,
): NetworkSetting = base.copy(
    dnsMode = mode,
    dohUrl = dohUrl.trim().ifEmpty { NetworkSetting.DEFAULT_DOH_URL },
    dotHost = dotHost.trim().ifEmpty { NetworkSetting.DEFAULT_DOT_HOST },
    customDnsServers = customServers.trim().ifEmpty { NetworkSetting.DEFAULT_BOOTSTRAP_SERVERS },
    dnsFallbackToSystem = fallbackToSystem,
    dnsEnableIpv6 = enableIpv6,
    dnsTestDomain = testDomain.trim().ifEmpty { NetworkSetting.DEFAULT_DNS_TEST_DOMAIN },
)
