package me.rerere.rikkahub.ui.pages.setting.backend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.BackendConnection
import me.rerere.rikkahub.data.model.BackendTypes
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * 后端服务页（连接管理中枢——2026-08-14 通用化）。
 *
 * 后端连接可保存/切换/删除：backend（执行后端）/ SSH / 自定义统一模型。
 * 对话页 executionBackend 引用后端连接 id（local 内置）。
 */
@Composable
fun BackendServicePage() {
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BackendConnection?>(null) }

    // 添加/编辑 backend 后端弹窗
    if (showAddDialog || editing != null) {
        val initial = editing
        BackendEditDialog(
            initial = initial,
            onDismiss = { showAddDialog = false; editing = null },
            onSave = { conn ->
                scope.launch {
                    // 等待真实 settings 加载完成（避免 DataStore 大文件加载慢时 value 仍是 dummy(init=true)，被 update 静默拒绝）
                    val current = settingsStore.settingsFlow.first { !it.init }
                    val updated =
                        if (initial == null) {
                            current.copy(
                                backendConnections = current.backendConnections + conn,
                            )
                        } else {
                            current.copy(
                                backendConnections =
                                    current.backendConnections.map {
                                        if (it.id == conn.id) conn else it
                                    },
                            )
                        }
                    me.rerere.rikkahub.data.log.AppLog.d("BackendPage", "保存后端: ${conn.name} type=${conn.type} endpoint=${conn.endpoint} 列表=${updated.backendConnections.size} 个")
                    settingsStore.update(updated)
                    showAddDialog = false
                    editing = null
                }
            },
            onDelete = {
                scope.launch {
                    val current = settingsStore.settingsFlow.first { !it.init }
                    settingsStore.update(
                        current.copy(
                            backendConnections = current.backendConnections.filterNot { c -> c.id == it.id },
                            executionBackend = if (current.executionBackend == it.id) "local" else current.executionBackend,
                        ),
                    )
                    editing = null
                }
            },
        )
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.backend_service_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 后端连接列表
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.backend_service_local)) },
                    supportingContent = {
                        Text(
                            if (settings.executionBackend == BackendTypes.LOCAL) stringResource(R.string.backend_service_current_badge) else stringResource(R.string.backend_service_local_desc),
                        )
                    },
                    trailingContent = {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    settingsStore.update(
                                        settingsStore.settingsFlow.value.copy(executionBackend = BackendTypes.LOCAL),
                                    )
                                }
                            },
                        ) {
                            Text(
                                if (settings.executionBackend == BackendTypes.LOCAL) stringResource(R.string.backend_service_current) else stringResource(R.string.backend_service_switch),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )

                settings.backendConnections.forEach { conn ->
                    item(
                        headlineContent = { Text(conn.name) },
                        supportingContent = {
                            val typeLabel =
                                when (conn.type) {
                                    BackendTypes.BACKEND -> stringResource(R.string.backend_service)
                                    BackendTypes.SSH -> stringResource(R.string.backend_service_type_ssh)
                                    else -> stringResource(R.string.backend_service_type_custom)
                                }
                            val currentBadge = stringResource(R.string.backend_service_current_badge)
                            Text(
                                buildString {
                                    append(typeLabel)
                                    if (conn.endpoint.isNotBlank()) append("\n").append(conn.endpoint)
                                    if (settings.executionBackend == conn.id) append("\n").append(currentBadge)
                                },
                            )
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (settings.executionBackend != conn.id) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                settingsStore.update(
                                                    settingsStore.settingsFlow.value.copy(executionBackend = conn.id),
                                                )
                                            }
                                        },
                                    ) {
                                        Text(stringResource(R.string.backend_service_switch), color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                TextButton(onClick = { editing = conn }) {
                                    Text(stringResource(R.string.edit))
                                }
                            }
                        },
                    )
                }
            }

            // 添加 backend 后端
            CardGroup {
                item(
                    onClick = { showAddDialog = true },
                    headlineContent = {
                        Text(stringResource(R.string.backend_service_add), color = MaterialTheme.colorScheme.primary)
                    },
                    supportingContent = { Text(stringResource(R.string.backend_service_add_conn_desc)) },
                )
            }

            // 后端说明
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.backend_service_about_title)) },
                    supportingContent = {
                        Text(stringResource(R.string.backend_service_about_desc))
                    },
                )
            }
        }
    }
}

@Composable
private fun BackendEditDialog(
    initial: BackendConnection?,
    onDismiss: () -> Unit,
    onSave: (BackendConnection) -> Unit,
    onDelete: (BackendConnection) -> Unit,
) {
    val vaultRepo: CredentialVaultRepository = koinInject()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: BackendTypes.BACKEND) }
    var endpoint by remember { mutableStateOf(initial?.endpoint ?: "") }
    var authRef by remember { mutableStateOf(initial?.authRef ?: "") }
    var credentialNames by remember { mutableStateOf<List<String>>(emptyList()) }

    // 加载凭证库名称，供下拉选择
    LaunchedEffect(Unit) {
        credentialNames = runCatching { vaultRepo.getAll().map { it.name } }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.backend_service_dialog_add_title) else stringResource(R.string.backend_service_dialog_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.backend_service_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = type, onValueChange = { type = it }, label = { Text(stringResource(R.string.backend_service_type_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text(stringResource(R.string.backend_service_endpoint_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = authRef, onValueChange = { authRef = it }, label = { Text(stringResource(R.string.backend_service_auth_ref_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                // 凭证库下拉快捷选择：点击填入 authRef
                if (credentialNames.isNotEmpty()) {
                    Select(
                        options = credentialNames,
                        selectedOption = credentialNames.firstOrNull() ?: "",
                        onOptionSelected = { authRef = it },
                        optionToString = { it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(stringResource(R.string.backend_service_vault_empty_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) return@TextButton
                    onSave(
                        BackendConnection(
                            id = initial?.id ?: "backend-${System.currentTimeMillis()}",
                            name = name.trim(),
                            type = type.trim().ifBlank { BackendTypes.BACKEND },
                            endpoint = endpoint.trim(),
                            authRef = authRef.trim().ifBlank { null },
                            createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { onDelete(initial) }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
