package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.tunnel.TsnetBridge
import me.rerere.rikkahub.data.tunnel.TunnelState
import me.rerere.rikkahub.data.tunnel.resolveTunnelAuthKey
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import org.koin.compose.koinInject

/**
 * 偏好设置 → 网络 → 隧道。
 *
 * 能力边界：隧道只在 App 进程内做 per-app 代理，不注册 VpnService，
 * 因此系统路由与 DNS 不受影响，其他 App 也不经过它。
 */
@Composable
fun TunnelSettingSection(vm: SettingVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val network = settings.networkSetting
    val context = LocalContext.current
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
        is TunnelState.Connected -> "已连接 ${s.ip}"
        is TunnelState.Failed -> "失败: ${s.reason}"
        else -> "未连接"
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
        if (TsnetBridge.isRunning) {
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

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "启用隧道", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "仅本 App 走隧道，不接管系统 VPN / DNS",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
        }

        Text(text = "状态：$status", style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(
            value = controlUrl,
            onValueChange = { controlUrl = it
                savedHint = false
            },
            label = { Text(text = "控制面 URL") },
            placeholder = { Text(text = "https://login.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SecretRefField(
            value = authKey,
            onValueChange = { authKey = it
                savedHint = false
            },
            label = "认证密钥",
            placeholder = "tskey-auth-... 或 \$\$ 凭证名",
            nameHint = "TAILSCALE_AUTH_KEY",
            singleLine = true,
        )
        OutlinedTextField(
            value = hostname,
            onValueChange = { hostname = it
                savedHint = false
            },
            label = { Text(text = "节点名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (dirty) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { cancel() }) { Text(text = "取消") }
                Button(onClick = { save() }, enabled = !busy) { Text(text = "保存") }
            }
        } else if (savedHint) {
            Text(
                text = "已保存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
