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
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * 后端服务页（连接管理中枢——2026-08-14 通用化）。
 *
 * 后端连接可保存/切换/删除：reasonix（执行后端）/ SSH / 自定义统一模型。
 * 对话页 executionBackend 引用后端连接 id（local 内置）。
 */
@Composable
fun BackendServicePage() {
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BackendConnection?>(null) }

    // 添加/编辑 reasonix 后端弹窗
    if (showAddDialog || editing != null) {
        val initial = editing
        BackendEditDialog(
            initial = initial,
            onDismiss = { showAddDialog = false; editing = null },
            onSave = { conn ->
                scope.launch {
                    val current = settingsStore.settingsFlow.value
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
                    settingsStore.update(updated)
                    showAddDialog = false
                    editing = null
                }
            },
            onDelete = {
                scope.launch {
                    val current = settingsStore.settingsFlow.value
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
                    headlineContent = { Text("本机（local）") },
                    supportingContent = {
                        Text(
                            if (settings.executionBackend == BackendTypes.LOCAL) "✅ 当前执行后端" else "内置默认——AI 本机执行",
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
                            Text(if (settings.executionBackend == BackendTypes.LOCAL) "当前" else "切换", color = MaterialTheme.colorScheme.primary)
                        }
                    },
                )

                settings.backendConnections.forEach { conn ->
                    item(
                        headlineContent = { Text(conn.name) },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(when (conn.type) {
                                        BackendTypes.REASONIX -> "Reasonix 执行后端"
                                        BackendTypes.SSH -> "SSH 后端"
                                        else -> "自定义后端"
                                    })
                                    if (conn.endpoint.isNotBlank()) append("\n").append(conn.endpoint)
                                    if (settings.executionBackend == conn.id) append("\n✅ 当前执行后端")
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
                                        Text("切换", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                TextButton(onClick = { editing = conn }) {
                                    Text("编辑")
                                }
                            }
                        },
                    )
                }
            }

            // 添加 reasonix 后端
            CardGroup {
                item(
                    onClick = { showAddDialog = true },
                    headlineContent = {
                        Text(stringResource(R.string.backend_service_add), color = MaterialTheme.colorScheme.primary)
                    },
                    supportingContent = { Text("添加 Reasonix / SSH / 自定义后端连接") },
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
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: BackendTypes.REASONIX) }
    var endpoint by remember { mutableStateOf(initial?.endpoint ?: "") }
    var authRef by remember { mutableStateOf(initial?.authRef ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加后端连接" else "编辑后端连接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称（如 reasonix-ecs）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = type, onValueChange = { type = it }, label = { Text("类型（reasonix/ssh/custom）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("地址（reasonix baseUrl / SSH host:port）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = authRef, onValueChange = { authRef = it }, label = { Text("Vault 凭证引用（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                            type = type.trim().ifBlank { BackendTypes.REASONIX },
                            endpoint = endpoint.trim(),
                            authRef = authRef.trim().ifBlank { null },
                            createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { onDelete(initial) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
