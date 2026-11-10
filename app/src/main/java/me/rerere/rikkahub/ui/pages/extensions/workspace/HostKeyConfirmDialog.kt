package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
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
                    FingerprintRow(
                        text = stringResource(R.string.workspace_ssh_host_key_previous, expected.type, expected.fingerprint),
                        copyText = "${expected.type} ${expected.fingerprint}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                FingerprintRow(
                    text = stringResource(R.string.workspace_ssh_host_key_received, actual.type, actual.fingerprint),
                    copyText = "${actual.type} ${actual.fingerprint}",
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

/**
 * 指纹行：文本可选中，右侧复制按钮把“算法 指纹”拷出来，
 * 用户可以拿去与服务器端的可信值逐字核对，而不是只靠肉眼比对。
 */
@Composable
private fun FingerprintRow(text: String, copyText: String, color: Color = Color.Unspecified) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SelectionContainer(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
        IconButton(onClick = { clipboard.setText(AnnotatedString(copyText)) }) {
            Icon(
                imageVector = HugeIcons.Copy01,
                contentDescription = stringResource(R.string.workspace_ssh_host_key_copy),
            )
        }
    }
}
