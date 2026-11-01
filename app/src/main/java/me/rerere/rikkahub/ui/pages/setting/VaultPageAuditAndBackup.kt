package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.vault.AuditAnomaly
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer
import me.rerere.rikkahub.data.vault.AuditAnomalyDetector
import me.rerere.rikkahub.data.vault.VaultBiometric
import me.rerere.rikkahub.data.vault.VaultFormats
import me.rerere.rikkahub.data.vault.VaultPreferences
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject

/** 剪贴板标签：系统剪贴板条目名，不面向界面，无需资源化。 */
private const val CLIPBOARD_LABEL = "vault-session"

/**
 * 安全凭证库（二级设置页）。
 *
 * 功能设置区：指纹开关（MVP 占位，后续接 BiometricPrompt）、导入凭证、
 * 应急导出（后续）、撤销全部会话（后续）。
 * 入口 → 三级「密钥列表」页（VaultCredentialsPage）。
 */
@Composable
internal fun VaultAuditAndBackupSection(
    shownAudit: List<VaultAuditLogEntity>,
    auditGroups: List<List<VaultAuditLogEntity>>,
    auditAnomalies: List<AuditAnomaly>,
    auditFilter: String?,
    onAuditFilterChange: (String?) -> Unit,
    auditActionFilter: String?,
    onAuditActionFilterChange: (String?) -> Unit,
    auditTimeDays: Int,
    onAuditTimeDaysChange: (Int) -> Unit,
    auditRetentionDays: Int,
    auditCap: Int,
    auditRollupMinutes: Int,
    onExportAudit: (String) -> Unit,
    onClearAuditConfirmed: suspend () -> Unit,
    importPassword: String,
    onImportPasswordChange: (String) -> Unit,
    onLaunchImport: () -> Unit,
    importResult: String?,
    exportFormat: String,
    onExportFormatSelect: (String) -> Unit,
    exportPassword: String,
    onExportPasswordChange: (String) -> Unit,
    onLaunchExport: () -> Unit,
    exportResult: String?,
    backupPassword: String,
    onBackupPasswordChange: (String) -> Unit,
    onLaunchBackupExport: (String) -> Unit,
    onLaunchBackupRestore: () -> Unit,
    backupResult: String?,
    onShowClearDialog: () -> Unit,
    actionNeedsAuth: (String) -> Boolean,
    formatTime: (Long) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            run {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val navController = LocalNavController.current
                val vaultPreferences: VaultPreferences = koinInject()
                val biometricBuffer: BiometricResultBuffer = koinInject()
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.vault_audit_title), style = MaterialTheme.typography.titleSmall)
                            auditFilter?.let { f ->
                                // 当前筛选态：点一下清除（沿用名字与符号，不引入新文案）
                                Text(
                                    text = "✕ $f",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onAuditFilterChange(null) },
                                )
                            }
                            auditActionFilter?.let { a ->
                                Text(
                                    text = "✕ $a",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.clickable { onAuditActionFilterChange(null) },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                0 to stringResource(R.string.vault_audit_time_all),
                                1 to stringResource(R.string.vault_audit_time_1d),
                                7 to stringResource(R.string.vault_audit_time_7d),
                            ).forEach { (days, label) ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (auditTimeDays == days) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clickable { onAuditTimeDaysChange(days) },
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.vault_audit_desc, shownAudit.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 阈值异常提示（D4a，仅提示不阻断）
                        auditAnomalies.take(3).forEach { anomaly ->
                            Text(
                                text = stringResource(
                                    R.string.vault_audit_anomaly_line,
                                    anomaly.credentialName,
                                    AuditAnomalyDetector.WINDOW_MINUTES,
                                    anomaly.calls,
                                    anomaly.actions,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        auditGroups.take(30).forEach { group ->
                            val log = group.first()
                            val needsAuth = actionNeedsAuth(log.action)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = log.credentialName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.clickable {
                                            // 点即筛；再点同一条 = 取消筛选
                                            onAuditFilterChange(log.credentialName.takeIf { it != auditFilter })
                                        },
                                    )
                                    Text(
                                        "${if (needsAuth) "⚠ " else ""}${log.caller} · ${log.action}" +
                                            auditCountSuffix(log.count),
                                        style = MaterialTheme.typography.bodySmall,
                                        // 需授权门的用途高亮（env_inject / export_env 等）；点即按用途筛
                                        color = if (needsAuth) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable {
                                            onAuditActionFilterChange(log.action.takeIf { it != auditActionFilter })
                                        },
                                    )
                                    // 同链路的内层记录折叠为子行
                                    group.drop(1).forEach { sub ->
                                        Text(
                                            text = "↳ ${sub.caller} · ${sub.action}" + auditCountSuffix(sub.count),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 12.dp),
                                        )
                                    }
                                    // 归属维度（D3b）：有会话显示 #<cid8> · <model8>，点即跳到该会话；
                                    // 后台任务无会话则只显示来源标记 —— 均为技术标识，零新增文案
                                    val auditConversationId = log.conversationId
                                    if (auditConversationId != null) {
                                        Text(
                                            text = "#$auditConversationId" +
                                                (log.modelId?.let { "\n· $it" } ?: ""),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable {
                                                navController.navigate(Screen.Chat(auditConversationId))
                                            },
                                        )
                                    } else if (log.source != null) {
                                        Text(
                                            text = log.source,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    formatTime(log.lastTsMs ?: log.tsMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // 导出留档（只含名称/用途/时间，无秘密值）
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onExportAudit("json")
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.vault_audit_export_json)) }
                            OutlinedButton(
                                onClick = {
                                    onExportAudit("csv")
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.vault_audit_export_csv)) }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.vault_audit_retention_days), style = MaterialTheme.typography.labelSmall)
                            Select(
                                options = listOf(30, 90, 365),
                                selectedOption = auditRetentionDays,
                                onOptionSelected = { d -> scope.launch { vaultPreferences.setAuditRetentionDays(d) } },
                                optionToString = { it.toString() },
                                modifier = Modifier.weight(1f),
                            )
                            Text(stringResource(R.string.vault_audit_retention_count), style = MaterialTheme.typography.labelSmall)
                            Select(
                                options = listOf(500, 2000, 10000),
                                selectedOption = auditCap,
                                onOptionSelected = { c -> scope.launch { vaultPreferences.setAuditCap(c) } },
                                optionToString = { it.toString() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            stringResource(R.string.vault_audit_rollup_minutes), style = MaterialTheme.typography.labelSmall
                        )
                        Select(
                            options = listOf(1, 5, 10, 30, 60),
                            selectedOption = auditRollupMinutes,
                            onOptionSelected = { m -> scope.launch { vaultPreferences.setAuditRollupMinutes(m) } },
                            optionToString = { it.toString() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.vault_audit_rollup_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.vault_audit_protected_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val clearAuditTitle = stringResource(R.string.vault_audit_clear)
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val ok = VaultBiometric.authenticate(
                                        context = context,
                                        buffer = biometricBuffer,
                                        title = clearAuditTitle,
                                    )
                                    if (ok) {
                                        onClearAuditConfirmed()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(clearAuditTitle)
                        }
                    }
                }
            }

            run {
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
                        // 加密包导入需要口令（明文格式忽略本输入）；文案复用导出侧
                        OutlinedTextField(
                            value = importPassword,
                            onValueChange = onImportPasswordChange,
                            label = { Text(stringResource(R.string.vault_export_password_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = {
                                onLaunchImport()
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

            run {
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
                        // 导出格式选择
                        Text(stringResource(R.string.vault_page_export_format), style = MaterialTheme.typography.labelMedium)
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                VaultFormats.FORMAT_VAULT to stringResource(R.string.vault_format_vault_desc),
                                VaultFormats.FORMAT_CSV to stringResource(R.string.vault_format_csv_desc),
                                VaultFormats.FORMAT_BITWARDEN to stringResource(R.string.vault_format_bitwarden_desc),
                                VaultFormats.FORMAT_LOADCREDS to stringResource(R.string.vault_format_loadcreds_desc),
                            ).forEach { (fmt, label) ->
                                OutlinedButton(
                                    onClick = { onExportFormatSelect(fmt) },
                                    border = androidx.compose.foundation.BorderStroke(
                                        if (exportFormat == fmt) 2.dp else 0.dp,
                                        if (exportFormat == fmt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    ),
                                ) { Text(label) }
                            }
                        }
                        if (exportFormat == VaultFormats.FORMAT_VAULT) {
                            OutlinedTextField(
                                value = exportPassword,
                                onValueChange = onExportPasswordChange,
                                label = { Text(stringResource(R.string.vault_export_password_label)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                stringResource(R.string.vault_page_plaintext_env_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                onLaunchExport()
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

            run {
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
                        // 配置感知地取文案：lint 的 LocalContextGetResourceValueCall 认为在回调里
                        // 直接 context.getString 可能拿到过期资源（跟随语言/主题切换不及时）
                        val backupPasswordRequiredMsg =
                            stringResource(R.string.vault_backup_password_required)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    onLaunchBackupExport(backupPasswordRequiredMsg)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.vault_backup_button))
                            }
                            OutlinedButton(
                                onClick = onLaunchBackupRestore,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.vault_restore_button))
                            }
                        }
                        OutlinedTextField(
                            value = backupPassword,
                            onValueChange = onBackupPasswordChange,
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

            run {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.vault_danger_zone), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(
                            onClick = onShowClearDialog,
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
