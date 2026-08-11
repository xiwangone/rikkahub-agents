package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
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
            contentPadding = PaddingValues(8.dp),
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
                CardGroup(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_web_bridge_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_desc)) },
                        supportingContent = { Text(stringResource(R.string.setting_web_bridge_desc_detail)) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_ecs_host)) },
                        trailingContent = {
                            TextField(
                                value = webBridgeEcsHost,
                                onValueChange = {
                                    webBridgeEcsHost = it
                                    scope.launch { settingsStore.update { s -> s.copy(webBridgeEcsHost = it.trim()) } }
                                },
                                singleLine = true,
                                modifier = Modifier.width(160.dp),
                                shape = CircleShape,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        errorIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                    ),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_ecs_user)) },
                        trailingContent = {
                            TextField(
                                value = webBridgeEcsUser,
                                onValueChange = {
                                    webBridgeEcsUser = it
                                    scope.launch { settingsStore.update { s -> s.copy(webBridgeEcsUser = it.trim()) } }
                                },
                                singleLine = true,
                                modifier = Modifier.width(120.dp),
                                shape = CircleShape,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        errorIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                    ),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_ssh_port)) },
                        trailingContent = {
                            TextField(
                                value = webBridgeSshPort,
                                onValueChange = {
                                    webBridgeSshPort = it
                                    it.toIntOrNull()?.let { port ->
                                        scope.launch { settingsStore.update { s -> s.copy(webBridgeEcsPort = port) } }
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(100.dp),
                                shape = CircleShape,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        errorIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                    ),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_remote_port)) },
                        supportingContent = { Text(stringResource(R.string.setting_web_bridge_remote_port_desc)) },
                        trailingContent = {
                            TextField(
                                value = webBridgeRemotePort,
                                onValueChange = {
                                    webBridgeRemotePort = it
                                    it.toIntOrNull()?.let { port ->
                                        scope.launch { settingsStore.update { s -> s.copy(webBridgeRemotePort = port) } }
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(100.dp),
                                shape = CircleShape,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        errorIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                    ),
                            )
                        },
                    )

                    item(
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_local_port)) },
                        supportingContent = { Text(stringResource(R.string.setting_web_bridge_local_port_desc)) },
                        trailingContent = {
                            TextField(
                                value = webBridgeLocalPort,
                                onValueChange = {
                                    webBridgeLocalPort = it
                                    it.toIntOrNull()?.let { port ->
                                        scope.launch { settingsStore.update { s -> s.copy(webBridgeLocalPort = port) } }
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(100.dp),
                                shape = CircleShape,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        errorIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                    ),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_private_key)) },
                        supportingContent = { Text(stringResource(R.string.setting_web_bridge_private_key_desc)) },
                        trailingContent = {
                            TextField(
                                value = webBridgePrivateKeyPath,
                                onValueChange = {
                                    webBridgePrivateKeyPath = it
                                    scope.launch { settingsStore.update { s -> s.copy(webBridgePrivateKeyPath = it.trim()) } }
                                },
                                singleLine = true,
                                modifier = Modifier.width(200.dp),
                                shape = CircleShape,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        errorIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                    ),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_password)) },
                        supportingContent = { Text(stringResource(R.string.setting_web_bridge_password_desc)) },
                        trailingContent = {
                            TextField(
                                value = webBridgePassword,
                                onValueChange = {
                                    webBridgePassword = it
                                    scope.launch { settingsStore.update { s -> s.copy(webBridgePassword = it.trim()) } }
                                },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                modifier = Modifier.width(160.dp),
                                shape = CircleShape,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        errorIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                    ),
                            )
                        },
                    )
                                        item(
                        headlineContent = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.setting_web_bridge_gen_key))
                                Text(
                                    text = stringResource(R.string.setting_web_bridge_gen_key_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val scope = rememberCoroutineScope()
                                val context = LocalContext.current
                                val vaultRepo: CredentialVaultRepository = koinInject()
                                var keyInfo by remember { mutableStateOf<String?>(null) }
                                androidx.compose.material3.OutlinedButton(
                                    onClick = {
                                        val key = SshKeyGenerator.generate()
                                        scope.launch {
                                            runCatching {
                                                val dir = java.io.File(context.filesDir, "ssh_keys").apply { mkdirs() }
                                                val file = java.io.File(dir, "web_bridge_rsa")
                                                if (file.exists()) file.delete()
                                                file.writeText(key.privateKeyPem)
                                                file.setReadable(true, true)
                                                file.setWritable(true, true)
                                                file.setExecutable(false)
                                                webBridgePrivateKeyPath = file.absolutePath
                                                settingsStore.update { s -> s.copy(webBridgePrivateKeyPath = file.absolutePath) }
                                                vaultRepo.save(
                                                    name = "WEB_BRIDGE_SSH_KEY",
                                                    value = key.privateKeyPem,
                                                    description = "Web 桥 SSH 私钥（全局，${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())} 生成；私钥路径：${file.absolutePath}）",
                                                    group = "SSH",
                                                )
                                                keyInfo = "✅ 已生成并保存到密钥库（分组：SSH）\n已写私钥路径：${file.absolutePath}\n公钥请添加到 ECS ~/.ssh/authorized_keys：\n${key.publicKeyLine}"
                                            }.onFailure { e ->
                                                keyInfo = "❌ 生成失败: ${e.message}"
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
                                        androidx.compose.material3.OutlinedButton(
                                            onClick = {
                                                val pub = info.substringAfter("ssh-rsa").substringBefore("\n").let { "ssh-rsa$it" }
                                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("web-bridge-public-key", pub))
                                                keyInfo = "✅ 公钥已复制！请粘贴发给我/添加到 ECS ~/.ssh/authorized_keys\n$pub"
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("复制公钥")
                                        }
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
