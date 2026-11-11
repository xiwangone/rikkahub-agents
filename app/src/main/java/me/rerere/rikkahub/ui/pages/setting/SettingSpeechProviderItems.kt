package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.asr.ASRProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_SYSTEM_TTS_ID
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.tts.provider.TTSProviderSetting


@Composable
internal fun TTSProviderItem(
    provider: TTSProviderSetting,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    dragHandle: @Composable () -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDropdownMenu by remember { mutableStateOf(false) }
    val tts = LocalTTSState.current
    val isSpeaking by tts.isSpeaking.collectAsStateWithLifecycle()
    val isAvailable by tts.isAvailable.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier,
        shape = cardGroupItemShape(0, 1),
        color =
            if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                CustomColors.listItemColors.containerColor
            },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AutoAIIcon(
                    name = provider.name.ifEmpty { stringResource(R.string.setting_tts_page_default_name) },
                    modifier = Modifier.size(32.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = provider.name.ifEmpty { stringResource(R.string.setting_tts_page_default_name) },
                        style = MaterialTheme.typography.titleMedium,
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )

                    Text(
                        text =
                            when (provider) {
                                is TTSProviderSetting.OpenAI -> {
                                    stringResource(
                                        R.string.setting_tts_page_provider_openai,
                                    )
                                }

                                is TTSProviderSetting.Gemini -> {
                                    stringResource(
                                        R.string.setting_tts_page_provider_gemini,
                                    )
                                }

                                is TTSProviderSetting.MiniMax -> {
                                    "MiniMax"
                                }

                                is TTSProviderSetting.SystemTTS -> {
                                    stringResource(
                                        R.string.setting_tts_page_provider_system,
                                    )
                                }

                                is TTSProviderSetting.Qwen -> {
                                    "Qwen"
                                }

                                is TTSProviderSetting.Groq -> {
                                    "Groq"
                                }

                                is TTSProviderSetting.XAI -> {
                                    "xAI"
                                }

                                is TTSProviderSetting.MiMo -> {
                                    "MiMo"
                                }

                                is TTSProviderSetting.Step -> {
                                    "Step"
                                }

                                is TTSProviderSetting.ElevenLabs -> {
                                    "ElevenLabs"
                                }

                                is TTSProviderSetting.FishAudio -> {
                                    "Fish Audio"
                                }

                                is TTSProviderSetting.Volcengine -> {
                                    stringResource(
                                        R.string.setting_tts_page_provider_volcengine,
                                    )
                                }
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                RadioButton(
                    selected = isSelected,
                    onClick = onSelect,
                )

                dragHandle()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 状态标签
                if (isSelected) {
                    Tag(type = TagType.SUCCESS) {
                        Text(stringResource(R.string.setting_tts_page_selected))
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // TTS测试播放按钮
                if (isSelected && isAvailable) {
                    val testText = stringResource(R.string.setting_tts_page_test_text)
                    IconButton(
                        onClick = {
                            if (!isSpeaking) {
                                tts.speak(testText)
                            } else {
                                tts.stop()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                            contentDescription =
                                if (isSpeaking) {
                                    stringResource(
                                        R.string.stop,
                                    )
                                } else {
                                    stringResource(R.string.test_tts)
                                },
                            tint = if (isSpeaking) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        )
                    }
                }

                IconButton(
                    onClick = { showDropdownMenu = true },
                ) {
                    Icon(
                        imageVector = HugeIcons.Tools,
                        contentDescription = stringResource(R.string.setting_tts_page_more_options_content_description),
                    )
                    DropdownMenu(
                        expanded = showDropdownMenu,
                        onDismissRequest = { showDropdownMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            onClick = {
                                showDropdownMenu = false
                                onEdit()
                            },
                            leadingIcon = {
                                Icon(HugeIcons.PencilEdit01, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = {
                                showDropdownMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(HugeIcons.Delete01, contentDescription = null)
                            },
                            enabled = provider.id != DEFAULT_SYSTEM_TTS_ID,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ASRProviderItem(
    provider: ASRProviderSetting,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    dragHandle: @Composable () -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDropdownMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        shape = cardGroupItemShape(0, 1),
        color =
            if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                CustomColors.listItemColors.containerColor
            },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AutoAIIcon(
                    name = provider.name.ifEmpty { stringResource(R.string.setting_asr_page_default_name) },
                    modifier = Modifier.size(32.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = provider.name.ifEmpty { stringResource(R.string.setting_asr_page_default_name) },
                        style = MaterialTheme.typography.titleMedium,
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )

                    Text(
                        text =
                            when (provider) {
                                is ASRProviderSetting.OpenAIRealtime -> "OpenAI Realtime"
                                is ASRProviderSetting.DashScope -> "DashScope"
                                is ASRProviderSetting.Volcengine -> "Volcengine"
                                is ASRProviderSetting.MiMo -> "MiMo"
                                is ASRProviderSetting.Step -> "Step"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                RadioButton(
                    selected = isSelected,
                    onClick = onSelect,
                )

                dragHandle()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelected) {
                    Tag(type = TagType.SUCCESS) {
                        Text(stringResource(R.string.setting_tts_page_selected))
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = { showDropdownMenu = true },
                ) {
                    Icon(
                        imageVector = HugeIcons.Tools,
                        contentDescription = stringResource(R.string.setting_tts_page_more_options_content_description),
                    )
                    DropdownMenu(
                        expanded = showDropdownMenu,
                        onDismissRequest = { showDropdownMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            onClick = {
                                showDropdownMenu = false
                                onEdit()
                            },
                            leadingIcon = {
                                Icon(HugeIcons.PencilEdit01, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = {
                                showDropdownMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(HugeIcons.Delete01, contentDescription = null)
                            },
                        )
                    }
                }
            }
        }
    }
}
