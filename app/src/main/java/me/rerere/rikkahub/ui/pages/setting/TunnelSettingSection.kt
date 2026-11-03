package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Global
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.tunnel.TsnetBridge
import me.rerere.rikkahub.data.tunnel.TunnelState
import me.rerere.rikkahub.data.tunnel.resolveTunnelAuthKey
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import org.koin.compose.koinInject

/**
 * 偏好设置 → 网络 → 隧道（Material 3 Expressive 卡片组）。
 *
 * 能力边界：隧道只在 App 进程内做 per-app 代理（覆盖 SSH 与 OkHttp HTTP/HTTPS），不注册 VpnService，
 * 因此系统路由与 DNS 不受影响，其他 App 也不经过它。
 */
@Composable
fun TunnelSettingSection(vm: SettingVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val network = settings.networkSetting
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val vaultRepository: CredentialVaultRepository = koinInject()
    val credentialResolver = remember(vaultRepository) { CredentialResolver(vaultRepository) }

    var controlUrl by remember(network.tailscaleControlUrl) {
        mutableStateOf(network.tailscaleControlUrl)
    }
    var authKey by remember(network.tailscaleAuthKey) {
        mutableStateOf(network.tailscaleAuthKey)
    }
    var hostname by remember(network.tailscaleHostname) {
        mutableStateOf(network.tailscaleHostname)
    }
    var busy by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf(false) }

    // 状态只有一个来源：[TsnetBridge.state]（由启动/停止与守护循环写回）。
    // 开关只反映"此刻是否开着"，因此进程重启后自然显示为关，不会出现"开关开着却没连"。
    val tunnelState by TsnetBridge.state.collectAsStateWithLifecycle()
    val status = when (val s = tunnelState) {
        is TunnelState.Connected -> stringResource(R.string.tunnel_status_connected, s.ip)
        is TunnelState.Connecting -> stringResource(R.string.tunnel_status_connecting)
        is TunnelState.Offline -> stringResource(R.string.tunnel_status_offline)
        is TunnelState.Failed -> stringResource(R.string.tunnel_status_failed, s.reason)
        is TunnelState.Off -> stringResource(R.string.tunnel_status_disconnected)
    }
    val switchOn = tunnelState is TunnelState.Connected ||
        tunnelState is TunnelState.Connecting ||
        tunnelState is TunnelState.Offline

    // 输入框当前值与已保存值不一致时，才需要显示 保存 / 取消。
    val dirty = controlUrl.trim() != network.tailscaleControlUrl ||
        authKey.trim() != network.tailscaleAuthKey ||
        hostname.trim().ifBlank { "rikkahub" } != network.tailscaleHostname

    /** 解析密钥后启动隧道；建连结果由 [TsnetBridge.state] 广播，这里只负责把失败原因也写回。 */
    suspend fun startTunnel() {
        val resolved = resolveTunnelAuthKey(credentialResolver, authKey)
            .getOrElse {
                TsnetBridge.publish(TunnelState.Failed(it.message ?: "auth key resolve failed"))
                return
            }
        TsnetBridge.start(
            context = context,
            controlURL = controlUrl.trim(),
            authKey = resolved,
            hostname = hostname.trim().ifBlank { "rikkahub" },
        )
    }

    fun persistConfig() {
        vm.updateSettings { current ->
            current.copy(
                networkSetting = current.networkSetting.copy(
                    tailscaleControlUrl = controlUrl.trim(),
                    tailscaleAuthKey = authKey.trim(),
                    tailscaleHostname = hostname.trim().ifBlank { "rikkahub" },
                ),
            )
        }
    }

    fun save() {
        persistConfig()
        savedHint = true
        // 隧道已在运行时用新配置重连，避免"保存了但没生效"。
        if (TsnetBridge.isRunning || TsnetBridge.desiredRunning) {
            busy = true
            scope.launch {
                TsnetBridge.stop()
                startTunnel()
                busy = false
            }
        }
    }

    fun cancel() {
        controlUrl = network.tailscaleControlUrl
        authKey = network.tailscaleAuthKey
        hostname = network.tailscaleHostname
        savedHint = false
    }

    val badgeContainerColor = when (tunnelState) {
        is TunnelState.Connected -> MaterialTheme.colorScheme.primaryContainer
        is TunnelState.Connecting, is TunnelState.Offline -> MaterialTheme.colorScheme.tertiaryContainer
        is TunnelState.Failed -> MaterialTheme.colorScheme.errorContainer
        is TunnelState.Off -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val badgeContentColor = when (tunnelState) {
        is TunnelState.Connected -> MaterialTheme.colorScheme.onPrimaryContainer
        is TunnelState.Connecting, is TunnelState.Offline -> MaterialTheme.colorScheme.onTertiaryContainer
        is TunnelState.Failed -> MaterialTheme.colorScheme.onErrorContainer
        is TunnelState.Off -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text(stringResource(R.string.tunnel_enable_title)) },
    ) {
        item(
            leadingContent = {
                CardGroupIcon(
                    icon = HugeIcons.Global,
                    containerColor = if (switchOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (switchOn) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tunnel_enable_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = badgeContainerColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = tunnelState is TunnelState.Connected) {
                                (tunnelState as? TunnelState.Connected)?.ip?.let { ip ->
                                    clipboard.setText(AnnotatedString(ip))
                                }
                            },
                    ) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeContentColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            },
            supportingContent = {
                Text(
                    text = stringResource(R.string.tunnel_enable_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            trailingContent = {
                Switch(
                    checked = switchOn,
                    enabled = !busy,
                    onCheckedChange = { enabled ->
                        busy = true
                        persistConfig()
                        scope.launch {
                            if (enabled) {
                                startTunnel()
                            } else {
                                TsnetBridge.stop()
                            }
                            busy = false
                        }
                    },
                )
            },
        )

        item(
            headlineContent = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = controlUrl,
                        onValueChange = {
                            controlUrl = it
                            savedHint = false
                        },
                        label = { Text(text = stringResource(R.string.tunnel_control_url_label)) },
                        placeholder = { Text(text = "https://login.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecretRefField(
                        value = authKey,
                        onValueChange = {
                            authKey = it
                            savedHint = false
                        },
                        label = stringResource(R.string.tunnel_auth_key_label),
                        placeholder = stringResource(R.string.tunnel_auth_key_placeholder),
                        nameHint = "TAILSCALE_AUTH_KEY",
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = hostname,
                        onValueChange = {
                            hostname = it
                            savedHint = false
                        },
                        label = { Text(text = stringResource(R.string.tunnel_hostname_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (dirty) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { cancel() }) {
                                Text(text = stringResource(R.string.settings_cancel))
                            }
                            Button(onClick = { save() }, enabled = !busy) {
                                Text(text = stringResource(R.string.chat_page_save))
                            }
                        }
                    } else if (savedHint) {
                        Text(
                            text = stringResource(R.string.tunnel_saved_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
        )
    }
}
