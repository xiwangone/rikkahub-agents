package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.ui.components.setting.SettingTextField
import me.rerere.tts.provider.TTSProviderSetting


@Composable
internal fun XAITTSConfiguration(
    setting: TTSProviderSetting.XAI,
    onValueChange: (TTSProviderSetting) -> Unit,
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) },
    ) {
        SecretRefField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            nameHint = "${setting.name}_API_KEY",
            singleLine = true,
            placeholder = stringResource(R.string.provider_api_key_placeholder_xai),
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) },
    ) {
        SettingTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.x.ai/v1") },
        )
    }

    // Voice ID
    var voiceExpanded by remember { mutableStateOf(false) }
    val voices =
        listOf(
            "eve" to "Eve",
            "ara" to "Ara",
            "rex" to "Rex",
            "sal" to "Sal",
            "leo" to "Leo",
        )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) },
    ) {
        ExposedDropdownMenuBox(
            expanded = voiceExpanded,
            onExpandedChange = { voiceExpanded = !voiceExpanded },
        ) {
            SettingTextField(
                value = setting.voiceId,
                onValueChange = { newVoiceId ->
                    onValueChange(setting.copy(voiceId = newVoiceId))
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded)
                },
            )
            ExposedDropdownMenu(
                expanded = voiceExpanded,
                onDismissRequest = { voiceExpanded = false },
            ) {
                voices.forEach { (voiceId, description) ->
                    DropdownMenuItem(
                        text = { Text(description) },
                        onClick = {
                            voiceExpanded = false
                            onValueChange(setting.copy(voiceId = voiceId))
                        },
                    )
                }
            }
        }
    }

    // Language
    var languageExpanded by remember { mutableStateOf(false) }
    val languages =
        listOf(
            "auto" to "Auto-detect",
            "en" to "English",
            "zh" to "Chinese (Simplified)",
            "ja" to "Japanese",
            "ko" to "Korean",
            "fr" to "French",
            "de" to "German",
            "es-ES" to "Spanish (Spain)",
            "es-MX" to "Spanish (Mexico)",
            "pt-BR" to "Portuguese (Brazil)",
            "pt-PT" to "Portuguese (Portugal)",
            "it" to "Italian",
            "ru" to "Russian",
            "ar-EG" to "Arabic (Egypt)",
            "hi" to "Hindi",
            "tr" to "Turkish",
            "vi" to "Vietnamese",
            "id" to "Indonesian",
            "bn" to "Bengali",
        )

    FormItem(
        label = { Text(stringResource(R.string.tts_language)) },
    ) {
        ExposedDropdownMenuBox(
            expanded = languageExpanded,
            onExpandedChange = { languageExpanded = !languageExpanded },
        ) {
            SettingTextField(
                value = setting.language,
                onValueChange = { newLanguage ->
                    onValueChange(setting.copy(language = newLanguage))
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded)
                },
            )
            ExposedDropdownMenu(
                expanded = languageExpanded,
                onDismissRequest = { languageExpanded = false },
            ) {
                languages.forEach { (code, displayName) ->
                    DropdownMenuItem(
                        text = { Text("$displayName ($code)") },
                        onClick = {
                            languageExpanded = false
                            onValueChange(setting.copy(language = code))
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ElevenLabsTTSConfiguration(
    setting: TTSProviderSetting.ElevenLabs,
    onValueChange: (TTSProviderSetting) -> Unit,
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) },
    ) {
        SecretRefField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            nameHint = "${setting.name}_API_KEY",
            singleLine = true,
            placeholder = stringResource(R.string.provider_api_key_placeholder_elevenlabs),
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) },
    ) {
        SettingTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.elevenlabs.io") },
        )
    }

    // Model
    var modelExpanded by remember { mutableStateOf(false) }
    val models =
        listOf(
            "eleven_multilingual_v2" to "Eleven Multilingual v2",
            "eleven_v3" to "Eleven v3",
            "eleven_flash_v2_5" to "Eleven Flash v2.5",
        )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) },
    ) {
        ExposedDropdownMenuBox(
            expanded = modelExpanded,
            onExpandedChange = { modelExpanded = !modelExpanded },
        ) {
            SettingTextField(
                value = setting.model,
                onValueChange = { newModel ->
                    onValueChange(setting.copy(model = newModel))
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                },
            )
            ExposedDropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false },
            ) {
                models.forEach { (modelId, displayName) ->
                    DropdownMenuItem(
                        text = { Text("$displayName ($modelId)") },
                        onClick = {
                            modelExpanded = false
                            onValueChange(setting.copy(model = modelId))
                        },
                    )
                }
            }
        }
    }

    // Voice ID
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) },
    ) {
        SettingTextField(
            value = setting.voiceId,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("JBFqnCBsd6RMkjVDRZzb") },
        )
    }

    // Stability
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_stability)) },
        description = { Text(stringResource(R.string.setting_tts_page_stability_description)) },
    ) {
        OutlinedNumberInput(
            value = setting.stability,
            onValueChange = { newStability ->
                onValueChange(setting.copy(stability = newStability.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.5",
        )
    }

    // Similarity Boost
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_similarity_boost)) },
        description = { Text(stringResource(R.string.setting_tts_page_similarity_boost_description)) },
    ) {
        OutlinedNumberInput(
            value = setting.similarityBoost,
            onValueChange = { newSimilarityBoost ->
                onValueChange(setting.copy(similarityBoost = newSimilarityBoost.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.75",
        )
    }
}

@Composable
internal fun FishAudioTTSConfiguration(
    setting: TTSProviderSetting.FishAudio,
    onValueChange: (TTSProviderSetting) -> Unit,
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) },
    ) {
        SecretRefField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            nameHint = "${setting.name}_API_KEY",
            singleLine = true,
            placeholder = stringResource(R.string.provider_api_key_placeholder_fish),
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) },
    ) {
        SettingTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.fish.audio") },
        )
    }

    // Model (下拉选择框 + 文本输入框，完全同 ElevenLabs 格式)
    var modelExpanded by remember { mutableStateOf(false) }
    val models =
        listOf(
            "s2.1-pro" to stringResource(R.string.tts_label_1),
            "s2.1-pro-free" to stringResource(R.string.tts_label_2),
            "s2-pro" to "S2-Pro",
            "s1" to "S1",
        )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) },
    ) {
        ExposedDropdownMenuBox(
            expanded = modelExpanded,
            onExpandedChange = { modelExpanded = !modelExpanded },
        ) {
            SettingTextField(
                value = setting.model,
                onValueChange = { newModel ->
                    onValueChange(setting.copy(model = newModel))
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                },
            )
            ExposedDropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false },
            ) {
                models.forEach { (modelId, displayName) ->
                    DropdownMenuItem(
                        text = { Text("$displayName ($modelId)") },
                        onClick = {
                            modelExpanded = false
                            onValueChange(setting.copy(model = modelId))
                        },
                    )
                }
            }
        }
    }

    // Voice ID (reference_id)
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) },
    ) {
        SettingTextField(
            value = setting.referenceId,
            onValueChange = { newReferenceId ->
                onValueChange(setting.copy(referenceId = newReferenceId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("802e3bc2b27e49c2995d23ef70e6ac89") },
        )
    }

    // Temperature
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_temperature)) },
        description = { Text(stringResource(R.string.setting_tts_page_temperature_description)) },
    ) {
        OutlinedNumberInput(
            value = setting.temperature,
            onValueChange = { newTemperature ->
                onValueChange(setting.copy(temperature = newTemperature.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.7",
        )
    }

    // Speed
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.setting_tts_page_fish_audio_speed_description)) },
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                onValueChange(setting.copy(speed = newSpeed.coerceIn(0.5f, 2f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "1.0",
        )
    }
}

@Composable
internal fun StepTTSConfiguration(
    setting: TTSProviderSetting.Step,
    onValueChange: (TTSProviderSetting) -> Unit,
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.tts_stepfun_key_desc)) },
    ) {
        SecretRefField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            nameHint = "${setting.name}_API_KEY",
            singleLine = true,
            placeholder = stringResource(R.string.tts_stepfun_key_hint),
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) },
    ) {
        SettingTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.stepfun.com") },
        )
    }

    // Model
    var modelExpanded by remember { mutableStateOf(false) }
    val models =
        listOf(
            "step-tts-mini" to stringResource(R.string.tts_label_3),
            "step-tts-vivid" to stringResource(R.string.tts_label_4),
            "stepaudio-2.5-tts" to stringResource(R.string.tts_label_5),
            "step-tts-2" to stringResource(R.string.tts_label_6),
        )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) },
    ) {
        ExposedDropdownMenuBox(
            expanded = modelExpanded,
            onExpandedChange = { modelExpanded = !modelExpanded },
        ) {
            SettingTextField(
                value = setting.model,
                onValueChange = { newModel ->
                    onValueChange(setting.copy(model = newModel))
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                },
            )
            ExposedDropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false },
            ) {
                models.forEach { (modelId, description) ->
                    DropdownMenuItem(
                        text = { Text(description) },
                        onClick = {
                            modelExpanded = false
                            onValueChange(setting.copy(model = modelId))
                        },
                    )
                }
            }
        }
    }

    // Voice
    var voiceExpanded by remember { mutableStateOf(false) }
    // 部分常用 voice-id, 完整列表见服务商开发文档
    // https://platform.stepfun.com/docs/zh/guides/developer/tts
    val voices =
        listOf(
            "elegantgentle-female" to stringResource(R.string.tts_label_7),
            "livelybreezy-female" to stringResource(R.string.tts_label_8),
            "energeticconfident-female" to stringResource(R.string.tts_label_9),
            "jingdiannvsheng" to stringResource(R.string.tts_label_10),
            "wenroushunv" to stringResource(R.string.tts_label_11),
            "tianmeinvsheng" to stringResource(R.string.tts_label_12),
            "qingchunshaonv" to stringResource(R.string.tts_label_13),
            "wenrounvsheng" to stringResource(R.string.tts_label_14),
            "ruanmengnvsheng" to stringResource(R.string.tts_label_15),
            "youyanvsheng" to stringResource(R.string.tts_label_16),
            "lengyanyujie" to stringResource(R.string.tts_label_17),
            "shuangkuaijiejie" to stringResource(R.string.tts_label_18),
            "wenjingxuejie" to stringResource(R.string.tts_label_19),
            "linjiajiejie" to stringResource(R.string.tts_label_20),
            "linjiameimei" to stringResource(R.string.tts_label_21),
            "zhixingjiejie" to stringResource(R.string.tts_label_22),
            "cixingnansheng" to stringResource(R.string.tts_label_23),
            "wenrounansheng" to stringResource(R.string.tts_label_24),
            "yuanqinansheng" to stringResource(R.string.tts_label_25),
            "zhengpaiqingnian" to stringResource(R.string.tts_label_26),
            "ruyananshi" to stringResource(R.string.tts_label_27),
            "boyinnansheng" to stringResource(R.string.tts_label_28),
            "shenchennanyin" to stringResource(R.string.tts_label_29),
            "shuangkuainansheng" to stringResource(R.string.tts_label_30),
            "ganliannvsheng" to stringResource(R.string.tts_label_31),
            "qinhenvsheng" to stringResource(R.string.tts_label_32),
            "huolinvsheng" to stringResource(R.string.tts_label_33),
            "jilingshaonv" to stringResource(R.string.tts_label_34),
            "yuanqishaonv" to stringResource(R.string.tts_label_35),
            "wenrougongzi" to stringResource(R.string.tts_label_36),
            "qingniandaxuesheng" to stringResource(R.string.tts_label_37),
        )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) },
    ) {
        ExposedDropdownMenuBox(
            expanded = voiceExpanded,
            onExpandedChange = { voiceExpanded = !voiceExpanded },
        ) {
            SettingTextField(
                value = setting.voice,
                onValueChange = { newVoice ->
                    onValueChange(setting.copy(voice = newVoice))
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded)
                },
            )
            ExposedDropdownMenu(
                expanded = voiceExpanded,
                onDismissRequest = { voiceExpanded = false },
            ) {
                voices.forEach { (voiceId, description) ->
                    DropdownMenuItem(
                        text = { Text(description) },
                        onClick = {
                            voiceExpanded = false
                            onValueChange(setting.copy(voice = voiceId))
                        },
                    )
                }
            }
        }
    }

    // Response Format
    var formatExpanded by remember { mutableStateOf(false) }
    val formats = listOf("mp3", "wav", "pcm", "opus", "flac")

    FormItem(
        label = { Text(stringResource(R.string.tts_response_format)) },
        description = { Text(stringResource(R.string.tts_audio_format_desc)) },
    ) {
        ExposedDropdownMenuBox(
            expanded = formatExpanded,
            onExpandedChange = { formatExpanded = !formatExpanded },
        ) {
            SettingTextField(
                value = setting.responseFormat,
                onValueChange = { newFormat ->
                    onValueChange(setting.copy(responseFormat = newFormat))
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = formatExpanded)
                },
            )
            ExposedDropdownMenu(
                expanded = formatExpanded,
                onDismissRequest = { formatExpanded = false },
            ) {
                formats.forEach { format ->
                    DropdownMenuItem(
                        text = { Text(format) },
                        onClick = {
                            formatExpanded = false
                            onValueChange(setting.copy(responseFormat = format))
                        },
                    )
                }
            }
        }
    }

    // Speed
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.tts_speed_desc)) },
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.5f..2.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed),
        )
    }

    // Volume
    FormItem(
        label = { Text(stringResource(R.string.tts_volume)) },
        description = { Text(stringResource(R.string.tts_volume_desc)) },
    ) {
        OutlinedNumberInput(
            value = setting.volume,
            onValueChange = { newVolume ->
                if (newVolume in 0.1f..2.0f) {
                    onValueChange(setting.copy(volume = newVolume))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.tts_volume),
        )
    }

    // Sample Rate
    var sampleRateExpanded by remember { mutableStateOf(false) }
    val sampleRates = listOf(8000, 16000, 22050, 24000)

    FormItem(
        label = { Text(stringResource(R.string.tts_sample_rate)) },
        description = { Text(stringResource(R.string.tts_sample_rate_desc)) },
    ) {
        ExposedDropdownMenuBox(
            expanded = sampleRateExpanded,
            onExpandedChange = { sampleRateExpanded = !sampleRateExpanded },
        ) {
            SettingTextField(
                value = setting.sampleRate.toString(),
                onValueChange = {},
                readOnly = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = sampleRateExpanded)
                },
            )
            ExposedDropdownMenu(
                expanded = sampleRateExpanded,
                onDismissRequest = { sampleRateExpanded = false },
            ) {
                sampleRates.forEach { rate ->
                    DropdownMenuItem(
                        text = { Text("$rate Hz") },
                        onClick = {
                            sampleRateExpanded = false
                            onValueChange(setting.copy(sampleRate = rate))
                        },
                    )
                }
            }
        }
    }

    // Instruction (仅 stepaudio-2.5-tts 生效)
    FormItem(
        label = { Text(stringResource(R.string.tts_instruction)) },
        description = { Text(stringResource(R.string.tts_global_instruction_desc)) },
    ) {
        SettingTextField(
            value = setting.instruction,
            onValueChange = { newInstruction ->
                // 服务端上限 200 字符, 客户端做一层保护
                if (newInstruction.length <= 200) {
                    onValueChange(setting.copy(instruction = newInstruction))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.tts_global_instruction_placeholder)) },
            minLines = 2,
            maxLines = 4,
        )
    }
}

@Composable
internal fun VolcengineTTSConfiguration(
    setting: TTSProviderSetting.Volcengine,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.tts_volcengine_api_key_desc)) }
    ) {
        SecretRefField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            nameHint = "${setting.name}_API_KEY",
            singleLine = true,
        )
    }
    FormItem(label = { Text(stringResource(R.string.setting_tts_page_base_url)) }) {
        SettingTextField(
            value = setting.baseUrl,
            onValueChange = { onValueChange(setting.copy(baseUrl = it)) },
            placeholder = { Text("https://openspeech.bytedance.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    FormItem(
        label = { Text(stringResource(R.string.tts_volcengine_resource_id)) },
        description = { Text(stringResource(R.string.tts_volcengine_resource_id_desc)) }
    ) {
        SettingTextField(
            value = setting.resourceId,
            onValueChange = { onValueChange(setting.copy(resourceId = it)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    FormItem(
        label = { Text(stringResource(R.string.tts_volcengine_speaker)) },
        description = { Text(stringResource(R.string.tts_volcengine_speaker_desc)) }
    ) {
        SettingTextField(
            value = setting.speaker,
            onValueChange = { onValueChange(setting.copy(speaker = it)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.tts_volcengine_speed_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.speechRate,
            onValueChange = { onValueChange(setting.copy(speechRate = it.coerceIn(-50, 100))) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
