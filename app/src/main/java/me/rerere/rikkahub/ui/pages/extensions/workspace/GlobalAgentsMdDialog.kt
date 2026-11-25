package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalToaster
import java.io.File

private const val GLOBAL_AGENTS_FILE_NAME = "AGENTS.md"
private const val MAX_GLOBAL_AGENTS_BYTES = 64 * 1024

internal fun globalAgentsMdFile(context: Context): File = File(context.filesDir, GLOBAL_AGENTS_FILE_NAME)

internal fun formatAgentsByteSize(bytes: Int): String =
    if (bytes < 1024) "$bytes B" else String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)

@Composable
internal fun GlobalAgentsMdDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val file = remember(ctx) { globalAgentsMdFile(ctx) }
    var draft by remember(file) {
        mutableStateOf(runCatching { if (file.isFile) file.readText() else "" }.getOrDefault(""))
    }
    val byteCount = remember(draft) { draft.toByteArray(Charsets.UTF_8).size }
    val overLimit = byteCount > MAX_GLOBAL_AGENTS_BYTES

    val savedMsg = stringResource(R.string.workspace_global_agents_saved)
    val tooLargeMsg = stringResource(R.string.workspace_global_agents_too_large)

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()?.let { imported ->
                draft = imported
            }
        }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(draft.toByteArray(Charsets.UTF_8))
                }
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_global_agents_title)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.workspace_global_agents_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) }) {
                            Text(stringResource(R.string.workspace_global_agents_import))
                        }
                        TextButton(onClick = { exportLauncher.launch(GLOBAL_AGENTS_FILE_NAME) }) {
                            Text(stringResource(R.string.workspace_global_agents_export))
                        }
                    }
                    Text(
                        text = "${formatAgentsByteSize(byteCount)} / 64 KB",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    maxLines = 12,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (draft.isNotEmpty()) {
                    TextButton(onClick = { draft = "" }) {
                        Text(stringResource(R.string.mcp_page_clear))
                    }
                }
                TextButton(
                    onClick = {
                        if (overLimit) {
                            toaster.show(tooLargeMsg)
                            return@TextButton
                        }
                        runCatching {
                            if (draft.isBlank()) {
                                if (file.exists()) file.delete()
                            } else {
                                file.writeText(draft)
                            }
                        }
                        toaster.show(savedMsg)
                        onSaved()
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
