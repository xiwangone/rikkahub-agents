package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.LocalMcpProfile
import me.rerere.rikkahub.data.ai.tools.LocalToolCatalog
import me.rerere.rikkahub.data.ai.tools.LocalToolCategory
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.vault.CredentialType
import me.rerere.rikkahub.ui.components.setting.NetworkAccessField
import me.rerere.rikkahub.ui.components.setting.SettingTextField
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.components.vault.SecretRefField

private val ALL_LOCAL_TOOLS: List<LocalToolOption> = LocalToolCatalog.all

@Composable
internal fun LocalMcpProfileModal(
    initial: LocalMcpProfile?,
    onConfirm: (LocalMcpProfile) -> Unit,
    onDismiss: () -> Unit,
    presets: List<String>,
    onPresetsChange: (List<String>) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "8788") }
    var selected by remember { mutableStateOf(initial?.allowedTools ?: emptyList()) }
    var expandedCategories by remember { mutableStateOf(setOf<LocalToolCategory>()) }
    var listenScope by remember { mutableStateOf(initial?.listenScope ?: "loopback") }
    var allowedNetworks by remember { mutableStateOf(initial?.allowedNetworks ?: "") }
    var authTokenRef by remember { mutableStateOf(initial?.authTokenRef ?: "") }
    val context = LocalContext.current
    val unnamedText = stringResource(R.string.mcp_page_unnamed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.mcp_page_dialog_add_title) else stringResource(R.string.mcp_page_dialog_edit_title)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
            ) {
                SettingTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.mcp_page_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SettingTextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.mcp_page_port_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.mcp_profile_listen_scope), style = MaterialTheme.typography.labelLarge)
                listOf(
                    "loopback" to stringResource(R.string.net_scope_loopback),
                    "lan" to stringResource(R.string.net_scope_lan),
                    "any" to stringResource(R.string.net_scope_any),
                ).forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = listenScope == value, onClick = { listenScope = value })
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                NetworkAccessField(
                    value = allowedNetworks,
                    onValueChange = { allowedNetworks = it },
                    presets = presets,
                    onPresetsChange = onPresetsChange,
                    label = stringResource(R.string.net_allowed_networks),
                    supportingText = stringResource(R.string.net_allowed_networks_desc),
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretRefField(
                    value = authTokenRef,
                    onValueChange = { authTokenRef = it },
                    label = stringResource(R.string.mcp_auth_token_ref),
                    description = stringResource(R.string.mcp_auth_token_ref_desc),
                    nameHint = "MCP_AUTH_TOKEN",
                    typeHint = CredentialType.API_KEY,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.mcp_page_tools_header), style = MaterialTheme.typography.labelLarge)
                        Row {
                            TextButton(onClick = { selected = ALL_LOCAL_TOOLS }) { Text(stringResource(R.string.mcp_page_select_all)) }
                            TextButton(onClick = { selected = emptyList() }) { Text(stringResource(R.string.mcp_page_clear)) }
                        }
                    }
                LocalToolCatalog.groups().forEach { (category, tools) ->
                    val expanded = category in expandedCategories
                    val pickedCount = tools.count { it in selected }
                    Surface(
                        shape = cardGroupItemShape(0, 1),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expandedCategories =
                                                if (expanded) expandedCategories - category else expandedCategories + category
                                        },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = categoryDisplayName(category) + "  ($pickedCount/${tools.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(if (expanded) "\u25b4" else "\u25be", style = MaterialTheme.typography.labelMedium)
                            }
                            if (expanded) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(onClick = { selected = (selected + tools).distinct() }) {
                                        Text(stringResource(R.string.mcp_page_select_all))
                                    }
                                    TextButton(onClick = { selected = selected - tools.toSet() }) {
                                        Text(stringResource(R.string.mcp_page_clear))
                                    }
                                }
                            }
                        }
                    }
                    if (!expanded) return@forEach
                    FlowRow {
                        tools.forEach { tool ->
                            val checked = tool in selected
                            FilterChip(
                                selected = checked,
                                onClick = { selected = if (checked) selected - tool else selected + tool },
                                label = { Text(toolDisplayName(tool)) },
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val base = initial ?: LocalMcpProfile(id = "local-mcp-${System.currentTimeMillis()}", name = "")
                    onConfirm(
                        base.copy(
                            name = name.ifBlank { unnamedText },
                            port = port.toIntOrNull() ?: 8788,
                            allowedTools = selected,
                            listenScope = listenScope,
                            allowedNetworks = allowedNetworks,
                            authTokenRef = authTokenRef,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun toolDisplayName(t: LocalToolOption): String = stringResource(LocalToolCatalog.labelRes(t))

@Composable
private fun categoryDisplayName(category: LocalToolCategory): String =
    stringResource(
        when (category) {
            LocalToolCategory.DEVICE_INFO -> R.string.mcp_category_device_info
            LocalToolCategory.DEVICE_CONTROL -> R.string.mcp_category_device_control
            LocalToolCategory.MEDIA -> R.string.mcp_category_media
            LocalToolCategory.FILES -> R.string.mcp_category_files
            LocalToolCategory.PERSONAL -> R.string.mcp_category_personal
            LocalToolCategory.REMOTE -> R.string.mcp_category_remote
            LocalToolCategory.VAULT -> R.string.mcp_category_vault
            LocalToolCategory.AUTOMATION -> R.string.mcp_category_automation
            LocalToolCategory.WEB_AI -> R.string.mcp_category_web_ai
        },
    )
