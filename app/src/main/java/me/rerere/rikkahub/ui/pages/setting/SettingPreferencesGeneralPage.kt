package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.service.AgentOverlay
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.AppLocale
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesGeneralPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings { current -> current.copy(displaySetting = setting) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    if (showLanguageDialog) {
        LanguagePickerDialog(
            onDismiss = { showLanguageDialog = false },
            onPick = { tag ->
                AppLocale.apply(context, tag)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    (context as? android.app.Activity)?.recreate()
                }
                showLanguageDialog = false
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_general))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                var createNewConversationOnStart by rememberSharedPreferenceBoolean(
                    "create_new_conversation_on_start",
                    true,
                )
                var agentOverlayEnabled by rememberSharedPreferenceBoolean(
                    AgentOverlay.PREF_ENABLED,
                    true,
                )
                CardGroup {
                    item(
                        onClick = { showLanguageDialog = true },
                        headlineContent = { Text(stringResource(R.string.setting_language_title)) },
                        supportingContent = { Text(currentAppLanguageLabel()) },
                    )
                    switchItem(
                        checked = createNewConversationOnStart,
                        onCheckedChange = { createNewConversationOnStart = it },
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_desc))
                        },
                    )
                    switchItem(
                        checked = agentOverlayEnabled,
                        onCheckedChange = { agentOverlayEnabled = it },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_agent_overlay_enabled_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_agent_overlay_enabled_desc))
                        },
                    )
                    switchItem(
                        checked = displaySetting.sendOnEnter,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(sendOnEnter = it)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_desc)) },
                    )
                }
            }

            item {
                CardGroup {
                    switchItem(
                        checked = displaySetting.showMessageJumper,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(showMessageJumper = it)) },
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_show_message_jumper_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_show_message_jumper_desc))
                        },
                    )
                    if (displaySetting.showMessageJumper) {
                        switchItem(
                            checked = displaySetting.messageJumperOnLeft,
                            onCheckedChange = { updateDisplaySetting(displaySetting.copy(messageJumperOnLeft = it)) },
                            headlineContent = {
                                Text(stringResource(R.string.setting_display_page_message_jumper_position_title))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.setting_display_page_message_jumper_position_desc))
                            },
                        )
                    }
                    switchItem(
                        checked = displaySetting.enableAutoScroll,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(enableAutoScroll = it)) },
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_enable_auto_scroll_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_enable_auto_scroll_desc))
                        },
                    )
                }
            }

            item {
                CardGroup {
                    switchItem(
                        checked = displaySetting.useAppIconStyleLoadingIndicator,
                        onCheckedChange = {
                            updateDisplaySetting(displaySetting.copy(useAppIconStyleLoadingIndicator = it))
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_desc))
                        },
                    )
                    switchItem(
                        checked = displaySetting.enableBlurEffect,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(enableBlurEffect = it)) },
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_enable_blur_effect_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_enable_blur_effect_desc))
                        },
                    )
                    switchItem(
                        checked = displaySetting.enableMessageGenerationHapticEffect,
                        onCheckedChange = {
                            updateDisplaySetting(displaySetting.copy(enableMessageGenerationHapticEffect = it))
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_desc))
                        },
                    )
                }
            }

            item {
                CardGroup {
                    switchItem(
                        checked = displaySetting.skipCropImage,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(skipCropImage = it)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_desc)) },
                    )
                    switchItem(
                        checked = displaySetting.pasteLongTextAsFile,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(pasteLongTextAsFile = it)) },
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_desc))
                        },
                    )
                    if (displaySetting.pasteLongTextAsFile) {
                        formItem(
                            headlineContent = {
                                Text(stringResource(R.string.setting_display_page_paste_long_text_threshold_title))
                            },
                            trailingContent = { CardGroupValue("${displaySetting.pasteLongTextThreshold}") },
                        ) {
                            Slider(
                                value = displaySetting.pasteLongTextThreshold.toFloat(),
                                onValueChange = {
                                    updateDisplaySetting(
                                        displaySetting.copy(pasteLongTextThreshold = it.toInt()),
                                    )
                                },
                                valueRange = 100f..10000f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    formItem(
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_attachment_inline_threshold_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_attachment_inline_threshold_desc))
                        },
                        trailingContent = { CardGroupValue("${displaySetting.attachmentInlineThreshold}") },
                    ) {
                        Slider(
                            value =
                                displaySetting.attachmentInlineThreshold
                                    .coerceIn(1_000, 50_000)
                                    .toFloat(),
                            onValueChange = {
                                updateDisplaySetting(
                                    displaySetting.copy(
                                        attachmentInlineThreshold = it.toInt(),
                                    ),
                                )
                            },
                            valueRange = 1000f..50000f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                CardGroup {
                    switchItem(
                        checked = displaySetting.enableVolumeKeyScroll,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(enableVolumeKeyScroll = it)) },
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_volume_key_scroll_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_volume_key_scroll_desc))
                        },
                    )
                    if (displaySetting.enableVolumeKeyScroll) {
                        formItem(
                            headlineContent = {
                                Text(stringResource(R.string.setting_display_page_volume_key_scroll_ratio))
                            },
                            trailingContent = {
                                CardGroupValue("${(displaySetting.volumeKeyScrollRatio * 100).toInt()}%")
                            },
                        ) {
                            Slider(
                                value = displaySetting.volumeKeyScrollRatio,
                                onValueChange = {
                                    updateDisplaySetting(displaySetting.copy(volumeKeyScrollRatio = it))
                                },
                                valueRange = 0.25f..1.0f,
                                steps = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_tts_settings)) },
                ) {
                    switchItem(
                        checked = displaySetting.ttsOnlyReadQuoted,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(ttsOnlyReadQuoted = it)) },
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_desc))
                        },
                    )
                    switchItem(
                        checked = displaySetting.ttsOnlyReadOutsideBrackets,
                        onCheckedChange = {
                            updateDisplaySetting(displaySetting.copy(ttsOnlyReadOutsideBrackets = it))
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_desc))
                        },
                    )
                    switchItem(
                        checked = displaySetting.autoPlayTTSAfterGeneration,
                        onCheckedChange = {
                            updateDisplaySetting(displaySetting.copy(autoPlayTTSAfterGeneration = it))
                        },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_desc)) },
                    )
                }
            }
        }
    }
}

internal fun parsePasteLongTextThreshold(text: String): Int? {
    val value = text.toIntOrNull() ?: return null
    return value.takeIf { it in 100..10000 }
}

@Composable
private fun appLanguageOptions(): List<Pair<String, String>> =
    listOf(
        "en-US" to "English",
        "zh" to stringResource(R.string.setting_language_zh_cn),
        "zh-Hant" to stringResource(R.string.setting_language_zh_hant),
        "ja" to stringResource(R.string.setting_language_ja),
        "ko-KR" to "한국어",
        "ru" to "Русский",
        "ar" to "العربية",
    )

@Composable
private fun currentAppLanguageLabel(): String {
    val tag = AppLocale.currentTag(LocalContext.current)
    if (tag.isBlank()) return stringResource(R.string.setting_language_system)
    return appLanguageOptions().firstOrNull { it.first.equals(tag, ignoreCase = true) }?.second ?: tag
}

@Composable
private fun LanguagePickerDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val current = AppLocale.currentTag(LocalContext.current)
    val options = listOf("" to "") + appLanguageOptions()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_language_title)) },
        text = {
            LazyColumn {
                items(options.size) { index ->
                    val (tag, label) = options[index]
                    val shown = if (tag.isEmpty()) stringResource(R.string.setting_language_system) else label
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(tag) }
                                .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = if (tag.isEmpty()) current.isBlank() else current.equals(tag, true),
                            onClick = { onPick(tag) },
                        )
                        Text(shown, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
