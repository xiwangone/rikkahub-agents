package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.ReasonixWebBridge
import me.rerere.rikkahub.data.vault.SshKeyGenerator
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import org.koin.compose.koinInject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Web 桥三级页（反向隧道到 ECS）。
 *
 * 全局配置：ECS 主机/用户名/SSH 端口/远程隧道端口。
 * 供 Reasonix 等 provider 选择「使用全局 Web 桥配置」复用（后续接入其他后端）。
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

    /** 全局开关开启：把全局配置同步到所有 Reasonix provider 并启用（改一次即可）。 */
    fun syncToReasonixProviders(settings: Settings, enabled: Boolean): Settings {
        val newProviders =
            settings.providers.map { p ->
                if (p is ProviderSetting.Reasonix) {
                    p.copy(
                        webBridgeEnabled = enabled,
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 总开关：开启时同步全局配置到 Reasonix provider 并启用
            item {
                CardGroup(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_web_bridge_switch)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_switch_desc)) },
                        supportingContent = { Text(stringResource(R.string.setting_web_bridge_switch_desc_detail)) },
                        trailingContent = {
                            Switch(
                                checked = settings.providers.any { it is ProviderSetting.Reasonix && it.webBridgeEnabled },
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        settingsStore.update { s -> syncToReasonixProviders(s, enabled) }
                                    }
                                },
                            )
                        },
                    )
                }
            }

            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_web_bridge_desc_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = webBridgeEcsHost,
                        onValueChange = {
                            webBridgeEcsHost = it
                            scope.launch { settingsStore.update { s -> s.copy(webBridgeEcsHost = it.trim()) } }
                        },
                        label = { Text(stringResource(R.string.setting_web_bridge_ecs_host)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    OutlinedTextField(
                        value = webBridgeEcsUser,
                        onValueChange = {
                            webBridgeEcsUser = it
                            scope.launch { settingsStore.update { s -> s.copy(webBridgeEcsUser = it.trim()) } }
                        },
                        label = { Text(stringResource(R.string.setting_web_bridge_ecs_user)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    OutlinedTextField(
                        value = webBridgeSshPort,
                        onValueChange = {
                            webBridgeSshPort = it
                            it.toIntOrNull()?.let { port ->
                                scope.launch { settingsStore.update { s -> s.copy(webBridgeEcsPort = port) } }
                            }
                        },
                        label = { Text(stringResource(R.string.setting_web_bridge_ssh_port)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )

                    OutlinedTextField(
                        value = webBridgeRemotePort,
                        onValueChange = {
                            webBridgeRemotePort = it
                            it.toIntOrNull()?.let { port ->
                                scope.launch { settingsStore.update { s -> s.copy(webBridgeRemotePort = port) } }
                            }
                        },
                        label = { Text(stringResource(R.string.setting_web_bridge_remote_port)) },
                        supportingText = { Text(stringResource(R.string.setting_web_bridge_remote_port_desc)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )

                    OutlinedTextField(
                        value = webBridgeLocalPort,
                        onValueChange = {
                            webBridgeLocalPort = it
                            it.toIntOrNull()?.let { port ->
                                scope.launch { settingsStore.update { s -> s.copy(webBridgeLocalPort = port) } }
                            }
                        },
                        label = { Text(stringResource(R.string.setting_web_bridge_local_port)) },
                        supportingText = { Text(stringResource(R.string.setting_web_bridge_local_port_desc)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )

                    OutlinedTextField(
                        value = webBridgePrivateKeyPath,
                        onValueChange = {
                            webBridgePrivateKeyPath = it
                            scope.launch { settingsStore.update { s -> s.copy(webBridgePrivateKeyPath = it.trim()) } }
                        },
                        label = { Text(stringResource(R.string.setting_web_bridge_private_key)) },
                        supportingText = { Text(stringResource(R.string.setting_web_bridge_private_key_desc)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    OutlinedTextField(
                        value = webBridgePassword,
                        onValueChange = {
                            webBridgePassword = it
                            scope.launch { settingsStore.update { s -> s.copy(webBridgePassword = it.trim()) } }
                        },
                        label = { Text(stringResource(R.string.setting_web_bridge_password)) },
                        supportingText = { Text(stringResource(R.string.setting_web_bridge_password_desc)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )

                    // ── 生成 SSH 密钥 + 保存到密钥库开关 ──
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.setting_web_bridge_gen_key),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.setting_web_bridge_gen_key_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val context = LocalContext.current
                    val vaultRepo: CredentialVaultRepository = koinInject()
                    var keyInfo by remember { mutableStateOf<String?>(null) }
                    var saveToVault by remember { mutableStateOf(true) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        val generated = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
                                                    name = "WEB_BRIDGE_SSH_KEY",
                                                    value = key.privateKeyPem,
                                                    description = "Web 桥 SSH 私钥（全局，${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())} 生成；私钥路径：${file.absolutePath}）",
                                                    group = "SSH",
                                                )
                                            }
                                            Triple(key, file.absolutePath, saveToVault)
                                        }
                                        val (key, path, toVault) = generated
                                        webBridgePrivateKeyPath = path
                                        settingsStore.update { s -> s.copy(webBridgePrivateKeyPath = path) }
                                        keyInfo =
                                            if (toVault) {
                                                context.getString(
                                                    R.string.setting_web_bridge_gen_success_vault,
                                                    path,
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
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.setting_web_bridge_gen_key_btn))
                        }
                        Text(
                            stringResource(R.string.setting_web_bridge_save_to_vault),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Switch(
                            checked = saveToVault,
                            onCheckedChange = { saveToVault = it },
                        )
                    }
                    keyInfo?.let { info ->
                        Text(
                            text = info,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (info.contains("ssh-rsa")) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    val pub = info.substringAfter("ssh-rsa").substringBefore("\n").let { "ssh-rsa$it" }
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("web-bridge-public-key", pub))
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

            // 连接状态与操作（全局唯一控制点；provider 页只留引用开关）
            item {
                val webBridge: ReasonixWebBridge = koinInject()
                val bridgeState by webBridge.state.collectAsState()
                CardGroup(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.web_bridge_connection)) },
                ) {
                    item(
                        headlineContent = {
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
                                color =
                                    when {
                                        bridgeState.tunnelConnected -> Color(0xFF22C55E)
                                        bridgeState.webServerRunning -> Color(0xFFF59E0B)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        },
                    )
                    if (bridgeState.message.isNotBlank()) {
                        item {
                            Text(
                                text = bridgeState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    item {
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
                                        )
                                    }
                                },
                                enabled = !bridgeState.tunnelConnected,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.web_bridge_connect))
                            }
                            androidx.compose.material3.OutlinedButton(
                                onClick = { webBridge.stop() },
                                enabled = bridgeState.webServerRunning || bridgeState.tunnelConnected,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.web_bridge_disconnect))
                            }
                        }
                    }
                }
            }
        }
    }
}
