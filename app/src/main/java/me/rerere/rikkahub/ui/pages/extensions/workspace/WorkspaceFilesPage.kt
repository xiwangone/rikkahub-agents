package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.material3.RadioButton
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.plus
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale


@Composable
internal fun WorkspaceFilesPage(
    state: WorkspaceDetailState,
    contentPadding: PaddingValues,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onGoUp: () -> Unit,
    onResolveImage: suspend (WorkspaceFileEntry, WorkspaceStorageArea) -> File?,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onCopy: (WorkspaceFileEntry) -> Unit,
    onMove: (WorkspaceFileEntry) -> Unit,
    onRename: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onExportZip: (WorkspaceFileEntry) -> Unit,
    onCopyPath: (WorkspaceFileEntry, WorkspaceStorageArea) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
    selectedPaths: Set<String>,
    onToggleSelection: (WorkspaceFileEntry) -> Unit,
    onSelectAll: () -> Unit,
    onExportSelection: () -> Unit,
    clipboard: WorkspaceClipboard?,
    onPaste: (WorkspaceClipboard) -> Unit,
    onClearClipboard: () -> Unit,
) {
    val currentKeys = state.entries.mapTo(mutableSetOf()) { "${state.area.name}:${it.path}" }
    val selectedCount = selectedPaths.intersect(currentKeys).size
    // 排序偏好按本仓同类页做法存 rememberSaveable（枚举名存字符串，避免 Bundle 直接存枚举）
    var sortByKey by rememberSaveable { mutableStateOf(WorkspaceFileSortBy.NAME.name) }
    var sortAscending by rememberSaveable { mutableStateOf(true) }
    val sortBy = WorkspaceFileSortBy.fromName(sortByKey)
    val sortedEntries = remember(state.entries, sortBy, sortAscending) {
        sortWorkspaceEntries(state.entries, sortBy, sortAscending)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            WorkspaceAreaSelector(
                selected = state.area,
                onSelected = onSelectArea,
            )
        }

        item {
            WorkspacePathBar(
                path = state.path,
                canGoUp = state.path.isNotBlank(),
                onGoUp = onGoUp,
            )
        }

        clipboard?.let { clip ->
            item {
                WorkspaceClipboardBar(
                    clip = clip,
                    targetPath = state.path,
                    onPaste = { onPaste(clip) },
                    onClear = onClearClipboard,
                )
            }
        }

        if (state.entries.isNotEmpty()) {
            item {
                WorkspaceSortBar(
                    sortBy = sortBy,
                    ascending = sortAscending,
                    onSortByChange = { sortByKey = it.name },
                    onToggleAscending = { sortAscending = !sortAscending },
                )
            }
        }

        state.error?.let { error ->
            item {
                ErrorCard(error)
            }
        }

        if (state.entries.isNotEmpty()) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onSelectAll) {
                        Text(if (selectedCount == state.entries.size) stringResource(R.string.workspace_selection_cancel) else stringResource(R.string.workspace_selection_all))
                    }
                    if (selectedCount > 0) {
                        Text("$selectedCount/${state.entries.size}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onExportSelection) { Text(stringResource(R.string.workspace_selection_export_zip)) }
                    }
                }
            }
        }

        if (!state.loading && state.entries.isEmpty() && state.error == null) {
            item {
                EmptyDirectoryState()
            }
        }

        items(sortedEntries, key = { "${state.area.name}:${it.path}" }) { entry ->
            WorkspaceFileCard(
                entry = entry,
                area = state.area,
                onResolveImage = { onResolveImage(entry, state.area) },
                onOpen = { onOpen(entry) },
                onDelete = { onDelete(entry) },
                onCopy = { onCopy(entry) },
                onMove = { onMove(entry) },
                onRename = { onRename(entry) },
                onExport = { onExport(entry) },
                onExportZip = { onExportZip(entry) },
                onCopyPath = { onCopyPath(entry, state.area) },
                onShare = { onShare(entry) },
                selected = "${state.area.name}:${entry.path}" in selectedPaths,
                selectionMode = selectedCount > 0,
                onToggleSelection = { onToggleSelection(entry) },
            )
        }
    }
}

@Composable
private fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
) {
    val areas =
        listOf(
            WorkspaceStorageArea.FILES to stringResource(R.string.workspace_detail_area_files),
            WorkspaceStorageArea.LINUX to stringResource(R.string.workspace_detail_area_rootfs),
        )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        areas.forEachIndexed { index, (area, label) ->
            SegmentedButton(
                selected = selected == area,
                onClick = { onSelected(area) },
                shape = SegmentedButtonDefaults.itemShape(index, areas.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            enabled = canGoUp,
            onClick = onGoUp,
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
}

@Composable
private fun WorkspaceClipboardBar(
    clip: WorkspaceClipboard,
    targetPath: String,
    onPaste: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Clipboard,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.workspace_detail_clipboard_item, clip.name),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_clipboard_target, targetPath.ifBlank { "/" }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.common_cancel))
            }
            Button(onClick = onPaste) {
                Text(stringResource(R.string.workspace_detail_paste))
            }
        }
    }
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.workspace_detail_rename_new_name)) },
                    singleLine = true,
                )
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    illegal -> Text(
                        text = stringResource(
                            R.string.workspace_detail_rename_invalid,
                            workspaceIllegalNameChars.joinToString(" "),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    conflict -> Text(
                        text = stringResource(R.string.workspace_detail_rename_exists, trimmed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    unchanged -> Text(
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

/**
 * 逐级浏览的目录选择器（移动目标用）。
 *
 * 不用现成的递归 tree 接口：那份只服务 LINUX 区（rootfsTree），且受条目上限截断；
 * 这里要的是「当前区、当前层的子目录」，逐层列目录既准又省。
 * [blockedPath] 连同其子树一起隐藏 —— 目录不能移进自己里面。
 */
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
    val options = children?.filter { child ->
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

                    options.isEmpty() -> Text(
                        text = stringResource(R.string.workspace_detail_move_no_subdirectory),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
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
                // 加载中不允许确认：此时还不知道当前目录是否就是被禁用项
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

@Composable
private fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    area: WorkspaceStorageArea,
    onResolveImage: suspend () -> File?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onExportZip: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isImage = !entry.isDirectory && entry.detectFileType() == WorkspaceFileType.IMAGE
    val imageFile by produceState<File?>(
        initialValue = null,
        key1 = if (isImage) area else null,
        key2 = if (isImage) entry.path else null,
        key3 = if (isImage) "${entry.updatedAt}:${entry.sizeBytes}" else null,
    ) {
        if (isImage) {
            value = try {
                onResolveImage()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { if (selectionMode) onToggleSelection() else onOpen() }, onLongClick = onToggleSelection),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode || selected) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
            }
            if (isImage) {
                val context = LocalContext.current
                val imageRequest = remember(imageFile, entry.updatedAt, entry.sizeBytes) {
                    imageFile?.let {
                        ImageRequest.Builder(context)
                            .data(it)
                            .memoryCacheKey("workspace:${it.absolutePath}:${entry.updatedAt}:${entry.sizeBytes}")
                            .build()
                    }
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.File02,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    imageRequest?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (entry.isDirectory) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = fileMetaLine(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(HugeIcons.MoreVertical, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.workspace_detail_copy_path)) },
                        leadingIcon = { Icon(HugeIcons.Copy01, contentDescription = null) },
                        onClick = { menuExpanded = false; onCopyPath() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_rename)) },
                        leadingIcon = { Icon(HugeIcons.Edit02, contentDescription = null) },
                        onClick = { menuExpanded = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.workspace_detail_action_move)) },
                        leadingIcon = { Icon(HugeIcons.Folder01, contentDescription = null) },
                        onClick = { menuExpanded = false; onMove() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.workspace_detail_action_copy)) },
                        leadingIcon = { Icon(HugeIcons.Clipboard, contentDescription = null) },
                        onClick = { menuExpanded = false; onCopy() },
                    )
                    if (entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_detail_export_zip)) },
                            leadingIcon = { Icon(HugeIcons.FileImport, contentDescription = null) },
                            onClick = { menuExpanded = false; onExportZip() },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (entry.isDirectory) R.string.workspace_detail_export_folder
                                    else R.string.common_export,
                                ),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.FileImport,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onExport()
                        },
                    )
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_share)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Share08,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onShare()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.common_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceSortBar(
    sortBy: WorkspaceFileSortBy,
    ascending: Boolean,
    onSortByChange: (WorkspaceFileSortBy) -> Unit,
    onToggleAscending: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            TextButton(onClick = { menuExpanded = true }) {
                Text(
                    text = "${stringResource(R.string.workspace_detail_sort_by)} · ${workspaceSortLabel(sortBy)}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                WorkspaceFileSortBy.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(workspaceSortLabel(option)) },
                        leadingIcon = { RadioButton(selected = option == sortBy, onClick = null) },
                        onClick = {
                            menuExpanded = false
                            onSortByChange(option)
                        },
                    )
                }
            }
        }
        TextButton(onClick = onToggleAscending) {
            Text(
                text = stringResource(
                    if (ascending) R.string.workspace_detail_sort_ascending else R.string.workspace_detail_sort_descending,
                ),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun workspaceSortLabel(sortBy: WorkspaceFileSortBy): String =
    stringResource(
        when (sortBy) {
            WorkspaceFileSortBy.NAME -> R.string.workspace_detail_sort_name
            WorkspaceFileSortBy.MODIFIED -> R.string.workspace_detail_sort_modified
            WorkspaceFileSortBy.SIZE -> R.string.workspace_detail_sort_size
        },
    )

private enum class WorkspaceFileSortBy {
    NAME,
    MODIFIED,
    SIZE,
    ;

    companion object {
        /** 存档恢复时枚举可能已改名：查不到就回落名称排序，不要抛异常。 */
        fun fromName(name: String): WorkspaceFileSortBy =
            WorkspaceFileSortBy.entries.firstOrNull { it.name == name } ?: WorkspaceFileSortBy.NAME
    }
}

/** 排序：目录恒在前（升降序只翻转组内顺序），同维度再按名称兜底。 */
private fun sortWorkspaceEntries(
    entries: List<WorkspaceFileEntry>,
    sortBy: WorkspaceFileSortBy,
    ascending: Boolean,
): List<WorkspaceFileEntry> {
    if (entries.size < 2) return entries
    val nameThen = compareBy<WorkspaceFileEntry> { it.name.lowercase() }
    val primary: Comparator<WorkspaceFileEntry> = when (sortBy) {
        WorkspaceFileSortBy.NAME -> nameThen
        WorkspaceFileSortBy.MODIFIED -> compareBy<WorkspaceFileEntry>({ it.updatedAt }).then(nameThen)
        WorkspaceFileSortBy.SIZE -> compareBy<WorkspaceFileEntry>({ it.sizeBytes }).then(nameThen)
    }
    val ordered = if (ascending) primary else primary.reversed()
    return entries.sortedWith(compareBy<WorkspaceFileEntry>({ !it.isDirectory }).then(ordered))
}

@Composable
private fun fileMetaLine(entry: WorkspaceFileEntry): String {
    val modified = formatFileUpdatedAt(
        updatedAt = entry.updatedAt,
        todayLabel = stringResource(R.string.workspace_detail_date_today),
        yesterdayLabel = stringResource(R.string.workspace_detail_date_yesterday),
    )
    return if (entry.isDirectory) modified else "$modified · ${entry.sizeBytes.fileSizeToString()}"
}

/** 文件修改时间四档显示：今天 HH:mm / 昨天 HH:mm / MM-dd / yyyy-MM-dd。 */
private fun formatFileUpdatedAt(
    updatedAt: Long,
    todayLabel: String,
    yesterdayLabel: String,
): String {
    // File.lastModified() 取不到时间时返回 0，此时不显示日期
    if (updatedAt <= 0L) return "-"
    val zone = ZoneId.systemDefault()
    val timestamp = Instant.ofEpochMilli(updatedAt).atZone(zone)
    val date = timestamp.toLocalDate()
    val today = LocalDate.now(zone)
    val locale = Locale.getDefault()
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
    return when (date) {
        today -> "$todayLabel ${timeFormatter.format(timestamp)}"
        today.minusDays(1) -> "$yesterdayLabel ${timeFormatter.format(timestamp)}"
        else -> DateTimeFormatter
            .ofPattern(if (date.year == today.year) "MM-dd" else "yyyy-MM-dd", locale)
            .format(date)
    }
}

@Composable
private fun EmptyDirectoryState() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Folder01,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_detail_empty_directory),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

