package me.rerere.rikkahub.ui.pages.setting

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Router
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.HostAddressOverride
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.network.AppDnsResolver
import me.rerere.rikkahub.data.network.parseFixedAddress
import me.rerere.rikkahub.data.network.toProxyOrNull
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val PROXY_TEST_URL = "https://www.google.com/generate_204"
private const val DEFAULT_DNS_TEST_DOMAIN = "www.baidu.com"

@Composable
fun SettingPreferencesNetworkPage(vm: SettingVM = koinViewModel()) {
    val httpClient = koinInject<OkHttpClient>()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val net = settings.networkSetting
    var userAgent by remember(net.userAgent) {
        mutableStateOf(net.userAgent)
    }
    var proxyUrl by remember(net.proxyUrl) {
        mutableStateOf(net.proxyUrl)
    }
    var proxyUsername by remember(net.proxyUsername) {
        mutableStateOf(net.proxyUsername)
    }
    var proxyPassword by remember(net.proxyPassword) {
        mutableStateOf(net.proxyPassword)
    }
    var proxyUrlDraft by remember { mutableStateOf("") }
    var proxyUsernameDraft by remember { mutableStateOf("") }
    var proxyPasswordDraft by remember { mutableStateOf("") }
    var proxyPasswordVisible by remember { mutableStateOf(false) }
    var proxyDialogVisible by remember { mutableStateOf(false) }

    var dnsDialogVisible by remember { mutableStateOf(false) }
    var dnsModeDraft by remember { mutableStateOf(NetworkSetting.DNS_MODE_SYSTEM) }
    var dohUrlDraft by remember { mutableStateOf(NetworkSetting.DEFAULT_DOH_URL) }
    var dotHostDraft by remember { mutableStateOf(NetworkSetting.DEFAULT_DOT_HOST) }
    var customDnsDraft by remember { mutableStateOf(NetworkSetting.DEFAULT_BOOTSTRAP_SERVERS) }
    var dnsFallbackDraft by remember { mutableStateOf(true) }
    var dnsIpv6Draft by remember { mutableStateOf(false) }
    var dnsTestDomain by remember { mutableStateOf(DEFAULT_DNS_TEST_DOMAIN) }
    var dnsTesting by remember { mutableStateOf(false) }

    var hostOverrideDialogVisible by remember { mutableStateOf(false) }
    var hostOverrideDrafts by remember { mutableStateOf(emptyList<HostAddressOverride>()) }
    var hostOverrideHostDraft by remember { mutableStateOf("") }
    var hostOverrideAddressDraft by remember { mutableStateOf("") }
    val defaultUserAgent = "RikkaHub-Agents-Android/${BuildConfig.VERSION_NAME}"
    val proxyUrlInvalid = proxyUrlDraft.isNotBlank() && proxyUrlDraft.toProxyOrNull() == null
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    var proxyTesting by remember { mutableStateOf(false) }

    fun updateUserAgent(value: String) {
        userAgent = value
        vm.updateSettings { current ->
            current.copy(
                networkSetting = current.networkSetting.copy(userAgent = value),
            )
        }
    }

    fun saveProxy() {
        vm.updateSettings { current ->
            current.copy(
                networkSetting = current.networkSetting.copy(
                    proxyUrl = proxyUrlDraft,
                    proxyUsername = proxyUsernameDraft,
                    proxyPassword = proxyPasswordDraft,
                ),
            )
        }
    }

    fun resetProxy() {
        proxyUrlDraft = ""
        proxyUsernameDraft = ""
        proxyPasswordDraft = ""
    }

    fun saveDnsConfig() {
        AppDnsResolver.invalidateCache()
        vm.updateSettings { current ->
            current.copy(
                networkSetting = current.networkSetting.copy(
                    dnsMode = dnsModeDraft,
                    dohUrl = dohUrlDraft.trim().ifEmpty { NetworkSetting.DEFAULT_DOH_URL },
                    dotHost = dotHostDraft.trim().ifEmpty { NetworkSetting.DEFAULT_DOT_HOST },
                    customDnsServers = customDnsDraft.trim().ifEmpty { NetworkSetting.DEFAULT_BOOTSTRAP_SERVERS },
                    dnsFallbackToSystem = dnsFallbackDraft,
                    dnsEnableIpv6 = dnsIpv6Draft,
                ),
            )
        }
    }

    fun saveHostAddressOverrides(entries: List<HostAddressOverride>) {
        vm.updateSettings { current ->
            current.copy(
                networkSetting = current.networkSetting.copy(hostAddressOverrides = entries),
            )
        }
    }

    fun testProxy() {
        val proxy = proxyUrl.toProxyOrNull() ?: return
        if (proxyTesting) return
        proxyTesting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val testClient = httpClient.newBuilder()
                        .proxy(proxy)
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .callTimeout(15, TimeUnit.SECONDS)
                        .build()
                    testClient.newCall(
                        Request.Builder()
                            .url(PROXY_TEST_URL)
                            .head()
                            .build()
                    ).execute().use { response ->
                        if (response.code != 204) {
                            throw IOException("HTTP ${response.code} ${response.message}")
                        }
                    }
                }
            }
            result.onSuccess {
                toaster.show(
                    resources.getString(R.string.backup_page_connection_success),
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
            proxyTesting = false
        }
    }

    fun testDns(targetSetting: NetworkSetting = net) {
        if (dnsTesting) return
        dnsTesting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AppDnsResolver.testResolve(
                        domain = dnsTestDomain.ifBlank { DEFAULT_DNS_TEST_DOMAIN },
                        setting = targetSetting,
                    )
                }
            }
            result.onSuccess { outcome ->
                toaster.show(
                    resources.getString(
                        R.string.setting_page_preferences_network_dns_test_success,
                        outcome.domain,
                        outcome.addresses.take(3).joinToString(", "),
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

    if (proxyDialogVisible) {
        AlertDialog(
            onDismissRequest = { proxyDialogVisible = false },
            modifier = Modifier.imePadding(),
            title = {
                Text(stringResource(R.string.setting_page_preferences_network_proxy))
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = proxyUrlDraft,
                        onValueChange = { proxyUrlDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(stringResource(R.string.setting_page_preferences_network_proxy))
                        },
                        placeholder = { Text("http://127.0.0.1:7890") },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (proxyUrlInvalid) {
                                        R.string.setting_page_preferences_network_proxy_invalid
                                    } else {
                                        R.string.setting_page_preferences_network_proxy_desc
                                    }
                                )
                            )
                        },
                        isError = proxyUrlInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = proxyUsernameDraft,
                        onValueChange = { proxyUsernameDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.backup_page_username)) },
                        singleLine = true,
                    )
                    SecretRefField(
                        value = proxyPasswordDraft,
                        onValueChange = { proxyPasswordDraft = it },
                        label = stringResource(R.string.backup_page_password),
                        nameHint = "PROXY_PASSWORD",
                        singleLine = true,
                    )
                    TextButton(
                        onClick = ::resetProxy,
                        modifier = Modifier.align(Alignment.End),
                        enabled = proxyUrlDraft.isNotEmpty() ||
                            proxyUsernameDraft.isNotEmpty() ||
                            proxyPasswordDraft.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveProxy()
                        proxyDialogVisible = false
                    },
                    enabled = !proxyUrlInvalid,
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    if (dnsDialogVisible) {
        val customServersInvalid = customDnsDraft.isNotBlank() &&
            AppDnsResolver.parseServerList(customDnsDraft).isEmpty()
        AlertDialog(
            onDismissRequest = { dnsDialogVisible = false },
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
                                selected = dnsModeDraft == modeKey,
                                onClick = { dnsModeDraft = modeKey },
                                label = { Text(stringResource(labelRes)) },
                            )
                        }
                    }

                    if (dnsModeDraft != NetworkSetting.DNS_MODE_SYSTEM) {
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
                                val selected = when (dnsModeDraft) {
                                    NetworkSetting.DNS_MODE_DOH -> dohUrlDraft.trim() == preset.dohUrl
                                    NetworkSetting.DNS_MODE_DOT -> dotHostDraft.trim() == preset.dotHost
                                    else -> customDnsDraft.trim() == preset.bootstrapIps
                                }
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        dohUrlDraft = preset.dohUrl
                                        dotHostDraft = preset.dotHost
                                        customDnsDraft = preset.bootstrapIps
                                    },
                                    label = { Text(preset.label) },
                                )
                            }
                        }
                    }

                    if (dnsModeDraft == NetworkSetting.DNS_MODE_DOH) {
                        OutlinedTextField(
                            value = dohUrlDraft,
                            onValueChange = { dohUrlDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(stringResource(R.string.setting_page_preferences_network_dns_doh_url))
                            },
                            placeholder = { Text(NetworkSetting.DEFAULT_DOH_URL) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true,
                        )
                    }

                    if (dnsModeDraft == NetworkSetting.DNS_MODE_DOT) {
                        OutlinedTextField(
                            value = dotHostDraft,
                            onValueChange = { dotHostDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(stringResource(R.string.setting_page_preferences_network_dns_dot_host))
                            },
                            placeholder = { Text(NetworkSetting.DEFAULT_DOT_HOST) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true,
                        )
                    }

                    if (dnsModeDraft != NetworkSetting.DNS_MODE_SYSTEM) {
                        OutlinedTextField(
                            value = customDnsDraft,
                            onValueChange = { customDnsDraft = it },
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
                                checked = dnsFallbackDraft,
                                onCheckedChange = { dnsFallbackDraft = it },
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
                                checked = dnsIpv6Draft,
                                onCheckedChange = { dnsIpv6Draft = it },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = dnsTestDomain,
                        onValueChange = { dnsTestDomain = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(stringResource(R.string.setting_page_preferences_network_dns_test_domain))
                        },
                        placeholder = { Text(DEFAULT_DNS_TEST_DOMAIN) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                    )
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
                TextButton(onClick = { dnsDialogVisible = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (hostOverrideDialogVisible) {
        val canAdd =
            hostOverrideHostDraft.isNotBlank() &&
                parseFixedAddress(hostOverrideAddressDraft) != null
        AlertDialog(
            onDismissRequest = { hostOverrideDialogVisible = false },
            modifier = Modifier.imePadding(),
            title = {
                Text(stringResource(R.string.setting_page_preferences_network_host_overrides))
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (hostOverrideDrafts.isEmpty()) {
                        Text(
                            stringResource(
                                R.string.setting_page_preferences_network_host_overrides_empty,
                            )
                        )
                    }
                    hostOverrideDrafts.forEachIndexed { index, entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${entry.host} → ${entry.address}",
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = entry.enabled,
                                onCheckedChange = { checked ->
                                    hostOverrideDrafts =
                                        hostOverrideDrafts.mapIndexed { i, item ->
                                            if (i == index) item.copy(enabled = checked) else item
                                        }
                                },
                            )
                            IconButton(
                                onClick = {
                                    hostOverrideDrafts = hostOverrideDrafts.filterIndexed { i, _ ->
                                        i != index
                                    }
                                },
                            ) {
                                Icon(HugeIcons.Delete01, contentDescription = null)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = hostOverrideHostDraft,
                        onValueChange = { hostOverrideHostDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                stringResource(
                                    R.string.setting_page_preferences_network_host_overrides_host,
                                )
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = hostOverrideAddressDraft,
                        onValueChange = { hostOverrideAddressDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                stringResource(
                                    R.string.setting_page_preferences_network_host_overrides_address,
                                )
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        supportingText = {
                            if (hostOverrideAddressDraft.isNotBlank() &&
                                parseFixedAddress(hostOverrideAddressDraft) == null
                            ) {
                                Text(
                                    stringResource(
                                        R.string.setting_page_preferences_network_host_overrides_invalid,
                                    )
                                )
                            }
                        },
                        isError = hostOverrideAddressDraft.isNotBlank() &&
                            parseFixedAddress(hostOverrideAddressDraft) == null,
                    )
                    TextButton(
                        onClick = {
                            hostOverrideDrafts = hostOverrideDrafts + HostAddressOverride(
                                host = hostOverrideHostDraft.trim(),
                                address = hostOverrideAddressDraft.trim(),
                            )
                            hostOverrideHostDraft = ""
                            hostOverrideAddressDraft = ""
                        },
                        enabled = canAdd,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(
                            stringResource(
                                R.string.setting_page_preferences_network_host_overrides_add,
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val saved = vm.settings.value.networkSetting.hostAddressOverrides
                        saveHostAddressOverrides(
                            if (hostOverrideDrafts.isEmpty() && saved.isNotEmpty()) saved else hostOverrideDrafts,
                        )
                        hostOverrideDialogVisible = false
                    },
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { hostOverrideDialogVisible = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
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
                        item(
                            headlineContent = {
                                Text(
                                    stringResource(
                                        R.string.setting_page_preferences_network_auto_retry_count_desc,
                                        settings.responseStreamMaxRetries,
                                    )
                                )
                            },
                            trailingContent = {
                                Slider(
                                    value = settings.responseStreamMaxRetries.toFloat(),
                                    onValueChange = { v ->
                                        vm.updateSettings { current -> current.copy(responseStreamMaxRetries = v.roundToInt()) }
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
                    title = {
                        Text(stringResource(R.string.setting_page_preferences_network_proxy))
                    },
                ) {
                    item(
                        onClick = {
                            proxyUrlDraft = proxyUrl
                            proxyUsernameDraft = proxyUsername
                            proxyPasswordDraft = proxyPassword
                            proxyPasswordVisible = false
                            proxyDialogVisible = true
                        },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Router,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_config))
                        },
                        supportingContent = {
                            Text(
                                if (proxyUrl.isBlank()) {
                                    stringResource(
                                        R.string.setting_page_preferences_network_proxy_desc
                                    )
                                } else {
                                    proxyUrl
                                }
                            )
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_provider_page_test_connection))
                        },
                        trailingContent = {
                            TextButton(
                                onClick = ::testProxy,
                                enabled = proxyUrl.toProxyOrNull() != null && !proxyTesting,
                            ) {
                                if (proxyTesting) {
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
                TunnelSettingSection(vm)
            }
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
                            dnsModeDraft = net.dnsMode
                            dohUrlDraft = net.dohUrl
                            dotHostDraft = net.dotHost
                            customDnsDraft = net.customDnsServers
                            dnsFallbackDraft = net.dnsFallbackToSystem
                            dnsIpv6Draft = net.dnsEnableIpv6
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
                            Text(dnsTestDomain.ifBlank { DEFAULT_DNS_TEST_DOMAIN })
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
                val hostOverrides = net.hostAddressOverrides
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(stringResource(R.string.setting_page_preferences_network_host_overrides))
                    },
                ) {
                    item(
                        onClick = {
                            hostOverrideDrafts = vm.settings.value.networkSetting.hostAddressOverrides
                            hostOverrideHostDraft = ""
                            hostOverrideAddressDraft = ""
                            hostOverrideDialogVisible = true
                        },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.ServerStack01,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_host_overrides_desc))
                        },
                        supportingContent = {
                            Text(
                                if (hostOverrides.isEmpty()) {
                                    stringResource(
                                        R.string.setting_page_preferences_network_host_overrides_empty
                                    )
                                } else {
                                    stringResource(
                                        R.string.setting_page_preferences_network_host_overrides_count,
                                        hostOverrides.size,
                                    )
                                }
                            )
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
