package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Share08
import me.rerere.hugeicons.stroke.Sorting01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
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
    onClearSelection: () -> Unit = {},
    onExportSelection: () -> Unit,
    clipboard: WorkspaceClipboard?,
    onPaste: (WorkspaceClipboard) -> Unit,
    onClearClipboard: () -> Unit,
) {
    val currentKeys = remember(state.area, state.entries) {
        state.entries.mapTo(mutableSetOf()) { "${state.area.name}:${it.path}" }
    }
    val selectedCount = selectedPaths.intersect(currentKeys).size
    val selecting = selectedCount > 0
    var sortByKey by rememberSaveable { mutableStateOf(WorkspaceFileSortBy.NAME.name) }
    var sortAscending by rememberSaveable { mutableStateOf(true) }
    val sortBy = WorkspaceFileSortBy.fromName(sortByKey)
    val sortedEntries =
        remember(state.entries, sortBy, sortAscending) {
            sortWorkspaceEntries(state.entries, sortBy, sortAscending)
        }
    val showLoading = rememberDelayedVisibility(state.loading)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                contentPadding +
                    PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = if (selecting) 128.dp else 16.dp,
                    ),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WorkspaceAreaSelector(
                        selected = state.area,
                        onSelected = onSelectArea,
                    )
                    WorkspacePathBar(
                        path = state.path,
                        canGoUp = state.path.isNotBlank(),
                        onGoUp = onGoUp,
                    )
                    clipboard?.let { clip ->
                        WorkspaceClipboardBar(
                            clip = clip,
                            targetPath = state.path,
                            onPaste = { onPaste(clip) },
                            onClear = onClearClipboard,
                        )
                    }
                    if (state.entries.isNotEmpty()) {
                        WorkspaceSortBar(
                            sortBy = sortBy,
                            ascending = sortAscending,
                            selectedCount = selectedCount,
                            totalCount = state.entries.size,
                            onSortByChange = { sortByKey = it.name },
                            onToggleAscending = { sortAscending = !sortAscending },
                            onSelectAll = onSelectAll,
                        )
                    }
                    if (showLoading && state.entries.isNotEmpty()) {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    state.error?.let { error ->
                        ErrorCard(error)
                    }
                }
            }

            if (showLoading && state.entries.isEmpty()) {
                item {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }
                }
            }

            if (!state.loading && state.entries.isEmpty() && state.error == null) {
                item {
                    ExtensionEmptyState(
                        message = stringResource(R.string.workspace_detail_empty_directory),
                        icon = HugeIcons.Folder01,
                    )
                }
            }

            itemsIndexed(
                sortedEntries,
                key = { _, entry -> "${state.area.name}:${entry.path}" },
            ) { index, entry ->
                WorkspaceFileCard(
                    entry = entry,
                    area = state.area,
                    shape = cardGroupItemShape(index, sortedEntries.size),
                    modifier = Modifier.padding(bottom = if (index == sortedEntries.lastIndex) 0.dp else 2.dp),
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
                    selectionMode = selecting,
                    onToggleSelection = { onToggleSelection(entry) },
                )
            }
        }

        AnimatedVisibility(
            visible = selecting,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -ScreenOffset),
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            HorizontalFloatingToolbar(
                expanded = true,
                colors = CustomColors.floatingToolbarColors,
                leadingContent = {
                    IconButton(onClick = onClearSelection) {
                        Icon(
                            HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.workspace_selection_cancel),
                        )
                    }
                },
                trailingContent = {
                    IconButton(onClick = onSelectAll) {
                        Icon(
                            HugeIcons.CheckList,
                            contentDescription = stringResource(R.string.workspace_selection_all),
                        )
                    }
                },
            ) {
                Button(
                    onClick = onExportSelection,
                    enabled = selectedCount > 0,
                ) {
                    Icon(
                        imageVector = HugeIcons.Download04,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("${stringResource(R.string.workspace_selection_export_zip)} ($selectedCount)")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
) {
    ConnectedChoiceButtons(
        options = WorkspaceStorageArea.entries,
        selected = selected,
        onSelect = onSelected,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) { area, isSelected ->
        ConnectedChoiceLabel(
            text =
                when (area) {
                    WorkspaceStorageArea.FILES -> stringResource(R.string.workspace_detail_area_files)
                    WorkspaceStorageArea.LINUX -> stringResource(R.string.workspace_detail_area_rootfs)
                },
            selected = isSelected,
            icon = HugeIcons.Tick01,
        )
    }
}

@Composable
private fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(
                enabled = canGoUp,
                onClick = onGoUp,
            ) {
                Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
            }
            Text(
                text = path.ifBlank { "/" },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
    }
}

@Composable
private fun WorkspaceClipboardBar(
    clip: WorkspaceClipboard,
    targetPath: String,
    onPaste: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CardGroupIcon(icon = HugeIcons.Clipboard)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.workspace_detail_clipboard_item, clip.name),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
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
private fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    area: WorkspaceStorageArea,
    shape: Shape,
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
    modifier: Modifier = Modifier,
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
            value =
                try {
                    onResolveImage()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
        }
    }
    val containerColor by animateColorAsState(
        targetValue =
            if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                CustomColors.listItemColors.containerColor
            },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelection() else onOpen() },
                    onLongClick = onToggleSelection,
                ),
        shape = shape,
        color = containerColor,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                isImage -> {
                    val context = LocalContext.current
                    val imageRequest =
                        remember(imageFile, entry.updatedAt, entry.sizeBytes) {
                            imageFile?.let {
                                ImageRequest.Builder(context)
                                    .data(it)
                                    .memoryCacheKey("workspace:${it.absolutePath}:${entry.updatedAt}:${entry.sizeBytes}")
                                    .build()
                            }
                        }
                    Box(
                        modifier =
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = HugeIcons.File02,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
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
                }

                entry.isDirectory ->
                    CardGroupIcon(
                        icon = HugeIcons.Folder01,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )

                else ->
                    CardGroupIcon(
                        icon = HugeIcons.File02,
                        containerColor =
                            if (selected) {
                                MaterialTheme.colorScheme.surfaceBright
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                    )
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${entry.path} · ${fileMetaLine(entry)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selectionMode) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
                }
            } else {
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
                            onClick = {
                                menuExpanded = false
                                onCopyPath()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_rename)) },
                            leadingIcon = { Icon(HugeIcons.Edit02, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_detail_action_move)) },
                            leadingIcon = { Icon(HugeIcons.Folder01, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onMove()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_detail_action_copy)) },
                            leadingIcon = { Icon(HugeIcons.Clipboard, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onCopy()
                            },
                        )
                        if (entry.isDirectory) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_detail_export_zip)) },
                                leadingIcon = { Icon(HugeIcons.Download04, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onExportZip()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (entry.isDirectory) {
                                            R.string.workspace_detail_export_folder
                                        } else {
                                            R.string.common_export
                                        },
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
}

@Composable
private fun WorkspaceSortBar(
    sortBy: WorkspaceFileSortBy,
    ascending: Boolean,
    selectedCount: Int,
    totalCount: Int,
    onSortByChange: (WorkspaceFileSortBy) -> Unit,
    onToggleAscending: () -> Unit,
    onSelectAll: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            TextButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = HugeIcons.Sorting01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${stringResource(R.string.workspace_detail_sort_by)} · ${workspaceSortLabel(sortBy)}",
                    style = MaterialTheme.typography.labelLarge,
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
                text =
                    stringResource(
                        if (ascending) {
                            R.string.workspace_detail_sort_ascending
                        } else {
                            R.string.workspace_detail_sort_descending
                        },
                    ),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSelectAll) {
            Icon(
                imageVector = HugeIcons.CheckList,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text =
                    if (selectedCount == totalCount) {
                        stringResource(R.string.workspace_selection_cancel)
                    } else {
                        stringResource(R.string.workspace_selection_all)
                    },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
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
        fun fromName(name: String): WorkspaceFileSortBy =
            WorkspaceFileSortBy.entries.firstOrNull { it.name == name } ?: WorkspaceFileSortBy.NAME
    }
}

private fun sortWorkspaceEntries(
    entries: List<WorkspaceFileEntry>,
    sortBy: WorkspaceFileSortBy,
    ascending: Boolean,
): List<WorkspaceFileEntry> {
    if (entries.size < 2) return entries
    val nameThen = compareBy<WorkspaceFileEntry> { it.name.lowercase() }
    val primary: Comparator<WorkspaceFileEntry> =
        when (sortBy) {
            WorkspaceFileSortBy.NAME -> nameThen
            WorkspaceFileSortBy.MODIFIED -> compareBy<WorkspaceFileEntry>({ it.updatedAt }).then(nameThen)
            WorkspaceFileSortBy.SIZE -> compareBy<WorkspaceFileEntry>({ it.sizeBytes }).then(nameThen)
        }
    val ordered = if (ascending) primary else primary.reversed()
    return entries.sortedWith(compareBy<WorkspaceFileEntry>({ !it.isDirectory }).then(ordered))
}

@Composable
private fun fileMetaLine(entry: WorkspaceFileEntry): String {
    val modified =
        formatFileUpdatedAt(
            updatedAt = entry.updatedAt,
            todayLabel = stringResource(R.string.workspace_detail_date_today),
            yesterdayLabel = stringResource(R.string.workspace_detail_date_yesterday),
        )
    return if (entry.isDirectory) modified else "$modified · ${entry.sizeBytes.fileSizeToString()}"
}

private fun formatFileUpdatedAt(
    updatedAt: Long,
    todayLabel: String,
    yesterdayLabel: String,
): String {
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
        else ->
            DateTimeFormatter
                .ofPattern(if (date.year == today.year) "MM-dd" else "yyyy-MM-dd", locale)
                .format(date)
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Alert01,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun rememberDelayedVisibility(
    loading: Boolean,
    delayMillis: Long = 300,
): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            delay(delayMillis)
            visible = true
        } else {
            visible = false
        }
    }
    return visible
}
