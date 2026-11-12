package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.BackendWebBridge
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.vault.CredentialType
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.SshKeyGenerator
import me.rerere.rikkahub.data.vault.VAULT_REF_PREFIX
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

private const val WEB_BRIDGE_KEY_NAME = "WEB_BRIDGE_SSH_KEY"

/** 端口号是否合法：能解析为整数且在 1–65535 内（与 SSH 主机页同口径）。 */
internal fun webBridgePortValid(text: String): Boolean {
    val port = text.toIntOrNull() ?: return false
    return port in 1..65535
}

/**
 * 只把全局 Web 桥字段同步进各后端供应商自己的副本，不动各自的开关。
 * 字段编辑后也要同步：以前只在拨动开关时同步，改完主机/端口直接重启 App，
 * 后端用的可能是旧地址。注意 Backend 没有 webBridgeCredentialRef 字段，
 * 凭证引用只存在全局设置里，连接按钮直接读全局值，不走后端副本。
 */
internal fun syncWebBridgeFields(settings: Settings): Settings {
    val newProviders =
        settings.providers.map { p ->
            if (p is ProviderSetting.Backend) {
                p.copy(
                    webBridgeEcsHost = settings.webBridgeEcsHost,
                    webBridgeEcsUser = settings.webBridgeEcsUser,
                    webBridgeEcsPort = settings.webBridgeEcsPort,
                    webBridgeRemotePort = settings.webBridgeRemotePort,
                    webBridgeLocalPort = settings.webBridgeLocalPort,
                    webBridgePrivateKeyPath = settings.webBridgePrivateKeyPath,
                    webBridgePassword = settings.webBridgePassword,
                )
            } else {
                p
            }
        }
    return settings.copy(providers = newProviders)
}

internal fun syncToBackendProviders(
    settings: Settings,
    enabled: Boolean,
): Settings {
    val withFields = syncWebBridgeFields(settings)
    return withFields.copy(
        providers =
            withFields.providers.map { p ->
                if (p is ProviderSetting.Backend) p.copy(webBridgeEnabled = enabled) else p
            },
    )
}

/**
 * Web 桥三级页（反向隧道到 ECS，Material 3 Expressive 分组卡片样式）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingWebBridgePage() {
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    var webBridgeEcsHost by remember(settings.webBridgeEcsHost) {
        mutableStateOf(settings.webBridgeEcsHost)
    }
    var webBridgeEcsUser by remember(settings.webBridgeEcsUser) {
        mutableStateOf(settings.webBridgeEcsUser)
    }
    var webBridgeSshPort by remember(settings.webBridgeEcsPort) {
        mutableStateOf(settings.webBridgeEcsPort.toString())
    }
    var webBridgeRemotePort by remember(settings.webBridgeRemotePort) {
        mutableStateOf(settings.webBridgeRemotePort.toString())
    }
    var webBridgeLocalPort by remember(settings.webBridgeLocalPort) {
        mutableStateOf(settings.webBridgeLocalPort.toString())
    }
    var webBridgePrivateKeyPath by remember(settings.webBridgePrivateKeyPath) {
        mutableStateOf(settings.webBridgePrivateKeyPath)
    }
    var webBridgePassword by remember(settings.webBridgePassword) {
        mutableStateOf(settings.webBridgePassword)
    }
    var webBridgeCredentialRef by remember(settings.webBridgeCredentialRef) {
        mutableStateOf(settings.webBridgeCredentialRef)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_web_bridge_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.fillMaxWidth(),
                    title = { Text(stringResource(R.string.setting_web_bridge_switch)) },
                ) {
                    // 多后端时开关以前是“取或”：一个开就显示开，关一次全关了。
                    // 部分开启时在副标题里写明 N/M，不再让人以为全开了。
                    val backends = settings.providers.filterIsInstance<ProviderSetting.Backend>()
                    val enabledBackends = backends.count { it.webBridgeEnabled }
                    val bridgePartial = enabledBackends in 1 until backends.size
                    switchItem(
                        checked = enabledBackends > 0,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                settingsStore.update { s -> syncToBackendProviders(s, enabled) }
                            }
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Connect) },
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_switch_desc)) },
                        supportingContent = {
                            Text(
                                if (bridgePartial) {
                                    stringResource(
                                        R.string.setting_web_bridge_partial_enabled,
                                        enabledBackends,
                                        backends.size,
                                    )
                                } else {
                                    stringResource(R.string.setting_web_bridge_switch_desc_detail)
                                },
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.fillMaxWidth(),
                    title = { Text(stringResource(R.string.setting_web_bridge_ecs_host)) },
                ) {
                    formItem(
                        leadingContent = { CardGroupIcon(HugeIcons.ServerStack01) },
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_web_bridge_desc_detail)) },
                    ) {
                        OutlinedTextField(
                            value = webBridgeEcsHost,
                            onValueChange = {
                                webBridgeEcsHost = it
                                scope.launch { settingsStore.update { s -> syncWebBridgeFields(s.copy(webBridgeEcsHost = it.trim())) } }
                            },
                            label = { Text(stringResource(R.string.setting_web_bridge_ecs_host)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = webBridgeEcsUser,
                                onValueChange = {
                                    webBridgeEcsUser = it
                                    scope.launch { settingsStore.update { s -> syncWebBridgeFields(s.copy(webBridgeEcsUser = it.trim())) } }
                                },
                                label = { Text(stringResource(R.string.setting_web_bridge_ecs_user)) },
                                modifier = Modifier.weight(0.65f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = webBridgeSshPort,
                                onValueChange = {
                                    val filtered = it.filter { ch -> ch.isDigit() }
                                    webBridgeSshPort = filtered
                                    // 超出 1–65535 的不再静默存入（旧逻辑只拦解析失败，99999/0 会直接写库）。
                                    filtered.toIntOrNull()?.takeIf { port -> port in 1..65535 }?.let { port ->
                                        scope.launch { settingsStore.update { s -> syncWebBridgeFields(s.copy(webBridgeEcsPort = port)) } }
                                    }
                                },
                                label = { Text(stringResource(R.string.setting_web_bridge_ssh_port)) },
                                modifier = Modifier.weight(0.35f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = !webBridgePortValid(webBridgeSshPort),
                                supportingText = {
                                    if (!webBridgePortValid(webBridgeSshPort)) {
                                        Text(stringResource(R.string.setting_ssh_host_port_invalid))
                                    }
                                },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = webBridgeRemotePort,
                                onValueChange = {
                                    val filtered = it.filter { ch -> ch.isDigit() }
                                    webBridgeRemotePort = filtered
                                    filtered.toIntOrNull()?.takeIf { port -> port in 1..65535 }?.let { port ->
                                        scope.launch { settingsStore.update { s -> syncWebBridgeFields(s.copy(webBridgeRemotePort = port)) } }
                                    }
                                },
                                label = { Text(stringResource(R.string.setting_web_bridge_remote_port)) },
                                supportingText = {
                                    if (!webBridgePortValid(webBridgeRemotePort)) {
                                        Text(stringResource(R.string.setting_ssh_host_port_invalid))
                                    } else {
                                        Text(stringResource(R.string.setting_web_bridge_remote_port_desc))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = !webBridgePortValid(webBridgeRemotePort),
                            )
                            OutlinedTextField(
                                value = webBridgeLocalPort,
                                onValueChange = {
                                    val filtered = it.filter { ch -> ch.isDigit() }
                                    webBridgeLocalPort = filtered
                                    filtered.toIntOrNull()?.takeIf { port -> port in 1..65535 }?.let { port ->
                                        scope.launch { settingsStore.update { s -> syncWebBridgeFields(s.copy(webBridgeLocalPort = port)) } }
                                    }
                                },
                                label = { Text(stringResource(R.string.setting_web_bridge_local_port)) },
                                supportingText = {
                                    if (!webBridgePortValid(webBridgeLocalPort)) {
                                        Text(stringResource(R.string.setting_ssh_host_port_invalid))
                                    } else {
                                        Text(stringResource(R.string.setting_web_bridge_local_port_desc))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = !webBridgePortValid(webBridgeLocalPort),
                            )
                        }
                    }

                    formItem(
                        leadingContent = { CardGroupIcon(HugeIcons.LockKey) },
                        headlineContent = { Text(stringResource(R.string.web_bridge_credential_ref_label)) },
                        supportingContent = { Text(stringResource(R.string.web_bridge_credential_ref_desc)) },
                    ) {
                        SecretRefField(
                            value = webBridgePrivateKeyPath,
                            onValueChange = {
                                webBridgePrivateKeyPath = it
                                scope.launch { settingsStore.update { s -> syncWebBridgeFields(s.copy(webBridgePrivateKeyPath = it.trim())) } }
                            },
                            label = stringResource(R.string.setting_web_bridge_private_key),
                            description = stringResource(R.string.setting_web_bridge_private_key_desc),
                            nameHint = "WEB_BRIDGE_KEY",
                            typeHint = CredentialType.SSH_KEY,
                            singleLine = true,
                        )
                        SecretRefField(
                            value = webBridgePassword,
                            onValueChange = {
                                webBridgePassword = it
                                scope.launch { settingsStore.update { s -> syncWebBridgeFields(s.copy(webBridgePassword = it.trim())) } }
                            },
                            label = stringResource(R.string.setting_web_bridge_password),
                            description = stringResource(R.string.setting_web_bridge_password_desc),
                            nameHint = "WEB_BRIDGE_PASSWORD",
                            typeHint = CredentialType.BASIC_AUTH,
                            singleLine = true,
                        )
                        SecretRefField(
                            value = webBridgeCredentialRef,
                            onValueChange = {
                                webBridgeCredentialRef = it
                                scope.launch { settingsStore.update { s -> syncWebBridgeFields(s.copy(webBridgeCredentialRef = it.trim())) } }
                            },
                            label = stringResource(R.string.web_bridge_credential_ref_label),
                            description = stringResource(R.string.web_bridge_credential_ref_desc),
                            nameHint = "WEB_BRIDGE",
                            typeHint = CredentialType.SSH_KEY,
                            singleLine = true,
                        )
                    }
                }
            }

            item {
                val context = LocalContext.current
                val webBridgeSshKeyVaultDesc = stringResource(R.string.web_bridge_ssh_key_vault_desc, java.time.LocalDate.now().toString())
                val vaultRepo: CredentialVaultRepository = koinInject()
                var keyInfo by remember { mutableStateOf<String?>(null) }
                var saveToVault by remember { mutableStateOf(true) }

                CardGroup(
                    modifier = Modifier.fillMaxWidth(),
                    title = { Text(stringResource(R.string.setting_web_bridge_gen_key)) },
                ) {
                    switchItem(
                        checked = saveToVault,
                        onCheckedChange = { saveToVault = it },
                        leadingContent = { CardGroupIcon(HugeIcons.Key01) },
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_save_to_vault)) },
                        supportingContent = { Text(stringResource(R.string.setting_web_bridge_gen_key_desc)) },
                    )
                    formItem {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        val generated =
                                            withContext(Dispatchers.IO) {
                                                val key = SshKeyGenerator.generate()
                                                val dir = java.io.File(context.filesDir, "ssh_keys").apply { mkdirs() }
                                                val file = java.io.File(dir, "web_bridge_rsa")
                                                if (file.exists()) file.delete()
                                                file.writeText(key.privateKeyPem)
                                                file.setReadable(true, true)
                                                file.setWritable(true, true)
                                                file.setExecutable(false)
                                                if (saveToVault) {
                                                    vaultRepo.save(
                                                        name = WEB_BRIDGE_KEY_NAME,
                                                        value = key.privateKeyPem,
                                                        description = webBridgeSshKeyVaultDesc,
                                                        group = "SSH",
                                                        publicKey = key.publicKeyLine,
                                                    )
                                                }
                                                Triple(key, file.absolutePath, saveToVault)
                                            }
                                        val (key, path, toVault) = generated
                                        val ref = if (toVault) "$VAULT_REF_PREFIX$WEB_BRIDGE_KEY_NAME" else path
                                        webBridgePrivateKeyPath = ref
                                        settingsStore.update { s -> syncWebBridgeFields(s.copy(webBridgePrivateKeyPath = ref)) }
                                        keyInfo =
                                            if (toVault) {
                                                context.getString(
                                                    R.string.setting_web_bridge_gen_success_vault,
                                                    ref,
                                                    key.publicKeyLine,
                                                )
                                            } else {
                                                context.getString(
                                                    R.string.setting_web_bridge_gen_success,
                                                    path,
                                                    key.publicKeyLine,
                                                )
                                            }
                                    }.onFailure { e ->
                                        keyInfo = context.getString(R.string.setting_web_bridge_gen_failed, e.message ?: "")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.setting_web_bridge_gen_key_btn))
                        }
                        keyInfo?.let { info ->
                            Text(
                                text = info,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (info.contains("ssh-rsa")) {
                                OutlinedButton(
                                    onClick = {
                                        val pub = info.substringAfter("ssh-rsa").substringBefore("\n").let { "ssh-rsa$it" }
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.resources.getString(R.string.web_bridge_clip_label), pub))
                                        keyInfo = context.getString(R.string.setting_web_bridge_copy_success, pub)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.web_bridge_copy_public_key))
                                }
                            }
                        }
                    }
                }
            }

            item {
                val webBridge: BackendWebBridge = koinInject()
                val bridgeState by webBridge.state.collectAsStateWithLifecycle()
                CardGroup(
                    modifier = Modifier.fillMaxWidth(),
                    title = { Text(stringResource(R.string.web_bridge_connection)) },
                ) {
                    formItem(
                        leadingContent = { CardGroupIcon(HugeIcons.Connect) },
                        headlineContent = { Text(stringResource(R.string.web_bridge_connection)) },
                        trailingContent = {
                            Surface(
                                shape = CircleShape,
                                color =
                                    when {
                                        bridgeState.tunnelConnected -> MaterialTheme.colorScheme.primaryContainer
                                        bridgeState.webServerRunning -> MaterialTheme.colorScheme.tertiaryContainer
                                        else -> MaterialTheme.colorScheme.secondaryContainer
                                    },
                                contentColor =
                                    when {
                                        bridgeState.tunnelConnected -> MaterialTheme.colorScheme.onPrimaryContainer
                                        bridgeState.webServerRunning -> MaterialTheme.colorScheme.onTertiaryContainer
                                        else -> MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                            ) {
                                Text(
                                    text =
                                        when {
                                            bridgeState.tunnelConnected ->
                                                stringResource(
                                                    R.string.web_bridge_state_connected,
                                                    settings.webBridgeRemotePort,
                                                    settings.webBridgeLocalPort,
                                                )
                                            bridgeState.webServerRunning ->
                                                stringResource(R.string.web_bridge_state_connecting)
                                            else -> stringResource(R.string.web_bridge_state_disconnected)
                                        },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        },
                    ) {
                        if (bridgeState.message.isNotBlank()) {
                            Text(
                                text = bridgeState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        // 连接前再校验一次三个端口：任一非法就禁点并说明原因，不再拿旧端口去连。
                        val portsValid =
                            webBridgePortValid(webBridgeSshPort) &&
                                webBridgePortValid(webBridgeRemotePort) &&
                                webBridgePortValid(webBridgeLocalPort)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        webBridge.start(
                                            ecsHost = settings.webBridgeEcsHost,
                                            ecsPort = settings.webBridgeEcsPort,
                                            ecsUser = settings.webBridgeEcsUser,
                                            remoteTunnelPort = settings.webBridgeRemotePort,
                                            localWebPort = settings.webBridgeLocalPort,
                                            privateKeyPath = settings.webBridgePrivateKeyPath,
                                            password = settings.webBridgePassword,
                                            credentialRef = settings.webBridgeCredentialRef,
                                        )
                                    }
                                },
                                enabled = !bridgeState.tunnelConnected && portsValid,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.web_bridge_connect))
                            }
                            OutlinedButton(
                                onClick = { webBridge.stop() },
                                enabled = bridgeState.webServerRunning || bridgeState.tunnelConnected,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.web_bridge_disconnect))
                            }
                        }
                        if (!portsValid) {
                            Text(
                                text = stringResource(R.string.setting_web_bridge_invalid_port_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
