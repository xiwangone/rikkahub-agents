package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AddCircle
import me.rerere.hugeicons.stroke.CoinsDollar
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Lock
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.quota.QuotaAuthType
import me.rerere.rikkahub.data.quota.QuotaCredentialManager
import me.rerere.rikkahub.data.quota.QuotaPlatform
import me.rerere.rikkahub.data.quota.QuotaPreferences
import me.rerere.rikkahub.data.quota.QuotaProviderConfig
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingQuotaPage() {
    val navController = LocalNavController.current
    val quotaPreferences: QuotaPreferences = koinInject()
    val context = androidx.compose.ui.platform.LocalContext.current
    val credentialManager: QuotaCredentialManager = koinInject()
    val scope = rememberCoroutineScope()

    val quotaEnabled by quotaPreferences.quotaEnabled.collectAsState(initial = false)
    val overlayEnabled by quotaPreferences.overlayEnabled.collectAsState(initial = false)
    val providers by quotaPreferences.providers.collectAsState(initial = emptyList())

    // 当前正在编辑的 provider index
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 凭证管理对话框
    var credentialDialogIndex by remember { mutableStateOf<Int?>(null) }
    var maskedValue by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quota_page_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 总开关
            item("quotaMasterSwitch") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.quota_master_switch)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.CoinsDollar, null) },
                        headlineContent = { Text(stringResource(R.string.quota_enable)) },
                        supportingContent = { Text(stringResource(R.string.quota_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = quotaEnabled,
                                onCheckedChange = { scope.launch { quotaPreferences.setQuotaEnabled(it) } },
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Eye, null) },
                        headlineContent = { Text(stringResource(R.string.quota_overlay_enable)) },
                        supportingContent = { Text(stringResource(R.string.quota_overlay_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = overlayEnabled,
                                onCheckedChange = { scope.launch { quotaPreferences.setOverlayEnabled(it) } },
                            )
                        },
                    )
                }
            }

            // 提供商列表（展开编辑）
            item("quotaProviders") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.quota_providers_title)) },
                ) {
                    providers.forEachIndexed { index, provider ->
                        // 主条目：点击展开编辑
                        item(
                            onClick = {
                                me.rerere.rikkahub.data.log.AppLog.d("Quota", "provider 行点击 index=$index label=${provider.label}")
                                editingIndex = if (editingIndex == index) null else index
                            },
                            leadingContent = {
                                Text(
                                    text = (index + 1).toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            headlineContent = {
                                Text(
                                    provider.label.ifBlank { stringResource(R.string.quota_unnamed) },
                                )
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        provider.consoleUrl.ifBlank { stringResource(R.string.quota_no_url) },
                                    )
                                    if (provider.credential != null) {
                                        Text(
                                            text = stringResource(R.string.quota_saved_credential_display, provider.authType.displayName),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = provider.enabled,
                                        onCheckedChange = { checked ->
                                            scope.launch {
                                                quotaPreferences.setProviders(
                                                    providers.toMutableList().also {
                                                        it[index] = provider.copy(enabled = checked)
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )

                        // 展开：编辑表单（必须经 item() 包裹，否则渲染位置错乱）
                        if (editingIndex == index) {
                            item {
                                ProviderEditSection(
                                    provider = provider,
                                    onUpdate = { updated ->
                                        scope.launch {
                                            quotaPreferences.setProviders(
                                                providers.toMutableList().also { it[index] = updated },
                                            )
                                        }
                                    },
                                    onOpenConsole = {
                                        navController.navigate(Screen.QuotaConsole(provider.id))
                                    },
                                    onDelete = {
                                        scope.launch {
                                            quotaPreferences.setProviders(
                                                providers.toMutableList().also { it.removeAt(index) },
                                            )
                                            editingIndex = null
                                        }
                                    },
                                    onManageCredential = {
                                        credentialDialogIndex = index
                                        scope.launch {
                                            maskedValue = credentialManager.getMaskedValue(provider.id)
                                        }
                                    },
                                    onClearCredential = {
                                        scope.launch {
                                            credentialManager.clearCredential(provider.id)
                                            maskedValue = ""
                                        }
                                    },
                                )
                            }
                        }
                    }

                    // 添加按钮
                    item(
                        onClick = {
                            me.rerere.rikkahub.data.log.AppLog.d("Quota", "添加按钮点击，当前 providers=${providers.size}")
                            scope.launch {
                                quotaPreferences.setProviders(providers + QuotaProviderConfig())
                                val newIndex = providers.size
                                editingIndex = newIndex
                                listState.animateScrollToItem(1)
                                android.widget.Toast.makeText(context, context.getString(R.string.quota_added_feedback), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        leadingContent = { Icon(HugeIcons.AddCircle, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = {
                            Text(
                                stringResource(R.string.quota_add_provider),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
            }

            // 预设模板
            item("quotaPresets") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.quota_presets_title)) },
                ) {
                    QuotaPlatform.entries.forEach { platform ->
                        item(
                            onClick = {
                                me.rerere.rikkahub.data.log.AppLog.d("Quota", "预设模板点击: ${platform.label}")
                                scope.launch {
                                    val existing = providers.find { it.label == platform.label }
                                    if (existing != null) {
                                        quotaPreferences.setProviders(
                                            providers.map {
                                                if (it.id == existing.id) {
                                                    it.copy(
                                                        label = platform.label,
                                                        consoleUrl = platform.consoleUrl,
                                                        jsSelector = platform.jsSelector,
                                                        regexPattern = platform.regexPattern,
                                                    )
                                                } else {
                                                    it
                                                }
                                            },
                                        )
                                    } else {
                                        quotaPreferences.setProviders(
                                            providers +
                                                QuotaProviderConfig(
                                                    label = platform.label,
                                                    consoleUrl = platform.consoleUrl,
                                                    jsSelector = platform.jsSelector,
                                                    regexPattern = platform.regexPattern,
                                                ),
                                        )
                                        val newIndex = providers.size
                                        editingIndex = newIndex
                                        listState.animateScrollToItem(1)
                                        android.widget.Toast.makeText(context, context.getString(R.string.quota_added_feedback_with_name, platform.label), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            leadingContent = { Icon(HugeIcons.CoinsDollar, null) },
                            headlineContent = { Text(platform.label) },
                            supportingContent = { Text(platform.consoleUrl) },
                        )
                    }
                }
            }
        }
    }

    // 凭证管理对话框
    credentialDialogIndex?.let { idx ->
        val provider = providers.getOrNull(idx) ?: return@let
        AlertDialog(
            onDismissRequest = { credentialDialogIndex = null },
            title = { Text(stringResource(R.string.quota_credential_manage_title, provider.label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.quota_credential_auth_method, provider.authType.displayName))
                    if (provider.credential != null) {
                        Text(stringResource(R.string.quota_credential_saved))
                        Text(stringResource(R.string.quota_credential_masked, maskedValue))
                        Text(stringResource(R.string.quota_credential_saved_time, provider.credential!!.capturedAtMillis.toString()))
                    } else {
                        Text(stringResource(R.string.quota_credential_not_saved))
                        Text(stringResource(R.string.quota_credential_capture_hint))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    credentialDialogIndex = null
                    navController.navigate(Screen.QuotaConsole(provider.id))
                }) {
                    Text(stringResource(R.string.quota_open_console_login))
                }
            },
            dismissButton = {
                Row {
                    if (provider.credential != null) {
                        TextButton(onClick = {
                            scope.launch {
                                credentialManager.clearCredential(provider.id)
                                maskedValue = ""
                            }
                            credentialDialogIndex = null
                        }) {
                            Text(stringResource(R.string.quota_clear_credential), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { credentialDialogIndex = null }) {
                        Text(stringResource(R.string.quota_close))
                    }
                }
            },
        )
    }
}

/**
 * 提供商编辑区域：标签、URL、鉴权方式、手动凭证。
 */
@Composable
private fun ProviderEditSection(
    provider: QuotaProviderConfig,
    onUpdate: (QuotaProviderConfig) -> Unit,
    onOpenConsole: () -> Unit,
    onDelete: () -> Unit,
    onManageCredential: () -> Unit,
    onClearCredential: () -> Unit,
) {
    var showAuthTypeMenu by remember { mutableStateOf(false) }
    var label by remember(provider.id) { mutableStateOf(provider.label) }
    var consoleUrl by remember(provider.id) { mutableStateOf(provider.consoleUrl) }
    var totalQuota by remember(provider.id) { mutableStateOf(provider.totalQuota.toString()) }
    var authValue by remember(provider.id) { mutableStateOf(provider.manualAuthValue) }
    var authKeyName by remember(provider.id) { mutableStateOf(provider.manualAuthKeyName) }
    var authUsername by remember(provider.id) { mutableStateOf(provider.manualAuthUsername) }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {
                label = it
                onUpdate(provider.copy(label = it))
            },
            label = { Text(stringResource(R.string.quota_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = consoleUrl,
            onValueChange = {
                consoleUrl = it
                onUpdate(provider.copy(consoleUrl = it))
            },
            label = { Text(stringResource(R.string.quota_console_url)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = totalQuota,
            onValueChange = {
                totalQuota = it
                onUpdate(provider.copy(totalQuota = it.toDoubleOrNull() ?: 0.0))
            },
            label = { Text(stringResource(R.string.quota_total_limit)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // 鉴权方式选择
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.quota_auth_method_label), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { showAuthTypeMenu = true }) {
                Text(provider.authType.displayName)
            }
            DropdownMenu(
                expanded = showAuthTypeMenu,
                onDismissRequest = { showAuthTypeMenu = false },
            ) {
                QuotaAuthType.entries.forEach { authType ->
                    DropdownMenuItem(
                        text = { Text(authType.displayName) },
                        onClick = {
                            onUpdate(provider.copy(authType = authType))
                            showAuthTypeMenu = false
                        },
                    )
                }
            }
            if (provider.credential != null) {
                IconButton(onClick = onManageCredential) {
                    Icon(HugeIcons.Lock, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 手动凭证字段（非 COOKIE 模式显示）
        if (provider.authType != QuotaAuthType.NONE && provider.authType != QuotaAuthType.COOKIE) {
            when (provider.authType) {
                QuotaAuthType.BASIC -> {
                    OutlinedTextField(
                        value = authUsername,
                        onValueChange = {
                            authUsername = it
                            onUpdate(provider.copy(manualAuthUsername = it))
                        },
                        label = { Text(stringResource(R.string.quota_username)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                else -> { /* no extra fields */ }
            }
            OutlinedTextField(
                value = authKeyName,
                onValueChange = {
                    authKeyName = it
                    onUpdate(provider.copy(manualAuthKeyName = it))
                },
                label = {
                    Text(
                        when (provider.authType) {
                            QuotaAuthType.BEARER -> stringResource(R.string.quota_header_default)
                            QuotaAuthType.BASIC -> stringResource(R.string.quota_header_default)
                            QuotaAuthType.CUSTOM_HEADER -> stringResource(R.string.quota_header_custom)
                            QuotaAuthType.QUERY_PARAM -> stringResource(R.string.quota_param_name)
                            else -> stringResource(R.string.quota_key_name)
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = authValue,
                onValueChange = {
                    authValue = it
                    onUpdate(provider.copy(manualAuthValue = it))
                },
                label = { Text(stringResource(R.string.quota_credential_value)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        // 操作按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onOpenConsole) {
                Text(stringResource(R.string.quota_open_console))
            }
            TextButton(onClick = onManageCredential) {
                Text(stringResource(R.string.quota_credential_manage))
            }
            TextButton(onClick = onClearCredential) {
                Text(stringResource(R.string.quota_clear_credential), color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onDelete) {
                Icon(HugeIcons.Delete02, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
