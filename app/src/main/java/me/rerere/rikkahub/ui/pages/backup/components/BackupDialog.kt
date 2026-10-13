package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import kotlin.system.exitProcess

@Composable
fun BackupDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_restart_app)) },
        text = { Text(stringResource(R.string.backup_page_restart_desc)) },
        confirmButton = {
            Button(
                onClick = {
                    exitProcess(0)
                },
            ) {
                Text(stringResource(R.string.backup_page_restart_app))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
