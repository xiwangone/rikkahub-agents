package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Lock
import me.rerere.hugeicons.stroke.Unlock
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.pages.backup.BackupVM

/**
 * 备份加密全局设置区：一行状态条（🔒 已开启/未开启），点击弹出设置对话框。
 * 位于备份页 TabRow 下方，所有通道（本地/WebDAV/S3）共用同一开关与口令。
 */
@Composable
fun BackupEncryptionSection(
    vm: BackupVM,
    modifier: Modifier = Modifier,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val enabled = settings.backupEncryptionEnabled
    val hasPassword = settings.backupEncryptionPasswordEnc.isNotBlank()
    var showDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { showDialog = true },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (enabled) HugeIcons.Lock else HugeIcons.Unlock,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.backup_page_encryption_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text =
                        if (enabled && hasPassword) {
                            stringResource(R.string.backup_page_encryption_locked)
                        } else if (enabled) {
                            stringResource(R.string.backup_page_encryption_not_remembered)
                        } else {
                            stringResource(R.string.backup_page_encryption_unlocked)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDialog) {
        BackupEncryptionDialog(
            vm = vm,
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun BackupEncryptionDialog(
    vm: BackupVM,
    onDismiss: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val enabled = settings.backupEncryptionEnabled
    val hasPassword = settings.backupEncryptionPasswordEnc.isNotBlank()
    var pwd by remember { mutableStateOf("") }
    var pwdConfirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        error = null
        if (pwd.length < 6) {
            error = stringResource(R.string.backup_page_encryption_password_short)
            return
        }
        if (pwd != pwdConfirm) {
            error = stringResource(R.string.backup_page_encryption_password_mismatch)
            return
        }
        vm.setBackupEncryptionPassword(pwd)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_encryption_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { vm.setBackupEncryptionEnabled(it) },
                    )
                    Column {
                        Text(stringResource(R.string.backup_page_encryption_enabled))
                        Text(
                            text =
                                if (hasPassword) {
                                    stringResource(R.string.backup_page_encryption_remembered)
                                } else {
                                    stringResource(R.string.backup_page_encryption_not_remembered)
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (enabled) {
                    Text(
                        text = stringResource(R.string.backup_page_encryption_enabled_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pwd,
                        onValueChange = { pwd = it },
                        label = { Text(stringResource(R.string.backup_page_encryption_password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pwdConfirm,
                        onValueChange = { pwdConfirm = it },
                        label = { Text(stringResource(R.string.backup_page_encryption_password_confirm)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (enabled) save() else {
                        vm.clearBackupEncryption()
                        onDismiss()
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (enabled) {
                            R.string.backup_page_encryption_password_set
                        } else {
                            R.string.backup_page_encryption_password_clear
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
