package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ProviderDescriptors
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun BackendProviderConfigure(
    provider: ProviderSetting.Backend,
    onEdit: (ProviderSetting.Backend) -> Unit,
) {
    ProviderDescriptors.get(provider.id).description()

    CardGroup {
        switchItem(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_enable)) },
        )
    }

    CardGroup {
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_provider_page_name)) },
        ) {
            OutlinedTextField(
                value = provider.name,
                onValueChange = { onEdit(provider.copy(name = it.trim())) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        formItem(
            headlineContent = { Text(stringResource(R.string.backend_type)) },
        ) {
            val typeOptions =
                listOf(
                    "backend" to "Backend",
                    "custom" to stringResource(R.string.backend_type_custom),
                    "cli" to "CLI",
                )
            ConnectedChoiceButtons(
                options = typeOptions,
                selected = typeOptions.firstOrNull { it.first == provider.backendType } ?: typeOptions.first(),
                onSelect = { onEdit(provider.copy(backendType = it.first)) },
            ) { (_, label), selected ->
                ConnectedChoiceLabel(text = label, selected = selected)
            }
        }
        if (provider.backendType != "cli") {
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
            ) {
                OutlinedTextField(
                    value = provider.baseUrl,
                    onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.backend_base_url_example)) },
                    isError = provider.baseUrl.isNotBlank() && provider.baseUrl.toHttpUrlOrNull() == null,
                )
            }
        }
        if (provider.backendType == "cli") {
            formItem(
                headlineContent = { Text(stringResource(R.string.backend_cli_command)) },
            ) {
                OutlinedTextField(
                    value = provider.cliCommand,
                    onValueChange = { onEdit(provider.copy(cliCommand = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.backend_cli_command_hint)) },
                )
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.backend_cli_ssh_host)) },
            ) {
                OutlinedTextField(
                    value = provider.cliSshHost,
                    onValueChange = { onEdit(provider.copy(cliSshHost = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.backend_cli_ssh_host_hint)) },
                )
            }
        }
        formItem {
            SecretRefField(
                value = provider.token,
                onValueChange = { onEdit(provider.copy(token = it.trim())) },
                label = stringResource(R.string.backend_token_serve),
                nameHint = "BACKEND_TOKEN",
            )
        }
    }

    if (provider.backendType == "backend") {
        CardGroup {
            formItem(
                headlineContent = { Text(stringResource(R.string.backend_connection_mode)) },
                supportingContent = {
                    Text(
                        if (provider.connectionMode == "serve") {
                            stringResource(R.string.backend_mode_serve_desc)
                        } else {
                            stringResource(R.string.backend_mode_ssh_desc)
                        },
                    )
                },
            ) {
                val modes =
                    listOf(
                        "serve" to stringResource(R.string.backend_mode_serve),
                        "ssh" to stringResource(R.string.backend_mode_ssh),
                    )
                ConnectedChoiceButtons(
                    options = modes,
                    selected = modes.firstOrNull { it.first == provider.connectionMode } ?: modes.first(),
                    onSelect = { onEdit(provider.copy(connectionMode = it.first)) },
                ) { (_, label), selected ->
                    ConnectedChoiceLabel(text = label, selected = selected)
                }
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.backend_username_basic)) },
            ) {
                OutlinedTextField(
                    value = provider.username,
                    onValueChange = { onEdit(provider.copy(username = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.backend_password_basic)) },
            ) {
                var passwordVisible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = provider.password,
                    onValueChange = { onEdit(provider.copy(password = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View,
                                contentDescription =
                                    stringResource(
                                        if (passwordVisible) {
                                            R.string.accessibility_hide_password
                                        } else {
                                            R.string.accessibility_show_password
                                        },
                                    ),
                            )
                        }
                    },
                )
            }
            switchItem(
                checked = provider.webBridgeEnabled,
                onCheckedChange = { onEdit(provider.copy(webBridgeEnabled = it)) },
                headlineContent = { Text(stringResource(R.string.backend_web_bridge_section)) },
                supportingContent = { Text(stringResource(R.string.backend_web_bridge_desc)) },
            )
        }

        Text(
            text = stringResource(R.string.backend_session_server_managed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (provider.backendType == "backend" && provider.baseUrl.isNotBlank()) {
        val resources = androidx.compose.ui.platform.LocalResources.current
        val scope = rememberCoroutineScope()
        var diag by remember { mutableStateOf<String?>(null) }
        var diagLoading by remember { mutableStateOf(false) }

        CardGroup(title = { Text(stringResource(R.string.backend_diag_title)) }) {
            formItem {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !diagLoading,
                        onClick = {
                            diagLoading = true
                            scope.launch {
                                diag =
                                    runCatching { backendStatusDigest(resources, provider) }
                                        .getOrElse {
                                            resources.getString(
                                                R.string.backend_diag_check_failed,
                                                it.message ?: it::class.simpleName ?: "",
                                            )
                                        }
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
    }
}

private suspend fun backendStatusDigest(
    resources: android.content.res.Resources,
    provider: ProviderSetting.Backend,
): String {
    val st =
        me.rerere.ai.provider.providers.backend.BackendApi(
            baseUrl = provider.baseUrl,
            username = provider.username,
            password = provider.password,
            token = provider.token,
        ).getStatus() ?: return resources.getString(R.string.backend_diag_status_unavailable)
    return buildString {
        appendLine(resources.getString(R.string.backend_diag_pending_prompt, (st.pendingPrompt == true).toString()))
        appendLine(resources.getString(R.string.backend_diag_cancellable, (st.cancellable == true).toString()))
        appendLine(resources.getString(R.string.backend_diag_cancel_requested, (st.cancelRequested == true).toString()))
        appendLine(resources.getString(R.string.backend_diag_tool_approval_mode, st.toolApprovalMode ?: "-"))
        st.cwd?.let { appendLine(resources.getString(R.string.backend_diag_cwd, it)) }
        st.sessionPath?.let { appendLine(resources.getString(R.string.backend_diag_session_path, it)) }
    }.trimEnd()
}
