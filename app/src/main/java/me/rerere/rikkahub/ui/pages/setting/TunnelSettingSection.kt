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
    var status by remember {
        mutableStateOf(
            if (TsnetBridge.isRunning) "已连接 ${TsnetBridge.nodeIp.orEmpty()}" else "未连接",
        )
    }

    // 输入框当前值与已保存值不一致时，才需要显示 保存 / 取消。
    val dirty = controlUrl.trim() != network.tailscaleControlUrl ||
        authKey.trim() != network.tailscaleAuthKey ||
        hostname.trim().ifBlank { "rikkahub" } != network.tailscaleHostname

    fun persist(enabled: Boolean) {
        vm.updateSettings { current ->
            current.copy(
                networkSetting = current.networkSetting.copy(
                    tunnelEnabled = enabled,
                    tailscaleControlUrl = controlUrl.trim(),
                    tailscaleAuthKey = authKey.trim(),
                    tailscaleHostname = hostname.trim().ifBlank { "rikkahub" },
                ),
            )
        }
    }

    fun save() {
        persist(network.tunnelEnabled)
        savedHint = true
        // 隧道已在运行时用新配置重连，避免"保存了但没生效"。
        if (network.tunnelEnabled && TsnetBridge.isRunning) {
            busy = true
            scope.launch {
                TsnetBridge.stop()
                status = TsnetBridge.start(
                    context = context,
                    controlURL = controlUrl.trim(),
                    authKey = authKey.trim(),
                    hostname = hostname.trim().ifBlank { "rikkahub" },
                ).fold(
                    onSuccess = { "已连接 $it" },
                    onFailure = { "失败: ${it.message}" },
                )
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
                checked = network.tunnelEnabled,
                enabled = !busy,
                onCheckedChange = { enabled ->
                    busy = true
                    persist(enabled)
                    scope.launch {
                        if (enabled) {
                            val result = TsnetBridge.start(
                                context = context,
                                controlURL = controlUrl.trim(),
                                authKey = authKey.trim(),
                                hostname = hostname.trim().ifBlank { "rikkahub" },
                            )
                            status = result.fold(
                                onSuccess = { "已连接 $it" },
                                onFailure = { "失败: ${it.message}" },
                            )
                        } else {
                            TsnetBridge.stop()
                            status = "未连接"
                        }
                        busy = false
                    }
                },
            )
        }

        Text(text = "状态：$status", style = MaterialTheme.typography.bodySmall)

        if (network.tunnelEnabled) {
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
            OutlinedTextField(
                value = authKey,
                onValueChange = { authKey = it
                    savedHint = false
                },
                label = { Text(text = "认证密钥") },
                placeholder = { Text(text = "tskey-auth-...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
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
            Text(
                text = "WireGuard 隧道：待实现",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
