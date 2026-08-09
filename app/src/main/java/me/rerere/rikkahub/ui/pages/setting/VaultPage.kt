package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.vault.CredentialImporter
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.VaultBiometric
import me.rerere.rikkahub.data.vault.VaultExporter
import me.rerere.rikkahub.data.vault.VaultPreferences
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 安全凭证库（二级设置页）。
 *
 * 功能设置区：指纹开关（MVP 占位，后续接 BiometricPrompt）、导入凭证、
 * 应急导出（后续）、撤销全部会话（后续）。
 * 入口 → 三级「密钥列表」页（VaultCredentialsPage）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultPage() {
    val navController = LocalNavController.current
    val repository: CredentialVaultRepository = koinInject()
    val vaultPreferences: VaultPreferences = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var credentialCount by remember { mutableStateOf(0) }
    var importResult by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var backupResult by remember { mutableStateOf<String?>(null) }
    var auditLogs by remember { mutableStateOf<List<VaultAuditLogEntity>>(emptyList()) }
    val biometricEnabled by vaultPreferences.biometricEnabled.collectAsState(initial = true)

    // ── 局部函数（必须先定义，供下方 launcher lambda 引用）──
    fun refreshCount() {
        scope.launch { credentialCount = repository.count() }
    }
    fun refreshAudit() {
        scope.launch { auditLogs = repository.recentAudit(100) }
    }
    fun formatTime(tsMs: Long): String {
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(tsMs))
    }
    refreshCount()
    refreshAudit()

    // 备份：整个库（含分组）加密导出 .vault
    val biometricBuffer: BiometricResultBuffer = koinInject()
    val backupExportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null && backupPassword.isNotBlank()) {
                val titleStr = context.getString(R.string.vault_biometric_export_title)
                val cancelledStr = context.getString(R.string.vault_export_cancelled)
                val successStr = context.getString(R.string.vault_backup_success)
                val failedStr = context.getString(R.string.vault_export_failed)
                scope.launch {
                    runCatching {
                        if (biometricEnabled) {
                            val ok = VaultBiometric.authenticate(context, biometricBuffer, titleStr)
                            if (!ok) {
                                backupResult = cancelledStr
                                return@launch
                            }
                        }
                        val entries = repository.getAll()
                        val plaintexts =
                            entries.mapNotNull { e ->
                                repository.decryptValue(e)?.let { VaultExporter.Quad(e.name, it, e.description, e.grp) }
                            }
                        val vaultJson = VaultExporter.exportWithGroups(backupPassword, plaintexts)
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(vaultJson.encodeToByteArray())
                        }
                        plaintexts.forEach { q ->
                            repository.logAccess(q.name, "backup", "backup")
                        }
                        backupResult = successStr.format(plaintexts.size)
                    }.onFailure { e ->
                        backupResult = failedStr.format(e.message ?: "")
                    }
                }
            }
        }

    // 恢复：从 .vault 包导入（覆盖/合并）
    val backupRestoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && backupPassword.isNotBlank()) {
                val restoreSuccessStr = context.getString(R.string.vault_backup_restore_success)
                val restoreFailedStr = context.getString(R.string.vault_backup_restore_failed)
                scope.launch {
                    runCatching {
                        val content =
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                BufferedReader(InputStreamReader(input)).readText()
                            } ?: ""
                        val restored = VaultExporter.import(content, backupPassword)
                        restored.forEach { q ->
                            repository.save(q.name, q.plaintext, q.description, q.group.ifEmpty { "Other" })
                        }
                        backupResult = restoreSuccessStr.format(restored.size)
                    }.onFailure { e ->
                        backupResult = restoreFailedStr.format(e.message ?: "")
                    }
                    refreshCount()
                }
            }
        }

    // 导出：加密 .vault 包写入用户选择的位置
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null && exportPassword.isNotBlank()) {
                val titleStr = context.getString(R.string.vault_biometric_export_title)
                val cancelledStr = context.getString(R.string.vault_export_cancelled)
                val successStr = context.getString(R.string.vault_export_success)
                val failedStr = context.getString(R.string.vault_export_failed)
                scope.launch {
                    runCatching {
                        if (biometricEnabled) {
                            val ok = VaultBiometric.authenticate(context, biometricBuffer, titleStr)
                            if (!ok) {
                                exportResult = cancelledStr
                                return@launch
                            }
                        }
                        val entries = repository.getAll()
                        val plaintexts =
                            entries.mapNotNull { e ->
                                repository.decryptValue(e)?.let { Triple(e.name, it, e.description) }
                            }
                        val vaultJson = VaultExporter.export(exportPassword, plaintexts)
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(vaultJson.encodeToByteArray())
                        }
                        plaintexts.forEach { (name, _, _) ->
                            repository.logAccess(name, "export", "export")
                        }
                        exportResult = successStr.format(plaintexts.size)
                    }.onFailure { e ->
                        exportResult = failedStr.format(e.message ?: "")
                    }
                }
            }
        }

    // SAF 文件选择：导入 load-creds.sh
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    runCatching {
                        val content =
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                BufferedReader(InputStreamReader(input)).readText()
                            } ?: ""
                        val parsed = CredentialImporter.parse(content)
                        val imported = repository.importEntries(parsed)
                        importResult = context.getString(R.string.vault_import_success, imported, parsed.size)
                    }.onFailure { e ->
                        importResult = context.getString(R.string.vault_import_failed, e.message ?: "")
                    }
                    refreshCount()
                }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_vault_title)) },
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.vault_credential_count, credentialCount), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { navController.navigate(me.rerere.rikkahub.Screen.VaultCredentials) }) {
                        Text(stringResource(R.string.vault_manage_credentials))
                    }
                }
                Text(
                    text = stringResource(R.string.vault_fingerprint_plan),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.vault_fingerprint_title), style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.vault_fingerprint_desc), style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = biometricEnabled,
                                onCheckedChange = { scope.launch { vaultPreferences.setBiometricEnabled(it) } },
                            )
                        }
                        Text(
                            text = stringResource(R.string.vault_fingerprint_plan),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.vault_audit_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = stringResource(R.string.vault_audit_desc, auditLogs.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        auditLogs.take(8).forEach { log ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(log.credentialName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${log.caller} · ${log.action}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    formatTime(log.tsMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { scope.launch { repository.clearAudit(); refreshAudit() } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.vault_audit_clear))
                        }
                    }
                }
            }

            item {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.vault_import_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = stringResource(R.string.vault_import_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(HugeIcons.Upload02, null, modifier = Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.vault_import_button))
                        }
                        importResult?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.vault_export_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = stringResource(R.string.vault_export_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = exportPassword,
                            onValueChange = { exportPassword = it },
                            label = { Text(stringResource(R.string.vault_export_password_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = {
                                if (exportPassword.isBlank()) {
                                    exportResult = context.getString(R.string.vault_export_password_required)
                                    return@OutlinedButton
                                }
                                exportLauncher.launch("RikkaHub-Vault-${System.currentTimeMillis()}.vault")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(HugeIcons.Upload02, null, modifier = Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.vault_export_button))
                        }
                        exportResult?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.vault_backup_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = stringResource(R.string.vault_backup_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (backupPassword.isBlank()) {
                                        backupResult = context.getString(R.string.vault_backup_password_required)
                                        return@OutlinedButton
                                    }
                                    backupExportLauncher.launch("RikkaHub-Vault-Backup-${System.currentTimeMillis()}.vault")
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.vault_backup_button))
                            }
                            OutlinedButton(
                                onClick = { backupRestoreLauncher.launch(arrayOf("application/json", "*/*")) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.vault_restore_button))
                            }
                        }
                        OutlinedTextField(
                            value = backupPassword,
                            onValueChange = { backupPassword = it },
                            label = { Text(stringResource(R.string.vault_backup_password_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        backupResult?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.vault_danger_zone), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(
                            onClick = { showClearDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(HugeIcons.LockKey, null, modifier = Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.vault_clear_all))
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.vault_clear_confirm_title)) },
            text = { Text(stringResource(R.string.vault_clear_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        scope.launch {
                            repository.clearAll()
                            refreshCount()
                        }
                    },
                ) { Text(stringResource(R.string.vault_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.vault_cancel)) } },
        )
    }
}
