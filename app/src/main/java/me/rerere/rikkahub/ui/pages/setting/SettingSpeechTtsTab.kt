package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_SYSTEM_TTS_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.rememberReorderCommitState
import me.rerere.rikkahub.ui.pages.setting.components.TTSProviderConfigure
import me.rerere.tts.provider.TTSProviderSetting
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

@Composable
internal fun TTSProviderList(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onEdit: (TTSProviderSetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lazyListState = rememberLazyListState()
    // 拖动只改本地顺序，停手后统一提交一次（每格都写设置会让整列重组、拖动明显卡顿）
    val reorderCommit =
        rememberReorderCommitState(
            items = settings.ttsProviders,
            key = { it.id },
            onCommit = { providers -> onUpdateSettings { current -> current.copy(ttsProviders = providers) } },
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
                TTSProviderItem(
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
                    isSelected = settings.selectedTTSProviderId == provider.id,
                    onSelect = {
                        onUpdateSettings { current -> current.copy(selectedTTSProviderId = provider.id) }
                    },
                    onEdit = {
                        onEdit(provider)
                    },
                    onDelete = {
                        val newSelectedId =
                            if (settings.selectedTTSProviderId ==
                                provider.id
                            ) {
                                DEFAULT_SYSTEM_TTS_ID
                            } else {
                                settings.selectedTTSProviderId
                            }
                        onUpdateSettings { current ->
                            current.copy(
                                ttsProviders = current.ttsProviders.filter { it.id != provider.id },
                                selectedTTSProviderId = newSelectedId,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun TTSPlaybackSpeedSetting(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderValue by remember(speed) { mutableFloatStateOf(speed) }

    CardGroup(modifier = modifier.fillMaxWidth()) {
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_tts_page_default_playback_speed)) },
            supportingContent = { Text(stringResource(R.string.setting_tts_page_default_playback_speed_description)) },
            trailingContent = { CardGroupValue("x${"%.1f".format(sliderValue)}") },
        ) {
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = (it * 10).roundToInt() / 10f },
                onValueChangeFinished = { onSpeedChange(sliderValue) },
                valueRange = 0.5f..2.0f,
                steps = 14,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun AddTTSProviderButton(onAdd: (TTSProviderSetting) -> Unit) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var currentProvider: TTSProviderSetting by remember { mutableStateOf(TTSProviderSetting.SystemTTS()) }

    IconButton(
        onClick = {
            currentProvider = TTSProviderSetting.SystemTTS()
            showBottomSheet = true
        },
    ) {
        Icon(HugeIcons.Add01, stringResource(R.string.setting_tts_page_add_provider_content_description))
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
                    text = stringResource(R.string.setting_tts_page_add_provider),
                    style = MaterialTheme.typography.headlineSmall,
                )

                TTSProviderConfigure(
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
internal fun TTSProviderEditSheet(
    provider: TTSProviderSetting,
    onDismiss: () -> Unit,
    onSave: (TTSProviderSetting) -> Unit,
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
                text = stringResource(R.string.setting_tts_page_edit_provider),
                style = MaterialTheme.typography.headlineSmall,
            )

            TTSProviderConfigure(
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
