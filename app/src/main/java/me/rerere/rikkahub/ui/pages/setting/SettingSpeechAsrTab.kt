package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.asr.ASRProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.rememberReorderCommitState
import me.rerere.rikkahub.ui.pages.setting.components.ASRProviderConfigure
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun ASRProviderList(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onEdit: (ASRProviderSetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lazyListState = rememberLazyListState()
    // 拖动只改本地顺序，停手后统一提交一次（每格都写设置会让整列重组、拖动明显卡顿）
    val reorderCommit =
        rememberReorderCommitState(
            items = settings.asrProviders,
            key = { it.id },
            onCommit = { providers -> onUpdateSettings { current -> current.copy(asrProviders = providers) } },
        )
    val reorderableState =
        rememberReorderableLazyListState(lazyListState) { from, to ->
            reorderCommit.onMove(from.key, to.key)
        }

    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        state = lazyListState,
    ) {
        items(reorderCommit.items, key = { it.id }) { provider ->
            ReorderableItem(
                state = reorderableState,
                key = provider.id,
            ) { isDragging ->
                ASRProviderItem(
                    modifier =
                        Modifier
                            .scale(if (isDragging) 0.95f else 1f)
                            .fillMaxWidth(),
                    provider = provider,
                    dragHandle = {
                        val haptic = LocalHapticFeedback.current
                        IconButton(
                            onClick = {},
                            modifier =
                                Modifier
                                    .longPressDraggableHandle(
                                        onDragStarted = {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            reorderCommit.onDragStart()
                                        },
                                        onDragStopped = {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            reorderCommit.onDragStop()
                                        },
                                    ),
                        ) {
                            Icon(
                                imageVector = HugeIcons.DragDropHorizontal,
                                contentDescription = null,
                            )
                        }
                    },
                    isSelected = settings.selectedASRProviderId == provider.id,
                    onSelect = {
                        onUpdateSettings { current -> current.copy(selectedASRProviderId = provider.id) }
                    },
                    onEdit = {
                        onEdit(provider)
                    },
                    onDelete = {
                        val newProviders = settings.asrProviders - provider
                        val newSelectedId =
                            if (settings.selectedASRProviderId == provider.id) {
                                newProviders.firstOrNull()?.id
                            } else {
                                settings.selectedASRProviderId
                            }
                        onUpdateSettings { current ->
                            current.copy(
                                asrProviders = current.asrProviders.filter { it.id != provider.id },
                                selectedASRProviderId = newSelectedId,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun AddASRProviderButton(onAdd: (ASRProviderSetting) -> Unit) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var showTypeMenu by remember { mutableStateOf(false) }
    var currentProvider: ASRProviderSetting by remember { mutableStateOf(ASRProviderSetting.OpenAIRealtime()) }

    Box {
        IconButton(
            onClick = { showTypeMenu = true },
        ) {
            Icon(HugeIcons.Add01, stringResource(R.string.setting_asr_page_add_provider))
        }
        DropdownMenu(
            expanded = showTypeMenu,
            onDismissRequest = { showTypeMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.asr_provider_openai_realtime)) },
                onClick = {
                    currentProvider = ASRProviderSetting.OpenAIRealtime()
                    showTypeMenu = false
                    showBottomSheet = true
                },
            )
            DropdownMenuItem(
                text = { Text("DashScope") },
                onClick = {
                    currentProvider = ASRProviderSetting.DashScope()
                    showTypeMenu = false
                    showBottomSheet = true
                },
            )
            DropdownMenuItem(
                text = { Text("Volcengine") },
                onClick = {
                    currentProvider = ASRProviderSetting.Volcengine()
                    showTypeMenu = false
                    showBottomSheet = true
                },
            )
            DropdownMenuItem(
                text = { Text("MiMo") },
                onClick = {
                    currentProvider = ASRProviderSetting.MiMo()
                    showTypeMenu = false
                    showBottomSheet = true
                },
            )
            DropdownMenuItem(
                text = { Text("Step") },
                onClick = {
                    currentProvider = ASRProviderSetting.Step()
                    showTypeMenu = false
                    showBottomSheet = true
                },
            )
        }
    }

    if (showBottomSheet) {
        val bottomSheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            )
        ModalBottomSheet(
            onDismissRequest = {
                showBottomSheet = false
            },
            sheetState = bottomSheetState,
            dragHandle = {
                BottomSheetDefaults.DragHandle()
            },
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .fillMaxHeight(0.8f)
                        .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_asr_page_add_provider),
                    style = MaterialTheme.typography.headlineSmall,
                )

                ASRProviderConfigure(
                    setting = currentProvider,
                    onValueChange = { newState ->
                        currentProvider = newState
                    },
                    modifier = Modifier.weight(1f),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            showBottomSheet = false
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    TextButton(
                        onClick = {
                            onAdd(currentProvider)
                            showBottomSheet = false
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.setting_tts_page_add))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ASRProviderEditSheet(
    provider: ASRProviderSetting,
    onDismiss: () -> Unit,
    onSave: (ASRProviderSetting) -> Unit,
) {
    val bottomSheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    var currentProvider by remember(provider) { mutableStateOf(provider) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = bottomSheetState,
        dragHandle = {
            BottomSheetDefaults.DragHandle()
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .fillMaxHeight(0.8f)
                    .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_asr_page_edit_provider),
                style = MaterialTheme.typography.headlineSmall,
            )

            ASRProviderConfigure(
                setting = currentProvider,
                onValueChange = { newState ->
                    currentProvider = newState
                },
                modifier = Modifier.weight(1f),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }

                TextButton(
                    onClick = { onSave(currentProvider) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            }
        }
    }
}
