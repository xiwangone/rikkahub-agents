package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.MirrorSpeedResult
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.RadioButton
import me.rerere.workspace.WorkspaceMirrors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.plus


@Composable
internal fun MirrorPickerDialog(
    pick: MirrorPick?,
    mirrors: WorkspaceMirrors,
    speedResults: Map<String, MirrorSpeedResult>,
    speedTesting: Boolean,
    onDismiss: () -> Unit,
    onSpeedTest: () -> Unit,
    onSelect: (String) -> Unit,
) {
    if (pick == null) return
    val presets = pick.presets()
    val current =
        when (pick) {
            MirrorPick.APK -> mirrors.apk
            MirrorPick.APT -> mirrors.apt
            MirrorPick.PIP -> mirrors.pip
            MirrorPick.NPM -> mirrors.npm
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_mirrors)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val fastestMs = speedResults.values.mapNotNull { it.latencyMs }.minOrNull()
                presets.forEach { preset ->
                    val result = speedResults[preset.id]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = preset.url == current || (current.isBlank() && preset == presets.first()),
                            onClick = { onSelect(preset.url) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                preset.region,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            speedTesting && result == null ->
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )

                            result != null && result.latencyMs != null ->
                                Text(
                                    text = "${result.latencyMs} ms",
                                    style = MaterialTheme.typography.labelMedium,
                                    color =
                                        if (result.latencyMs == fastestMs) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )

                            result?.failed == true ->
                                Text(
                                    text = stringResource(R.string.workspace_detail_mirror_speed_failed),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onSpeedTest, enabled = !speedTesting) {
                if (speedTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.workspace_detail_mirror_speed_testing))
                } else {
                    Text(stringResource(R.string.workspace_detail_mirror_speed_test))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun TagEditDialog(
    initial: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var input by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        AppLog.i("WorkspaceTags", "dialog open: initial=$initial")
    }
    fun addCurrent() {
        val trimmed = input.trim()
        if (trimmed.isNotEmpty() && trimmed !in draft) draft = draft + trimmed
        input = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_tags)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    draft.fastForEach { tag ->
                        Surface(shape = MaterialTheme.shapes.small, tonalElevation = 2.dp) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(tag, style = MaterialTheme.typography.bodySmall)
                                Icon(
                                    imageVector = HugeIcons.Cancel01,
                                    contentDescription = null,
                                    modifier =
                                        Modifier
                                            .size(14.dp)
                                            .clickable { draft = draft.filter { it != tag } },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.workspace_detail_tags_hint)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { addCurrent() }) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = stringResource(R.string.add),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions =
                        KeyboardActions(
                            onDone = { addCurrent() },
                        ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                addCurrent()
                onSave(draft.distinct())
            }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
