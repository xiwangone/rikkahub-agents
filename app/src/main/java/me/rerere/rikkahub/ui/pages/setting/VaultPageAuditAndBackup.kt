package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DatabaseRestore
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.vault.AuditAnomaly
import me.rerere.rikkahub.data.vault.AuditAnomalyDetector
import me.rerere.rikkahub.data.vault.VaultBiometric
import me.rerere.rikkahub.data.vault.VaultFormats
import me.rerere.rikkahub.data.vault.VaultPreferences
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.setting.SettingTextField
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject

/**
 * 安全凭证库：审计日志、导入、导出、整库备份与危险操作分组（Material 3 Expressive 样式）。
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val vaultPreferences: VaultPreferences = koinInject()
    val biometricBuffer: BiometricResultBuffer = koinInject()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 1. 审计日志
        CardGroup(
            title = { Text(stringResource(R.string.vault_audit_title)) },
        ) {
            formItem(
                leadingContent = { CardGroupIcon(HugeIcons.Shield01) },
                headlineContent = { Text(stringResource(R.string.vault_audit_title)) },
                supportingContent = { Text(stringResource(R.string.vault_audit_desc, shownAudit.size)) },
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        0 to stringResource(R.string.vault_audit_time_all),
                        1 to stringResource(R.string.vault_audit_time_1d),
                        7 to stringResource(R.string.vault_audit_time_7d),
                    ).forEach { (days, label) ->
                        FilterChip(
                            selected = auditTimeDays == days,
                            onClick = { onAuditTimeDaysChange(days) },
                            label = { Text(label) },
                        )
                    }
                    auditFilter?.let { f ->
                        FilterChip(
                            selected = true,
                            onClick = { onAuditFilterChange(null) },
                            label = { Text("✕ $f") },
                        )
                    }
                    auditActionFilter?.let { a ->
                        FilterChip(
                            selected = true,
                            onClick = { onAuditActionFilterChange(null) },
                            label = { Text("✕ $a") },
                        )
                    }
                }

                auditAnomalies.take(3).forEach { anomaly ->
                    Text(
                        text =
                            stringResource(
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

                if (auditGroups.isNotEmpty()) {
                    HorizontalDivider()
                    auditGroups.take(30).forEach { group ->
                        val log = group.first()
                        val needsAuth = actionNeedsAuth(log.action)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = log.credentialName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier =
                                        Modifier.clickable {
                                            onAuditFilterChange(log.credentialName.takeIf { it != auditFilter })
                                        },
                                )
                                Text(
                                    text = "${if (needsAuth) "⚠ " else ""}${log.caller} · ${log.action}" + auditCountSuffix(log.count),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (needsAuth) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier =
                                        Modifier.clickable {
                                            onAuditActionFilterChange(log.action.takeIf { it != auditActionFilter })
                                        },
                                )
                                group.drop(1).forEach { sub ->
                                    Text(
                                        text = "↳ ${sub.caller} · ${sub.action}" + auditCountSuffix(sub.count),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp),
                                    )
                                }
                                val auditConversationId = log.conversationId
                                if (auditConversationId != null) {
                                    Text(
                                        text = "#$auditConversationId" + (log.modelId?.let { "\n· $it" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier =
                                            Modifier.clickable {
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
                                text = formatTime(log.lastTsMs ?: log.tsMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onExportAudit("json") },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.vault_audit_export_json)) }
                    OutlinedButton(
                        onClick = { onExportAudit("csv") },
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.vault_audit_rollup_minutes), style = MaterialTheme.typography.labelSmall)
                    Select(
                        options = listOf(1, 5, 10, 30, 60),
                        selectedOption = auditRollupMinutes,
                        onOptionSelected = { m -> scope.launch { vaultPreferences.setAuditRollupMinutes(m) } },
                        optionToString = { it.toString() },
                        modifier = Modifier.weight(1f),
                    )
                }
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
                            val ok =
                                VaultBiometric.authenticate(
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

        // 2. 导入与导出
        CardGroup(
            title = { Text(stringResource(R.string.vault_import_title) + " / " + stringResource(R.string.vault_export_title)) },
        ) {
            formItem(
                leadingContent = { CardGroupIcon(HugeIcons.Download04) },
                headlineContent = { Text(stringResource(R.string.vault_import_title)) },
                supportingContent = { Text(stringResource(R.string.vault_import_desc)) },
            ) {
                SettingTextField(
                    value = importPassword,
                    onValueChange = onImportPasswordChange,
                    label = { Text(stringResource(R.string.vault_export_password_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = onLaunchImport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = HugeIcons.Download04,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.vault_import_button))
                }
                importResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            formItem(
                leadingContent = { CardGroupIcon(HugeIcons.Upload02) },
                headlineContent = { Text(stringResource(R.string.vault_export_title)) },
                supportingContent = { Text(stringResource(R.string.vault_export_desc)) },
            ) {
                Text(stringResource(R.string.vault_page_export_format), style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        VaultFormats.FORMAT_VAULT to stringResource(R.string.vault_format_vault_desc),
                        VaultFormats.FORMAT_CSV to stringResource(R.string.vault_format_csv_desc),
                        VaultFormats.FORMAT_BITWARDEN to stringResource(R.string.vault_format_bitwarden_desc),
                        VaultFormats.FORMAT_LOADCREDS to stringResource(R.string.vault_format_loadcreds_desc),
                    ).forEach { (fmt, label) ->
                        FilterChip(
                            selected = exportFormat == fmt,
                            onClick = { onExportFormatSelect(fmt) },
                            label = { Text(label) },
                        )
                    }
                }
                if (exportFormat == VaultFormats.FORMAT_VAULT) {
                    SettingTextField(
                        value = exportPassword,
                        onValueChange = onExportPasswordChange,
                        label = { Text(stringResource(R.string.vault_export_password_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
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
                    onClick = onLaunchExport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = HugeIcons.Upload02,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.vault_export_button))
                }
                exportResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 3. 备份与恢复
        CardGroup(
            title = { Text(stringResource(R.string.vault_backup_title)) },
        ) {
            val backupPasswordRequiredMsg = stringResource(R.string.vault_backup_password_required)
            formItem(
                leadingContent = { CardGroupIcon(HugeIcons.DatabaseRestore) },
                headlineContent = { Text(stringResource(R.string.vault_backup_title)) },
                supportingContent = { Text(stringResource(R.string.vault_backup_desc)) },
            ) {
                SettingTextField(
                    value = backupPassword,
                    onValueChange = onBackupPasswordChange,
                    label = { Text(stringResource(R.string.vault_backup_password_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onLaunchBackupExport(backupPasswordRequiredMsg) },
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
                backupResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 4. 危险区域
        CardGroup(
            title = { Text(stringResource(R.string.vault_danger_zone)) },
        ) {
            item(
                onClick = onShowClearDialog,
                leadingContent = {
                    CardGroupIcon(
                        icon = HugeIcons.Delete01,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                },
                headlineContent = {
                    Text(
                        stringResource(R.string.vault_clear_all),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}
