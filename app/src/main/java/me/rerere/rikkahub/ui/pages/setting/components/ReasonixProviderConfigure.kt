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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
 * Reasonix Provider 配置页。
 * - baseUrl：Reasonix serve 入口（nginx Basic Auth 或直连 token）
 * - username/password：nginx Basic Auth（与 reasonix-android 客户端一致）
 * - token：Reasonix serve token 模式（留空则走 Basic Auth）
 */
@Composable
fun ReasonixProviderConfigure(
    provider: ProviderSetting.Reasonix,
    onEdit: (ProviderSetting.Reasonix) -> Unit,
) {
    provider.description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    // 后端类型选择（reasonix / openclaw / custom / cli）
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
            "reasonix" to "Reasonix",
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

    // baseUrl：reasonix/custom 显示；cli 类型改用命令
    if (provider.backendType != "cli") {
        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.reasonix_base_url_example)) },
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

    // reasonix 专用：连接方式 + Basic Auth（其他后端类型不显示）
    if (provider.backendType == "reasonix") {
        // 连接方式选择
        Text(
            text = stringResource(R.string.reasonix_connection_mode),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("serve" to stringResource(R.string.reasonix_mode_serve), "ssh" to stringResource(R.string.reasonix_mode_ssh)).forEach { (mode, label) ->
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
                    stringResource(R.string.reasonix_mode_serve_desc)
                } else {
                    stringResource(R.string.reasonix_mode_ssh_desc)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = provider.username,
            onValueChange = { onEdit(provider.copy(username = it.trim())) },
            label = { Text(stringResource(R.string.reasonix_username_basic)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        var passwordVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = provider.password,
            onValueChange = { onEdit(provider.copy(password = it)) },
            label = { Text(stringResource(R.string.reasonix_password_basic)) },
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
        label = { Text(stringResource(R.string.reasonix_token_serve)) },
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
    // reasonix 专用：Web 桥（其他后端类型不显示）
    if (provider.backendType == "reasonix") {
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.reasonix_web_bridge_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Switch(
                checked = provider.webBridgeEnabled,
                onCheckedChange = { onEdit(provider.copy(webBridgeEnabled = it)) },
            )
        }
        Text(
            text = stringResource(R.string.reasonix_web_bridge_desc),
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

    // reasonix 专用：服务端会话说明
    if (provider.backendType == "reasonix") {
        Text(
            text = stringResource(R.string.reasonix_session_server_managed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
