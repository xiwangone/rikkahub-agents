package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningHigh
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningLow
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningMedium
import kotlin.math.roundToInt

private val levels = ReasoningLevel.entries
private val levelCount = levels.size
private val levelShapes = levels.map { it.shape() }

@Composable
fun ReasoningButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ReasoningPicker(
            reasoningLevel = reasoningLevel,
            onDismissRequest = { showPicker = false },
            onUpdateReasoningLevel = onUpdateReasoningLevel,
        )
    }

    ToggleSurface(
        checked = reasoningLevel.isEnabled,
        onClick = { showPicker = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = reasoningLevel.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (!onlyIcon) Text(stringResource(R.string.setting_provider_page_reasoning))
        }
    }
}

@Composable
fun ReasoningPicker(
    reasoningLevel: ReasoningLevel,
    onDismissRequest: () -> Unit = {},
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    val currentIndex = levels.indexOf(reasoningLevel).coerceAtLeast(0)
    val sliderState =
        remember {
            SliderState(
                value = currentIndex.toFloat(),
                valueRange = 0f..(levelCount - 1).toFloat(),
                steps = levelCount - 2,
            )
        }
    val interactionSource = remember { MutableInteractionSource() }
    val hapticFeedback = LocalHapticFeedback.current
    val previewLevel = levels[sliderState.value.roundToInt().coerceIn(0, levelCount - 1)]

    // 拖动中不回写外部档位；松手后由档位变化自然同步。
    // 此处刻意**不在** Release 回调里强行写 sliderState：那个回调闭包捕获的是组合时的
    // currentIndex，拖动期间已过期，强写会把滑块拉回旧档位。
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(currentIndex) {
        if (!isDragging && sliderState.value.roundToInt() != currentIndex) {
            sliderState.value = currentIndex.toFloat()
        }
    }

    // 用交互源跟踪拖动状态：拖动期间手上的位置是权威值，外部档位不得插回。
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> isDragging = true
                is PressInteraction.Release, is PressInteraction.Cancel -> isDragging = false
            }
        }
    }

    LaunchedEffect(sliderState) {
        snapshotFlow { sliderState.value.roundToInt() }
            .drop(1)
            .collect { hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PickerValueHeader(
                title = stringResource(R.string.reasoning_picker_title),
                value = previewLevel,
                hint = stringResource(R.string.reasoning_picker_hint),
                label = { it.label() },
            ) {
                PickerHero(
                    shapes = levelShapes,
                    index = levels.indexOf(previewLevel),
                    icon = previewLevel.icon(),
                    containerColor =
                        when {
                            !previewLevel.isEnabled -> MaterialTheme.colorScheme.surfaceContainerHighest
                            previewLevel >= ReasoningLevel.HIGH -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.primaryContainer
                        },
                    contentColor =
                        when {
                            !previewLevel.isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                            previewLevel >= ReasoningLevel.HIGH -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        },
                )
            }

            Slider(
                state = sliderState,
                modifier = Modifier.fillMaxWidth(),
                interactionSource = interactionSource,
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = interactionSource,
                        thumbSize = DpSize(4.dp, 48.dp),
                    )
                },
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state,
                        trackCornerSize = 12.dp,
                        modifier = Modifier.height(36.dp),
                    )
                },
            )
        }
    }

    // 只在**跨过整数刻度**时提交：拖动中每帧写外部、随即被外部回写会把滑块拉回手指位置之外，
    // 表现为「循环滑动 + 卡顿」。按整数位去重后，一次拖动最多提交档位数次。
    LaunchedEffect(sliderState) {
        snapshotFlow { sliderState.value.roundToInt() }
            .distinctUntilChanged()
            .collect { snapped ->
                val index = snapped.coerceIn(0, levelCount - 1)
                if (levels[index] != reasoningLevel) {
                    onUpdateReasoningLevel(levels[index])
                }
            }
    }
}

private fun ReasoningLevel.icon(): ImageVector =
    when (this) {
        ReasoningLevel.OFF -> HugeIcons.Idea
        ReasoningLevel.AUTO -> HugeIcons.Idea01
        ReasoningLevel.LOW -> ReasoningLow
        ReasoningLevel.MEDIUM -> ReasoningMedium
        ReasoningLevel.HIGH -> ReasoningHigh
        ReasoningLevel.XHIGH -> ReasoningHigh
        ReasoningLevel.MAX -> ReasoningHigh
    }

private fun ReasoningLevel.shape(): RoundedPolygon =
    when (this) {
        ReasoningLevel.OFF -> MaterialShapes.Circle
        ReasoningLevel.AUTO -> MaterialShapes.Cookie4Sided
        ReasoningLevel.LOW -> MaterialShapes.Cookie6Sided
        ReasoningLevel.MEDIUM -> MaterialShapes.Cookie7Sided
        ReasoningLevel.HIGH -> MaterialShapes.Cookie9Sided
        ReasoningLevel.XHIGH -> MaterialShapes.Cookie12Sided
        ReasoningLevel.MAX -> MaterialShapes.SoftBurst
    }

@Composable
private fun ReasoningLevel.label(): String =
    when (this) {
        ReasoningLevel.OFF -> stringResource(R.string.reasoning_off)
        ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto)
        ReasoningLevel.LOW -> stringResource(R.string.reasoning_light)
        ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_medium)
        ReasoningLevel.HIGH -> stringResource(R.string.reasoning_heavy)
        ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_xhigh)
        ReasoningLevel.MAX -> stringResource(R.string.reasoning_max)
    }

@Composable
@Preview(showBackground = true)
private fun ReasoningPickerPreview() {
    MaterialTheme {
        var level by remember { mutableStateOf(ReasoningLevel.AUTO) }
        ReasoningPicker(
            reasoningLevel = level,
            onUpdateReasoningLevel = { level = it },
        )
    }
}
