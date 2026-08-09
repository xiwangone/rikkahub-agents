package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AddCircle
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.EyeOff
import me.rerere.hugeicons.stroke.Key01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject

/**
 * 密钥列表（三级页）：分组展示 + 小眼睛显隐 + 新增/编辑/删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultCredentialsPage() {
    val repository: CredentialVaultRepository = koinInject()
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<VaultCredentialEntity>>(emptyList()) }
    var revealedNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showEditor by remember { mutableStateOf<EditorMode?>(null) }
    var deleteTarget by remember { mutableStateOf<VaultCredentialEntity?>(null) }

    suspend fun refresh() {
        entries = repository.getAll()
    }

    LaunchedEffect(Unit) { refresh() }

    fun toggleReveal(entry: VaultCredentialEntity) {
        val name = entry.name
        revealedNames =
            if (name in revealedNames) revealedNames - name
            else revealedNames + name
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("密钥列表（${entries.size}）") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showEditor = EditorMode.Create() }) {
                        Icon(HugeIcons.AddCircle, "新增")
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(HugeIcons.Key01, null, modifier = Modifier.padding(8.dp))
                Text("还没有凭证", style = MaterialTheme.typography.titleMedium)
                Text(
                    "点右上角 + 新建，或回上一页「从文件导入」批量导入 load-creds.sh。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { showEditor = EditorMode.Create() }) { Text("新建凭证") }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 按组展示
                val grouped = entries.groupBy { it.group }
                grouped.forEach { (group, list) ->
                    item(key = "group_$group") {
                        Text(
                            group,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { it.id }) { entry ->
                        CredentialRow(
                            entry = entry,
                            revealed = entry.name in revealedNames,
                            onRevealToggle = { toggleReveal(entry) },
                            onEdit = { showEditor = EditorMode.Edit(entry) },
                            onDelete = { deleteTarget = entry },
                        )
                    }
                }
            }
        }
    }

    // 新增/编辑 BottomSheet 弹窗
    showEditor?.let { mode ->
        CredentialEditorDialog(
            mode = mode,
            onDismiss = { showEditor = null },
            onSave = { name, value, description, group ->
                scope.launch {
                    repository.save(name, value, description, group)
                    showEditor = null
                    refresh()
                }
            },
        )
    }

    // 删除确认
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除 ${target.name}？") },
            text = { Text("将从密钥库移除该凭证。此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            repository.delete(target)
                            refresh()
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

/** 编辑器模式：新建 or 编辑已有 */
sealed class EditorMode {
    data class Create(val initialName: String = "") : EditorMode()
    data class Edit(val entry: VaultCredentialEntity) : EditorMode()
}

/** 单条凭证行：名称 + 描述 + 脱敏/明文切换 + 编辑/删除 */
@Composable
private fun CredentialRow(
    entry: VaultCredentialEntity,
    revealed: Boolean,
    onRevealToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val repository: CredentialVaultRepository = koinInject()
    var plaintext by remember(entry.id) { mutableStateOf<String?>(null) }

    // 需要显示明文时才解密（内存，用完即弃）
    if (revealed && plaintext == null) {
        plaintext = repository.decryptValue(entry)
    }

    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                if (entry.description.isNotBlank()) {
                    Text(
                        entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (revealed && plaintext != null) plaintext!! else CredentialVaultRepository.mask(entry.valueLength.takeIf { it > 0 }?.let { "x".repeat(it) } ?: "******"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (revealed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRevealToggle) {
                Icon(if (revealed) HugeIcons.EyeOff else HugeIcons.Eye, if (revealed) "隐藏" else "显示")
            }
            IconButton(onClick = onEdit) {
                Icon(HugeIcons.Edit02, "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete02, "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 新增/编辑对话框 */
@Composable
private fun CredentialEditorDialog(
    mode: EditorMode,
    onDismiss: () -> Unit,
    onSave: (name: String, value: String, description: String, group: String) -> Unit,
) {
    val repository: CredentialVaultRepository = koinInject()
    var name by remember { mutableStateOf((mode as? EditorMode.Edit)?.entry?.name ?: (mode as? EditorMode.Create)?.initialName ?: "") }
    var value by remember { mutableStateOf((mode as? EditorMode.Edit)?.entry?.let { repository.decryptValue(it) } ?: "") }
    var description by remember { mutableStateOf((mode as? EditorMode.Edit)?.entry?.description ?: "") }
    var group by remember { mutableStateOf((mode as? EditorMode.Edit)?.entry?.group ?: "Other") }
    var nameError by remember { mutableStateOf(false) }
    var valueError by remember { mutableStateOf(false) }

    val isEdit = mode is EditorMode.Edit

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑凭证" else "新建凭证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.uppercase().replace(Regex("[^A-Z0-9_]"), "_")
                        nameError = false
                    },
                    label = { Text("变量名（如 DEEPSEEK_API_KEY）") },
                    singleLine = true,
                    enabled = !isEdit, // 编辑模式不改变量名（唯一键）
                    isError = nameError,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; valueError = false },
                    label = { Text(if (isEdit) "新值（留空则不修改）" else "凭证值") },
                    singleLine = false,
                    isError = valueError,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("分组") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) { nameError = true; return@Button }
                    if (!isEdit && value.isBlank()) { valueError = true; return@Button }
                    // 编辑模式：value 留空 = 保留原值（在 onSave 里处理）
                    onSave(name, value, description, group)
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
