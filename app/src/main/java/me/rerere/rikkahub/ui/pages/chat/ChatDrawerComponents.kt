package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Sorting01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.Navigator
import kotlin.uuid.Uuid

@Composable
internal fun DrawerActions(navController: Navigator) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DrawerEntry(
            icon = HugeIcons.TransactionHistory,
            text = stringResource(R.string.chat_page_history),
            onClick = { navController.navigate(Screen.History) },
            trailingIcon = HugeIcons.Search01,
            trailingDescription = stringResource(R.string.chat_page_search_chats),
            onTrailingClick = { navController.navigate(Screen.MessageSearch) },
        )

        DrawerEntry(
            icon = HugeIcons.Video01,
            text = stringResource(R.string.media_creation_title),
            onClick = { navController.navigate(Screen.MediaCreationSessions) },
        )

        DrawerEntry(
            icon = HugeIcons.LanguageCircle,
            text = stringResource(R.string.chat_page_menu_ai_translator),
            onClick = { navController.navigate(Screen.Translator) },
        )
    }
}

@Composable
private fun DrawerEntry(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    trailingIcon: ImageVector? = null,
    trailingDescription: String? = null,
    onTrailingClick: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (trailingIcon != null) {
                Box(
                    modifier =
                        Modifier
                            .padding(end = 4.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(
                                role = Role.Button,
                                onClick = onTrailingClick,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = trailingIcon,
                        contentDescription = trailingDescription,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DrawerAction(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Tooltip(
            tooltip = {
                label()
            },
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(10.dp)
                        .size(20.dp),
            ) {
                icon()
            }
        }
    }
}

@Composable
internal fun FolderBar(
    folders: List<Folder>,
    selectedFolderId: Uuid?,
    onSelect: (Uuid?) -> Unit,
    onCreate: () -> Unit,
    onRename: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val moreOptionsLabel = stringResource(R.string.accessibility_more_options)
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            FolderChip(
                label = stringResource(R.string.chat_page_folder_default),
                selected = selectedFolderId == null,
                onClick = { onSelect(null) },
                onLongClick = null,
            )
        }
        items(folders) { folder ->
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                FolderChip(
                    label = folder.name,
                    icon = HugeIcons.Folder01,
                    selected = selectedFolderId == folder.id,
                    onClick = { onSelect(folder.id) },
                    onLongClick = { menuExpanded = true },
                    onLongClickLabel = moreOptionsLabel,
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_rename)) },
                        leadingIcon = { Icon(HugeIcons.PencilEdit01, null) },
                        onClick = {
                            onRename(folder)
                            menuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_delete)) },
                        leadingIcon = { Icon(HugeIcons.Delete01, null) },
                        onClick = {
                            onDelete(folder)
                            menuExpanded = false
                        },
                    )
                }
            }
        }
        item {
            FolderChip(
                label = stringResource(R.string.chat_page_folder_add),
                icon = HugeIcons.FolderAdd,
                selected = false,
                onClick = onCreate,
                onLongClick = null,
            )
        }
    }
}

@Composable
internal fun ConversationSortButton(
    sortByCreatedAt: Boolean,
    onSortByCreatedAtChange: (Boolean) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val currentLabel =
        stringResource(
            if (sortByCreatedAt) {
                R.string.conversation_sort_created
            } else {
                R.string.conversation_sort_updated
            },
        )
    Box {
        Surface(
            onClick = { menuExpanded = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Tooltip(
                tooltip = { Text(currentLabel) },
            ) {
                Icon(
                    imageVector = HugeIcons.Sorting01,
                    contentDescription = currentLabel,
                    modifier =
                        Modifier
                            .padding(8.dp)
                            .size(16.dp),
                )
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.conversation_sort_updated)) },
                trailingIcon = {
                    if (!sortByCreatedAt) {
                        Icon(HugeIcons.Tick01, null, modifier = Modifier.size(18.dp))
                    }
                },
                onClick = {
                    menuExpanded = false
                    if (sortByCreatedAt) onSortByCreatedAtChange(false)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.conversation_sort_created)) },
                trailingIcon = {
                    if (sortByCreatedAt) {
                        Icon(HugeIcons.Tick01, null, modifier = Modifier.size(18.dp))
                    }
                },
                onClick = {
                    menuExpanded = false
                    if (!sortByCreatedAt) onSortByCreatedAtChange(true)
                },
            )
        }
    }
}

@Composable
internal fun FolderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String? = null,
    icon: ImageVector? = null,
) {
    Surface(
        shape = CircleShape,
        color =
            if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        modifier =
            Modifier
                .clip(CircleShape)
                .semantics {
                    toggleableState = ToggleableState(selected)
                }.combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClickLabel = onLongClickLabel,
                    onLongClick = onLongClick,
                ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(14.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun AssistantItem(
    assistant: Assistant,
    isCurrentAssistant: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color =
            if (isCurrentAssistant) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        tonalElevation = if (isCurrentAssistant) 2.dp else 0.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UIAvatar(
                name = assistant.name,
                value = assistant.avatar,
                onUpdate = {},
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isCurrentAssistant) {
                    Text(
                        text = stringResource(R.string.assistant_page_current_assistant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
