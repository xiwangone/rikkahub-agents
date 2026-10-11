package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Represents different types of items in the conversation list
 */
sealed class ConversationListItem {
    data class DateHeader(
        val date: LocalDate,
        val label: String,
    ) : ConversationListItem()

    data object PinnedHeader : ConversationListItem()

    /** 子代理运行分组的标题行（可折叠） */
    data object SubAgentHeader : ConversationListItem()

    data class Item(
        val conversation: Conversation,
    ) : ConversationListItem()
}

@Composable
fun ColumnScope.ConversationList(
    current: Conversation,
    conversations: LazyPagingItems<ConversationListItem>,
    conversationJobs: Collection<Uuid>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onClick: (Conversation) -> Unit = {},
    onDelete: (Conversation) -> Unit = {},
    onRename: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {},
    subAgentExpanded: Boolean = false,
    onToggleSubAgent: () -> Unit = {},
    collapsedDates: Set<String> = emptySet(),
    onToggleDate: (String) -> Unit = {},
    onCleanSubAgent: () -> Unit = {},
) {
    var hasScrolledToCurrent by remember(current.id) { mutableStateOf(false) }
    var showCleanConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(current.id, conversations.itemCount, hasScrolledToCurrent) {
        if (hasScrolledToCurrent) return@LaunchedEffect
        val currentIndex =
            conversations.itemSnapshotList.items.indexOfFirst {
                (it as? ConversationListItem.Item)?.conversation?.id == current.id
            }
        if (currentIndex >= 0) {
            val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == currentIndex }
            if (!isVisible) {
                listState.scrollToItem(currentIndex)
            }
            hasScrolledToCurrent = true
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (conversations.itemCount == 0) {
            when (conversations.loadState.refresh) {
                is LoadState.Loading -> item(key = "refresh_loading") { PagingLoadingItem() }
                is LoadState.Error ->
                    item(key = "refresh_error") {
                        PagingRetryItem { conversations.retry() }
                    }
                is LoadState.NotLoading -> {
                    item(key = "empty_conversations") {
                        Surface(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Text(
                                text = stringResource(id = R.string.chat_page_no_conversations),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }

        when (conversations.loadState.append) {
            is LoadState.Loading -> item(key = "append_loading") { PagingLoadingItem() }
            is LoadState.Error ->
                item(key = "append_error") {
                    PagingRetryItem { conversations.retry() }
                }
            is LoadState.NotLoading -> Unit
        }

        items(
            count = conversations.itemCount,
            key =
                conversations.itemKey { item ->
                    when (item) {
                        is ConversationListItem.DateHeader -> "date_${item.date}"
                        is ConversationListItem.PinnedHeader -> "pinned_header"
                        is ConversationListItem.SubAgentHeader -> "sub_agent_header"
                        is ConversationListItem.Item -> item.conversation.id.toString()
                    }
                },
        ) { index ->
            when (val item = conversations[index]) {
                is ConversationListItem.DateHeader -> {
                    DateHeaderItem(
                        label = item.label,
                        collapsed = item.date.toString() in collapsedDates,
                        onClick = { onToggleDate(item.date.toString()) },
                        modifier = Modifier.animateItem(),
                    )
                }

                is ConversationListItem.PinnedHeader -> {
                    PinnedHeader(
                        modifier = Modifier.animateItem(),
                    )
                }

                is ConversationListItem.SubAgentHeader -> {
                    SubAgentHeaderItem(
                        expanded = subAgentExpanded,
                        onClick = onToggleSubAgent,
                        onClean = { showCleanConfirm = true },
                        modifier = Modifier.animateItem(),
                    )
                }

                is ConversationListItem.Item -> {
                    ConversationItem(
                        conversation = item.conversation,
                        selected = item.conversation.id == current.id,
                        loading = item.conversation.id in conversationJobs,
                        onClick = onClick,
                        onDelete = onDelete,
                        onRename = onRename,
                        onPin = onPin,
                        onMoveToAssistant = onMoveToAssistant,
                        onMoveToFolder = onMoveToFolder,
                        modifier = Modifier.animateItem(),
                    )
                }

                null -> {
                    // Placeholder for loading state
                }
            }
        }
    }

    if (showCleanConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanConfirm = false },
            title = { Text(stringResource(R.string.chat_sub_agent_clean_confirm_title)) },
            text = { Text(stringResource(R.string.chat_sub_agent_clean_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanConfirm = false
                        onCleanSubAgent()
                    },
                ) {
                    Text(stringResource(R.string.chat_sub_agent_clean))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanConfirm = false }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            },
        )
    }
}

@Composable
private fun DateHeaderItem(
    label: String,
    collapsed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .semantics { toggleableState = ToggleableState(!collapsed) }
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (collapsed) "\u25B8" else "\u25BE",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SubAgentHeaderItem(
    expanded: Boolean,
    onClick: () -> Unit,
    onClean: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .semantics { toggleableState = ToggleableState(expanded) }
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.chat_sub_agent_group),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (expanded) "\u25BE" else "\u25B8",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        IconButton(onClick = onClean) {
            Icon(
                imageVector = HugeIcons.Delete01,
                contentDescription = stringResource(R.string.chat_sub_agent_clean),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PinnedHeader(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = HugeIcons.Pin,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.pinned_chats),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    selected: Boolean,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onDelete: (Conversation) -> Unit = {},
    onRename: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {},
    onClick: (Conversation) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val backgroundColor =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val contentColor =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val focusManager = LocalFocusManager.current
    val moreOptionsLabel = stringResource(R.string.accessibility_more_options)
    var showDropdownMenu by remember {
        mutableStateOf(false)
    }
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(50f))
                .semantics { toggleableState = ToggleableState(selected) }
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onLongClickLabel = moreOptionsLabel,
                    onClick = { onClick(conversation) },
                    onLongClick = {
                        // 抽屉常驻时也收起输入焦点，避免键盘闪现
                        focusManager.clearFocus(force = true)
                        showDropdownMenu = true
                    },
                ).background(backgroundColor),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = conversation.title.ifBlank { stringResource(id = R.string.chat_page_new_message) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = contentColor,
            )
            Spacer(Modifier.weight(1f))

            // 置顶图标
            AnimatedVisibility(conversation.isPinned) {
                Icon(
                    imageVector = HugeIcons.Pin,
                    contentDescription = stringResource(R.string.accessibility_pinned),
                    modifier = Modifier.size(12.dp),
                    tint = contentColor,
                )
            }
            AnimatedVisibility(loading) {
                // stringResource 是 @Composable，不能放进 Modifier.semantics{}（非组合上下文）→ 先取值
                val loadingDescription = stringResource(R.string.accessibility_loading)
                Box(
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.extendColors.green6)
                            .size(4.dp)
                            .semantics {
                                contentDescription = loadingDescription
                            },
                )
            }
            IconButton(onClick = { showDropdownMenu = true }) {
                Icon(
                    imageVector = HugeIcons.MoreVertical,
                    contentDescription = stringResource(R.string.accessibility_chat_options),
                    modifier = Modifier.size(20.dp),
                    tint = contentColor,
                )
            }
            DropdownMenu(
                expanded = showDropdownMenu,
                onDismissRequest = { showDropdownMenu = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (conversation.isPinned) {
                                stringResource(
                                    R.string.unpin_chat,
                                )
                            } else {
                                stringResource(R.string.pin_chat)
                            },
                        )
                    },
                    onClick = {
                        onPin(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                            null,
                        )
                    },
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_rename_chat))
                    },
                    onClick = {
                        onRename(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Refresh01, null)
                    },
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.chat_page_move_to_assistant))
                    },
                    onClick = {
                        onMoveToAssistant(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Forward02, null)
                    },
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.chat_page_move_to_folder))
                    },
                    onClick = {
                        onMoveToFolder(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Folder01, null)
                    },
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_delete))
                    },
                    onClick = {
                        onDelete(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Delete01, null)
                    },
                )
            }
        }
    }
}

@Composable
private fun PagingLoadingItem() {
    val loadingDescription = stringResource(R.string.accessibility_loading)
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp).semantics { contentDescription = loadingDescription },
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun PagingRetryItem(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextButton(onClick = onRetry) {
            Icon(imageVector = HugeIcons.Refresh01, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.accessibility_refresh_page))
        }
    }
}
