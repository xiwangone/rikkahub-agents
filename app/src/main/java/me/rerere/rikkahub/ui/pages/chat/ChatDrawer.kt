package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.dokar.sonner.ToastType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.provider.providers.backend.SessionPathStore
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.shouldShowUpdateReminders
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ai.AssistantPicker
import me.rerere.rikkahub.ui.components.ui.BackupReminderCard
import me.rerere.rikkahub.ui.components.ui.Greeting
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.UpdateCard
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.rememberIsPlayStoreVersion
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.toDp
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun ChatDrawerContent(
    navController: Navigator,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val isPlayStore = rememberIsPlayStoreVersion()
    val repo = koinInject<ConversationRepository>()
    val sessionPathStore = koinInject<SessionPathStore>()

    val activity = context as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)

    val conversations = drawerVm.conversations.collectAsLazyPagingItems()
    val folders by drawerVm.folders.collectAsStateWithLifecycle()
    val selectedFolderId by drawerVm.selectedFolderId.collectAsStateWithLifecycle()
    val sortByCreatedAt by drawerVm.sortByCreatedAt.collectAsStateWithLifecycle()
    val subAgentExpanded by drawerVm.subAgentExpanded.collectAsStateWithLifecycle()
    val collapsedDates by drawerVm.collapsedDates.collectAsStateWithLifecycle()
    val conversationListState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = drawerVm.scrollIndex,
            initialFirstVisibleItemScrollOffset = drawerVm.scrollOffset,
        )

    LaunchedEffect(conversationListState) {
        snapshotFlow {
            conversationListState.firstVisibleItemIndex to
                conversationListState.firstVisibleItemScrollOffset
        }.distinctUntilChanged()
            .collectLatest { (index, offset) ->
                drawerVm.saveScrollPosition(index, offset)
            }
    }

    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )

    val nicknameEditState =
        useEditState<String> { newNickname ->
            vm.updateSettings { currentSettings ->
                currentSettings.copy(
                    displaySetting =
                        currentSettings.displaySetting.copy(
                            userNickname = newNickname,
                        ),
                )
            }
        }

    var showMoveToAssistantSheet by remember { mutableStateOf(false) }
    var conversationToMove by remember { mutableStateOf<Conversation?>(null) }
    val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    var showMoveToFolderSheet by remember { mutableStateOf(false) }
    var conversationToMoveFolder by remember { mutableStateOf<Conversation?>(null) }
    val folderSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<Folder?>(null) }
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }
    var conversationToRename by remember { mutableStateOf<Conversation?>(null) }

    var showMenuPopup by remember { mutableStateOf(false) }

    @Suppress("DEPRECATION")
    val headerWindowSize = currentWindowDpSize()
    val scrollableHeader = headerWindowSize.height < 640.dp

    val drawerHeader: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (settings.displaySetting.shouldShowUpdateReminders() && !isPlayStore) {
                UpdateCard(vm)
            }

            BackupReminderCard(
                settings = settings,
                onClick = { navController.navigate(Screen.Backup) },
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UIAvatar(
                    name = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                    value = settings.displaySetting.userAvatar,
                    onUpdate = { newAvatar ->
                        vm.updateSettings { currentSettings ->
                            currentSettings.copy(
                                displaySetting =
                                    currentSettings.displaySetting.copy(
                                        userAvatar = newAvatar,
                                    ),
                            )
                        }
                    },
                    modifier = Modifier.size(50.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text =
                                settings.displaySetting.userNickname.ifBlank {
                                    stringResource(R.string.user_default_name)
                                },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier.clickable {
                                    nicknameEditState.open(settings.displaySetting.userNickname)
                                },
                        )

                        Icon(
                            imageVector = HugeIcons.PencilEdit01,
                            contentDescription = stringResource(R.string.accessibility_edit_nickname),
                            modifier =
                                Modifier
                                    .onClick {
                                        nicknameEditState.open(settings.displaySetting.userNickname)
                                    }.size(LocalTextStyle.current.fontSize.toDp()),
                        )
                    }
                    Greeting(
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            DrawerActions(navController = navController)

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FolderBar(
                    folders = folders,
                    selectedFolderId = selectedFolderId,
                    onSelect = { folderId ->
                        if (folderId != selectedFolderId) {
                            drawerVm.selectFolder(folderId)
                            scope.launch { conversationListState.scrollToItem(if (scrollableHeader) 1 else 0) }
                        }
                    },
                    onCreate = { showCreateFolderDialog = true },
                    onRename = { folderToRename = it },
                    onDelete = { folderToDelete = it },
                    modifier = Modifier.weight(1f),
                )

                ConversationSortButton(
                    sortByCreatedAt = sortByCreatedAt,
                    onSortByCreatedAtChange = { wantCreated ->
                        if (wantCreated != sortByCreatedAt) {
                            drawerVm.toggleConversationSortOrder()
                            scope.launch { conversationListState.scrollToItem(if (scrollableHeader) 1 else 0) }
                        }
                    },
                )
            }
        }
    }

    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!scrollableHeader) {
                drawerHeader()
            }

            ConversationList(
                header = if (scrollableHeader) drawerHeader else null,
                current = current,
                conversations = conversations,
                conversationJobs = conversationJobs.keys,
                listState = conversationListState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                onClick = {
                    navigateToChatPage(navController, it.id)
                },
                onRename = {
                    conversationToRename = it
                },
                onDelete = {
                    scope.launch {
                        vm.deleteConversation(it).join()
                        runCatching { sessionPathStore.remove(it.id.toString()) }
                        conversations.refresh()
                        if (it.id == current.id) {
                            navigateToChatPage(navController)
                        }
                    }
                },
                onPin = {
                    vm.updatePinnedStatus(it)
                },
                onMoveToAssistant = {
                    conversationToMove = it
                    showMoveToAssistantSheet = true
                },
                onMoveToFolder = {
                    conversationToMoveFolder = it
                    showMoveToFolderSheet = true
                },
                onCleanSubAgent = {
                    scope.launch {
                        drawerVm.cleanSubAgentRuns(conversationJobs.keys)
                        conversations.refresh()
                    }
                },
                subAgentExpanded = subAgentExpanded,
                onToggleSubAgent = { drawerVm.toggleSubAgentExpanded() },
                collapsedDates = collapsedDates,
                onToggleDate = { drawerVm.toggleDateCollapsed(it) },
            )

            AssistantPicker(
                settings = settings,
                onUpdateSettings = { transform ->
                    val assistantId = transform(settings).getCurrentAssistant().id
                    val updateJob = vm.updateSettings(transform)
                    scope.launch {
                        updateJob.join()
                        val id =
                            if (context.readBooleanPreference("create_new_conversation_on_start", true)) {
                                Uuid.random()
                            } else {
                                repo
                                    .getConversationsOfAssistant(assistantId)
                                    .first()
                                    .firstOrNull()
                                    ?.id ?: Uuid.random()
                            }
                        navigateToChatPage(navigator = navController, chatId = id)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                onClickSetting = {
                    val currentAssistantId = settings.assistantId
                    navController.navigate(Screen.AssistantDetail(id = currentAssistantId.toString()))
                },
            )

            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
            ) {
                DrawerAction(
                    icon = {
                        Icon(
                            imageVector = HugeIcons.LookTop,
                            contentDescription = stringResource(R.string.assistant_page_title),
                        )
                    },
                    label = {
                        Text(stringResource(R.string.assistant_page_title))
                    },
                    onClick = {
                        navController.navigate(Screen.Assistant)
                    },
                )

                Box {
                    DrawerAction(
                        icon = {
                            Icon(HugeIcons.Sparkles, "Menu")
                        },
                        label = {
                            Text(stringResource(R.string.menu))
                        },
                        onClick = {
                            showMenuPopup = true
                        },
                    )
                    DropdownMenu(
                        expanded = showMenuPopup,
                        onDismissRequest = { showMenuPopup = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_page_menu_ai_translator)) },
                            leadingIcon = { Icon(HugeIcons.LanguageCircle, null) },
                            onClick = {
                                showMenuPopup = false
                                navController.navigate(Screen.Translator)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_page_menu_image_generation)) },
                            leadingIcon = { Icon(HugeIcons.Image02, null) },
                            onClick = {
                                showMenuPopup = false
                                navController.navigate(Screen.ImageGen)
                            },
                        )
                    }
                }

                DrawerAction(
                    icon = {
                        Icon(HugeIcons.InLove, stringResource(R.string.favorite_page_title))
                    },
                    label = {
                        Text(stringResource(R.string.favorite_page_title))
                    },
                    onClick = {
                        navController.navigate(Screen.Favorite)
                    },
                )

                DrawerAction(
                    icon = {
                        Icon(HugeIcons.ChartColumn, stringResource(R.string.chat_drawer_statistics))
                    },
                    label = {
                        Text(stringResource(R.string.chat_drawer_statistics))
                    },
                    onClick = {
                        navController.navigate(Screen.Stats)
                    },
                )

                Spacer(Modifier.weight(1f))

                DrawerAction(
                    modifier = Modifier.testTag("drawer_settings"),
                    icon = {
                        Icon(HugeIcons.Settings03, stringResource(R.string.settings))
                    },
                    label = { Text(stringResource(R.string.settings)) },
                    onClick = {
                        navController.navigate(Screen.Setting)
                    },
                )
            }
        }
    }

    nicknameEditState.EditStateContent { nickname, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                nicknameEditState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_nickname))
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.confirm()
                    },
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.dismiss()
                    },
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            },
        )
    }

    if (showMoveToFolderSheet) {
        val doMove: (Uuid?) -> Unit = { folderId ->
            conversationToMoveFolder?.let { conversation ->
                drawerVm.moveConversationToFolder(conversation.id, folderId)
                scope.launch {
                    folderSheetState.hide()
                    showMoveToFolderSheet = false
                    conversationToMoveFolder = null
                    conversations.refresh()
                }
            }
        }
        val totalFolderItems = folders.size + 1
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToFolderSheet = false
                conversationToMoveFolder = null
            },
            sheetState = folderSheetState,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_folder),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    item(key = "unfiled") {
                        SegmentedListItem(
                            selected = conversationToMoveFolder?.folderId == null,
                            onClick = { doMove(null) },
                            shapes = ListItemDefaults.segmentedShapes(index = 0, count = totalFolderItems),
                            leadingContent = { Icon(HugeIcons.Folder01, null) },
                        ) {
                            Text(text = stringResource(R.string.chat_page_remove_from_folder))
                        }
                    }

                    itemsIndexed(folders, key = { _, f -> f.id }) { idx, folder ->
                        val isCurrent = folder.id == conversationToMoveFolder?.folderId
                        SegmentedListItem(
                            selected = isCurrent,
                            onClick = { doMove(folder.id) },
                            shapes = ListItemDefaults.segmentedShapes(index = idx + 1, count = totalFolderItems),
                            leadingContent = { Icon(HugeIcons.Folder01, null) },
                        ) {
                            Text(
                                text = folder.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text(stringResource(R.string.chat_page_create_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_folder_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.createFolder(name)
                        showCreateFolderDialog = false
                    },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            },
        )
    }

    folderToRename?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.chat_page_rename_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.renameFolder(folder.id, name)
                        folderToRename = null
                    },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            },
        )
    }

    conversationToRename?.let { conversation ->
        var title by remember(conversation.id) { mutableStateOf(conversation.title) }
        AlertDialog(
            onDismissRequest = { conversationToRename = null },
            title = { Text(stringResource(R.string.chat_page_rename_chat)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            drawerVm.regenerateTitle(conversation)
                            conversationToRename = null
                        },
                    ) {
                        Text(stringResource(R.string.chat_page_regenerate_title))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.renameConversation(conversation, title)
                        conversationToRename = null
                    },
                    enabled = title.isNotBlank(),
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { conversationToRename = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            },
        )
    }

    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.chat_page_delete_folder)) },
            text = { Text(stringResource(R.string.chat_page_delete_folder_confirm, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (drawerVm.deleteFolder(folder.id)) {
                            folderToDelete = null
                            conversations.refresh()
                        } else {
                            toaster.show(
                                context.getString(R.string.chat_page_delete_folder_generating),
                                type = ToastType.Warning,
                            )
                        }
                    },
                ) { Text(stringResource(R.string.chat_page_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            },
        )
    }

    if (showMoveToAssistantSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToAssistantSheet = false
                conversationToMove = null
            },
            sheetState = bottomSheetState,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_assistant),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    itemsIndexed(settings.assistants, key = { _, it -> it.id }) { idx, assistant ->
                        val isCurrent = assistant.id == conversationToMove?.assistantId
                        SegmentedListItem(
                            selected = isCurrent,
                            onClick = {
                                conversationToMove?.let { conversation ->
                                    vm.moveConversationToAssistant(conversation, assistant.id)
                                    scope.launch {
                                        bottomSheetState.hide()
                                        showMoveToAssistantSheet = false
                                        conversationToMove = null
                                    }
                                }
                            },
                            shapes = ListItemDefaults.segmentedShapes(index = idx, count = settings.assistants.size),
                            leadingContent = {
                                UIAvatar(
                                    name = assistant.name,
                                    value = assistant.avatar,
                                    onUpdate = {},
                                    modifier = Modifier.size(36.dp),
                                )
                            },
                            supportingContent =
                                if (isCurrent) {
                                    { Text(stringResource(R.string.assistant_page_current_assistant)) }
                                } else {
                                    null
                                },
                        ) {
                            Text(
                                text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
