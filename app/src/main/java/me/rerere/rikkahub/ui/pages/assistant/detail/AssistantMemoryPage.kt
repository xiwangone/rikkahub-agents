package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private enum class MemoryTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    MEMORIES(HugeIcons.Brain02, R.string.assistant_page_manage_memory_title),
    SETTINGS(HugeIcons.Settings03, R.string.settings),
    ;

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM =
        koinViewModel(
            parameters = {
                parametersOf(id)
            },
        )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { MemoryTab.entries.size }
    val scope = rememberCoroutineScope()

    val memoryDialogState =
        useEditState<AssistantMemory> {
            if (it.id == 0) {
                vm.addMemory(it)
            } else {
                vm.updateMemory(it)
            }
        }

    // 记忆编辑对话框
    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = {
                memoryDialogState.dismiss()
            },
            title = {
                Text(stringResource(R.string.assistant_page_manage_memory_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(
                        value = memory.content,
                        onValueChange = {
                            update(memory.copy(content = it))
                        },
                        label = {
                            Text(stringResource(R.string.assistant_page_manage_memory_title))
                        },
                        minLines = 2,
                        maxLines = 8,
                    )
                    // 记忆分层：core（常驻）/ conditional（按需检索）
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.assistant_page_memory_tier),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        val tiers = listOf(MemoryRepository.TIER_CORE, MemoryRepository.TIER_CONDITIONAL)
                        ConnectedChoiceButtons(
                            options = tiers,
                            selected = memory.tier.takeIf { it in tiers } ?: MemoryRepository.TIER_CORE,
                            onSelect = { update(memory.copy(tier = it)) },
                            containerColor = MaterialTheme.colorScheme.surfaceBright,
                        ) { tier, selected ->
                            ConnectedChoiceLabel(
                                text =
                                    if (tier == MemoryRepository.TIER_CONDITIONAL) {
                                        stringResource(R.string.assistant_page_memory_tier_conditional)
                                    } else {
                                        stringResource(R.string.assistant_page_memory_tier_core)
                                    },
                                selected = selected,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.assistant_page_memory_tier_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.confirm()
                    },
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.dismiss()
                    },
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_memory))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (MemoryTab.entries[pagerState.currentPage] == MemoryTab.MEMORIES) {
                        IconButton(onClick = { memoryDialogState.open(AssistantMemory(0, "")) }) {
                            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.add))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = MemoryTab.entries,
                modifier = Modifier.padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            ) { page ->
                when (MemoryTab.entries[page]) {
                    MemoryTab.MEMORIES -> MemoryItemsTab(
                        memories = memories,
                        memoryEnabled = assistant.enableMemory,
                        onEditMemory = { memoryDialogState.open(it) },
                        onDeleteMemory = { vm.deleteMemory(it) },
                        onOpenSettings = {
                            scope.launch { pagerState.animateScrollToPage(MemoryTab.SETTINGS.ordinal) }
                        },
                    )

                    MemoryTab.SETTINGS -> MemorySettingsTab(
                        assistant = assistant,
                        onUpdateAssistant = { vm.update(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MemorySettingsTab(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
) {
    var showTimeReminderIntervalDialog by remember(assistant.id) { mutableStateOf(false) }
    var timeReminderIntervalInput by remember(assistant.id) { mutableStateOf("") }

    if (showTimeReminderIntervalDialog) {
        val interval = timeReminderIntervalInput.toIntOrNull()?.takeIf { it > 0 }
        AlertDialog(
            onDismissRequest = { showTimeReminderIntervalDialog = false },
            title = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
            text = {
                TextField(
                    value = timeReminderIntervalInput,
                    onValueChange = { timeReminderIntervalInput = it },
                    label = { Text(stringResource(R.string.assistant_page_time_reminder_interval_label)) },
                    supportingText = { Text(stringResource(R.string.assistant_page_time_reminder_interval_hint)) },
                    isError = interval == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = interval != null,
                    onClick = {
                        interval?.let {
                            onUpdateAssistant(assistant.copy(timeReminderIntervalMinutes = it))
                        }
                        showTimeReminderIntervalDialog = false
                    },
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimeReminderIntervalDialog = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardGroup {
            switchItem(
                checked = assistant.enableMemory,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            enableMemory = it,
                        ),
                    )
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_memory_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
            )
            switchItem(
                checked = assistant.useGlobalMemory,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            useGlobalMemory = it,
                        ),
                    )
                },
                enabled = assistant.enableMemory,
                supportingContent = { Text(stringResource(R.string.assistant_page_global_memory_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
            )
            switchItem(
                checked = assistant.enableRecentChatsReference,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            enableRecentChatsReference = it,
                        ),
                    )
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_recent_chats_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
            )
        }

        CardGroup {
            switchItem(
                checked = assistant.enableTimeReminder,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            enableTimeReminder = it,
                        ),
                    )
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder)) },
            )
            if (assistant.enableTimeReminder) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval_desc)) },
                    trailingContent = {
                        CardGroupValue(
                            stringResource(
                                R.string.assistant_page_time_reminder_interval_value,
                                assistant.timeReminderIntervalMinutes,
                            ),
                        )
                    },
                    onClick = {
                        timeReminderIntervalInput = assistant.timeReminderIntervalMinutes.toString()
                        showTimeReminderIntervalDialog = true
                    },
                )
            }
        }
    }
}

@Composable
private fun MemoryItemsTab(
    memories: List<AssistantMemory>,
    memoryEnabled: Boolean,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var pendingDeleteMemory by remember { mutableStateOf<AssistantMemory?>(null) }

    if (memories.isEmpty()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            ExtensionEmptyState(
                message = stringResource(R.string.assistant_page_memory_empty),
                description =
                    stringResource(
                        if (memoryEnabled) {
                            R.string.assistant_page_memory_empty_hint
                        } else {
                            R.string.assistant_page_memory_empty_disabled_hint
                        },
                    ),
                buttonText = stringResource(R.string.settings).takeIf { !memoryEnabled },
                onAction = onOpenSettings,
                icon = HugeIcons.Brain02,
            )
        }
        return
    }

    val coreMemories = remember(memories) { memories.filter { it.tier != MemoryRepository.TIER_CONDITIONAL } }
    val conditionalMemories = remember(memories) { memories.filter { it.tier == MemoryRepository.TIER_CONDITIONAL } }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (coreMemories.isNotEmpty()) {
            CardGroup(
                title = { Text(stringResource(R.string.assistant_page_memory_tier_core)) },
            ) {
                coreMemories.fastForEach { memory ->
                    key(memory.id) {
                        item(
                            onClick = { onEditMemory(memory) },
                            headlineContent = {
                                Text(
                                    text = memory.content,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            trailingContent = {
                                ItemActionMenu(
                                    actions =
                                        listOf(
                                            ItemAction(
                                                text = stringResource(R.string.edit),
                                                icon = HugeIcons.PencilEdit01,
                                                onClick = { onEditMemory(memory) },
                                            ),
                                            ItemAction(
                                                text = stringResource(R.string.delete),
                                                icon = HugeIcons.Delete01,
                                                destructive = true,
                                                onClick = { pendingDeleteMemory = memory },
                                            ),
                                        ),
                                )
                            },
                        )
                    }
                }
            }
        }

        if (conditionalMemories.isNotEmpty()) {
            CardGroup(
                title = { Text(stringResource(R.string.assistant_page_memory_tier_conditional)) },
            ) {
                conditionalMemories.fastForEach { memory ->
                    key(memory.id) {
                        item(
                            onClick = { onEditMemory(memory) },
                            headlineContent = {
                                Text(
                                    text = memory.content,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            trailingContent = {
                                ItemActionMenu(
                                    actions =
                                        listOf(
                                            ItemAction(
                                                text = stringResource(R.string.edit),
                                                icon = HugeIcons.PencilEdit01,
                                                onClick = { onEditMemory(memory) },
                                            ),
                                            ItemAction(
                                                text = stringResource(R.string.delete),
                                                icon = HugeIcons.Delete01,
                                                destructive = true,
                                                onClick = { pendingDeleteMemory = memory },
                                            ),
                                        ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(onDeleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Text(
                text = pendingDeleteMemory?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}
