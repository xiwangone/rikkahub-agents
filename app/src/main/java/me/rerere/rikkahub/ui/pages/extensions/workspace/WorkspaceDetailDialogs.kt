package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.MirrorSpeedResult
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.plus

/**
 * 聚焦时把字段滚进可视范围（键盘弹出后不被遮挡）。
 */
@Composable
private fun rememberBringIntoViewModifier(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return Modifier
        .bringIntoViewRequester(requester)
        .onFocusChanged { state ->
            if (state.isFocused) scope.launch { requester.bringIntoView() }
        }
}

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
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).imePadding()) {
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
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                    modifier = Modifier.fillMaxWidth().then(rememberBringIntoViewModifier()),
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

@Composable
internal fun WorkspaceRenameDialog(
    entry: WorkspaceFileEntry,
    takenNames: Set<String>,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(entry.name) }
    val trimmed = name.trim()
    val illegal = workspaceIllegalNameChars.any { trimmed.contains(it) }
    val conflict = trimmed in takenNames
    val unchanged = trimmed == entry.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.common_rename)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.workspace_detail_rename_new_name)) },
                    singleLine = true,
                    modifier = Modifier.then(rememberBringIntoViewModifier()),
                )
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    illegal ->
                        Text(
                            text =
                                stringResource(
                                    R.string.workspace_detail_rename_invalid,
                                    workspaceIllegalNameChars.joinToString(" "),
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                    conflict ->
                        Text(
                            text = stringResource(R.string.workspace_detail_rename_exists, trimmed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                    unchanged ->
                        Text(
                            text = stringResource(R.string.workspace_detail_rename_unchanged),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotBlank() && !illegal && !conflict && !unchanged,
                onClick = { onSubmit(trimmed) },
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
internal fun WorkspaceDirectoryPickerDialog(
    blockedPath: String?,
    startPath: String,
    onListDirectories: suspend (String) -> List<WorkspaceFileEntry>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(startPath) }
    var children by remember { mutableStateOf<List<WorkspaceFileEntry>?>(null) }
    LaunchedEffect(path) { children = onListDirectories(path) }
    val options =
        children?.filter { child ->
            blockedPath == null || (child.path != blockedPath && !child.path.startsWith("$blockedPath/"))
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.workspace_detail_move_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        enabled = path.isNotBlank(),
                        onClick = { path = path.substringBeforeLast('/', missingDelimiterValue = "") },
                    ) {
                        Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
                    }
                    Text(
                        text = path.ifBlank { "/" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    options == null -> CircularProgressIndicator(modifier = Modifier.size(22.dp))

                    options.isEmpty() ->
                        Text(
                            text = stringResource(R.string.workspace_detail_move_no_subdirectory),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                    else ->
                        LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                            items(options, key = { it.path }) { dir ->
                                TextButton(
                                    onClick = { path = dir.path },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Folder01,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = dir.name,
                                        modifier = Modifier.padding(start = 8.dp),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = options != null,
                onClick = { onConfirm(path) },
            ) {
                Text(stringResource(R.string.workspace_detail_move_here))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

