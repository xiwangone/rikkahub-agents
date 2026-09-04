package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import me.rerere.rikkahub.R

/**
 * 恢复加密备份时的口令输入框。确认后回调 [onConfirm]，由调用方执行带口令恢复。
 */
@Composable
fun PasswordPromptDialog(
    onDismiss: () -> Unit,
    onConfirm: (password: String) -> Unit,
    errorMessage: String? = null,
) {
    var pwd by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_encryption_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.backup_page_encryption_needs_password),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text(stringResource(R.string.backup_page_encryption_password_placeholder)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pwd) },
                enabled = pwd.isNotBlank(),
            ) {
                Text(stringResource(R.string.backup_page_encryption_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
