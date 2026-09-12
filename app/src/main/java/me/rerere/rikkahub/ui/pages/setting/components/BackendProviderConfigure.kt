package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Backend Provider 配置页。
 * - baseUrl：Backend serve 入口（nginx Basic Auth 或直连 token）
 * - username/password：nginx Basic Auth（与 backend-android 客户端一致）
 * - token：Backend serve token 模式（留空则走 Basic Auth）
 */
@Composable
fun BackendProviderConfigure(
    provider: ProviderSetting.Backend,
    onEdit: (ProviderSetting.Backend) -> Unit,
) {
    provider.description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    // 后端类型选择（backend / openclaw / custom / cli）
    Text(
        text = stringResource(R.string.backend_type),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            "backend" to "Backend",
            "custom" to stringResource(R.string.backend_type_custom),
            "cli" to "CLI",
        ).forEach { (type, label) ->
            FilterChip(
                selected = provider.backendType == type,
                onClick = { onEdit(provider.copy(backendType = type)) },
                label = { Text(label) },
            )
        }
    }

    // baseUrl：backend/custom 显示；cli 类型改用命令
    if (provider.backendType != "cli") {
        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.backend_base_url_example)) },
            isError = provider.baseUrl.isNotBlank() && provider.baseUrl.toHttpUrlOrNull() == null,
        )
    }

    // cli 类型：CLI 命令模板（{prompt} 为提示词占位符）
    if (provider.backendType == "cli") {
        OutlinedTextField(
            value = provider.cliCommand,
            onValueChange = { onEdit(provider.copy(cliCommand = it.trim())) },
            label = { Text(stringResource(R.string.backend_cli_command)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.backend_cli_command_hint)) },
        )
        OutlinedTextField(
            value = provider.cliSshHost,
            onValueChange = { onEdit(provider.copy(cliSshHost = it.trim())) },
            label = { Text(stringResource(R.string.backend_cli_ssh_host)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.backend_cli_ssh_host_hint)) },
        )
    }

    // backend 专用：连接方式 + Basic Auth（其他后端类型不显示）
    if (provider.backendType == "backend") {
        // 连接方式选择
        Text(
            text = stringResource(R.string.backend_connection_mode),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("serve" to stringResource(R.string.backend_mode_serve), "ssh" to stringResource(R.string.backend_mode_ssh)).forEach { (mode, label) ->
                androidx.compose.material3.FilterChip(
                    selected = provider.connectionMode == mode,
                    onClick = { onEdit(provider.copy(connectionMode = mode)) },
                    label = { Text(label) },
                )
            }
        }
        Text(
            text =
                if (provider.connectionMode == "serve") {
                    stringResource(R.string.backend_mode_serve_desc)
                } else {
                    stringResource(R.string.backend_mode_ssh_desc)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = provider.username,
            onValueChange = { onEdit(provider.copy(username = it.trim())) },
            label = { Text(stringResource(R.string.backend_username_basic)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        var passwordVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = provider.password,
            onValueChange = { onEdit(provider.copy(password = it)) },
            label = { Text(stringResource(R.string.backend_password_basic)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View,
                        contentDescription = null,
                    )
                }
            },
        )
    }

    var tokenVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = provider.token,
        onValueChange = { onEdit(provider.copy(token = it.trim())) },
        label = { Text(stringResource(R.string.backend_token_serve)) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
        visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { tokenVisible = !tokenVisible }) {
                Icon(
                    if (tokenVisible) HugeIcons.ViewOff else HugeIcons.View,
                    contentDescription = null,
                )
            }
        },
    )

    // ── Web 桥（反向隧道）──
    // backend 专用：Web 桥（其他后端类型不显示）
    if (provider.backendType == "backend") {
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.backend_web_bridge_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Switch(
                checked = provider.webBridgeEnabled,
                onCheckedChange = { onEdit(provider.copy(webBridgeEnabled = it)) },
            )
        }
        Text(
            text = stringResource(R.string.backend_web_bridge_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 分隔：Web 桥区块与下方「是否启用」（整个提供商开关）之间加间距，避免视觉拥挤
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) },
        )
    }

    // backend 专用：服务端会话说明
    if (provider.backendType == "backend") {
        Text(
            text = stringResource(R.string.backend_session_server_managed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // 接入诊断：读取服务端 /status，把「是否挂起 / 能否取消」等关键状态集中展示，
    // 避免交互（审批 / 提问 / 停止）失效时无从判断。
    if (provider.backendType == "backend" && provider.baseUrl.isNotBlank()) {
        val scope = rememberCoroutineScope()
        var diag by remember { mutableStateOf<String?>(null) }
        var diagLoading by remember { mutableStateOf(false) }
        Text(
            text = stringResource(R.string.backend_diag_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !diagLoading,
                onClick = {
                    diagLoading = true
                    scope.launch {
                        diag =
                            runCatching { backendStatusDigest(provider) }
                                .getOrElse { "检查失败：${it.message ?: it::class.simpleName}" }
                        // 同步写入日志，便于事后排查（诊断结果同样可追溯）
                        runCatching { me.rerere.rikkahub.data.log.AppLog.d("BackendDiag", diag ?: "") }
                        diagLoading = false
                    }
                },
            ) {
                Text(stringResource(R.string.backend_diag_check))
            }
        }
        diag?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

}


/** 读取后端服务 /status，输出便于判断的摘要（是否待应答 / 能否取消 / 审批模式等）。 */
private suspend fun backendStatusDigest(provider: ProviderSetting.Backend): String {
    val st =
        me.rerere.ai.provider.providers.backend.BackendApi(
            baseUrl = provider.baseUrl,
            username = provider.username,
            password = provider.password,
            token = provider.token,
        ).getStatus() ?: return "无法读取服务端状态"
    return buildString {
        appendLine("待应答交互 pendingPrompt: ${st.pendingPrompt == true}")
        appendLine("可取消 cancellable: ${st.cancellable == true}")
        appendLine("已请求取消 cancelRequested: ${st.cancelRequested == true}")
        appendLine("审批模式 toolApprovalMode: ${st.toolApprovalMode ?: "-"}")
        st.cwd?.let { appendLine("工作目录: $it") }
        st.sessionPath?.let { appendLine("会话: $it") }
    }.trimEnd()
}
