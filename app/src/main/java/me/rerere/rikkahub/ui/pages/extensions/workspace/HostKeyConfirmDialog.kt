package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.workspace.ssh.SshHostKey

/**
 * 主机密钥确认弹窗：首次连接（[expected] 为 null）或已信任密钥变更时弹出，
 * 展示对方出示的密钥类型与指纹；用户确认后调用方再把密钥记入信任存储并重连。
 */
@Composable
fun HostKeyConfirmDialog(
    address: String,
    actual: SshHostKey,
    expected: SshHostKey?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (expected == null) R.string.workspace_ssh_host_key_unknown_title
                    else R.string.workspace_ssh_host_key_changed_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        if (expected == null) R.string.workspace_ssh_host_key_unknown_desc
                        else R.string.workspace_ssh_host_key_changed_desc,
                        address,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (expected != null) {
                    Text(
                        text = stringResource(R.string.workspace_ssh_host_key_previous, expected.type, expected.fingerprint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.workspace_ssh_host_key_received, actual.type, actual.fingerprint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.workspace_ssh_host_key_trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
