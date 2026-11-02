package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.vault.VaultProviderKeyRefs
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.compose.koinInject

val MediaGenerationProviderSetting.typeName: String
    @Composable get() =
        when (this) {
            is MediaGenerationProviderSetting.OpenAI -> "OpenAI"
            is MediaGenerationProviderSetting.Aliyun -> stringResource(R.string.media_provider_type_aliyun)
            is MediaGenerationProviderSetting.Volcengine -> stringResource(R.string.media_provider_type_volcengine)
            is MediaGenerationProviderSetting.MiniMax -> "MiniMax"
            is MediaGenerationProviderSetting.OpenRouter -> "OpenRouter"
        }

val MediaKind.label: String
    @Composable get() =
        when (this) {
            MediaKind.IMAGE -> stringResource(R.string.media_kind_image)
            MediaKind.VIDEO -> stringResource(R.string.video)
        }

private fun MediaGenerationProviderSetting.defaultBaseUrl(): String =
    when (this) {
        is MediaGenerationProviderSetting.OpenAI -> MediaGenerationProviderSetting.OpenAI().baseUrl
        is MediaGenerationProviderSetting.Aliyun -> MediaGenerationProviderSetting.Aliyun().baseUrl
        is MediaGenerationProviderSetting.Volcengine -> MediaGenerationProviderSetting.Volcengine().baseUrl
        is MediaGenerationProviderSetting.MiniMax -> MediaGenerationProviderSetting.MiniMax().baseUrl
        is MediaGenerationProviderSetting.OpenRouter -> MediaGenerationProviderSetting.OpenRouter().baseUrl
    }

@Composable
private fun ConfigureColumn(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 128.dp),
        content = content,
    )
}

@Composable
fun MediaGenerationProviderBasicConfigure(
    setting: MediaGenerationProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (MediaGenerationProviderSetting) -> Unit,
) {
    val defaultUrl = setting.defaultBaseUrl()
    ConfigureColumn(modifier) {
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.setting_media_page_provider_type)) },
                trailingContent = { CardGroupValue(setting.typeName) },
            )

            formItem(
                headlineContent = { Text(stringResource(R.string.setting_media_page_provider_name)) },
            ) {
                OutlinedTextField(
                    value = setting.name,
                    onValueChange = { onValueChange(setting.copyProvider(name = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            formItem(
                headlineContent = { Text(stringResource(R.string.setting_media_page_api_key)) },
            ) {
                SecretRefField(
                    value = setting.apiKey,
                    onValueChange = { onValueChange(setting.copyProvider(apiKey = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                    nameHint = "${setting.name.ifBlank { "MEDIA" }}_API_KEY",
                    singleLine = true,
                )
            }

            formItem(
                headlineContent = { Text(stringResource(R.string.setting_media_page_base_url)) },
            ) {
                OutlinedTextField(
                    value = setting.baseUrl,
                    onValueChange = { onValueChange(setting.copyProvider(baseUrl = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon =
                        if (setting.baseUrl != defaultUrl) {
                            {
                                IconButton(
                                    onClick = { onValueChange(setting.copyProvider(baseUrl = defaultUrl)) },
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Refresh03,
                                        contentDescription = stringResource(R.string.setting_model_page_reset_to_default),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                )
            }

            if (setting is MediaGenerationProviderSetting.Aliyun) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.setting_media_page_workspace_id)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.setting_media_page_workspace_id_desc,
                                MediaGenerationProviderSetting.Aliyun.WORKSPACE_PLACEHOLDER,
                            ),
                        )
                    },
                ) {
                    OutlinedTextField(
                        value = setting.workspaceId,
                        onValueChange = { onValueChange(setting.copy(workspaceId = it.trim())) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        }
    }
}

@Composable
fun MediaGenerationProviderModelsConfigure(
    setting: MediaGenerationProviderSetting,
    availableModels: List<MediaGenerationModel>,
    showModelPickerSheet: Boolean,
    onShowModelPickerSheetChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onValueChange: (MediaGenerationProviderSetting) -> Unit,
) {
    val models = setting.models
    val kinds = MediaKind.entries.filter { it in setting.supportedKinds }

    fun updateModels(newModels: List<MediaGenerationModel>) {
        onValueChange(setting.copyProvider(models = newModels))
    }

    ConfigureColumn(modifier) {
        CardGroup {
            models.forEach { model ->
                formItem {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (model.modelId.isNotBlank()) {
                            AutoAIIcon(
                                name = model.modelId,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        OutlinedTextField(
                            value = model.modelId,
                            onValueChange = { value ->
                                updateModels(
                                    models.map {
                                        if (it.id == model.id) model.copy(modelId = value.trim()) else it
                                    },
                                )
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.setting_media_page_model_id)) },
                            singleLine = true,
                        )
                        IconButton(onClick = { updateModels(models.filter { it.id != model.id }) }) {
                            Icon(HugeIcons.Delete01, stringResource(R.string.delete))
                        }
                    }
                    if (kinds.size > 1) {
                        ConnectedChoiceButtons(
                            options = kinds,
                            selected = model.kind,
                            onSelect = { kind ->
                                updateModels(
                                    models.map {
                                        if (it.id == model.id) model.copy(kind = kind) else it
                                    },
                                )
                            },
                        ) { kind, selected ->
                            ConnectedChoiceLabel(text = kind.label, selected = selected)
                        }
                    }
                }
            }

            item(
                onClick = { onShowModelPickerSheetChange(true) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_avaliable_models)) },
                supportingContent = { Text(stringResource(R.string.setting_media_page_models_desc)) },
                leadingContent = {
                    CardGroupIcon(
                        HugeIcons.Package01,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                },
                trailingContent = {
                    if (availableModels.isNotEmpty()) {
                        CardGroupValue(availableModels.size.toString())
                    }
                },
            )

            item(
                onClick =
                    if (kinds.isNotEmpty()) {
                        { updateModels(models + MediaGenerationModel(modelId = "", kind = kinds.first())) }
                    } else {
                        null
                    },
                headlineContent = { Text(stringResource(R.string.setting_media_page_add_model)) },
                supportingContent = { Text(stringResource(R.string.setting_media_page_model_id)) },
                leadingContent = {
                    CardGroupIcon(
                        HugeIcons.Add01,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                },
            )
        }
    }

    if (showModelPickerSheet) {
        MediaModelPickerSheet(
            availableModels = availableModels,
            selectedModels = models,
            onModelSelected = { candidate ->
                if (models.none { it.modelId.equals(candidate.modelId, ignoreCase = true) }) {
                    updateModels(models + candidate.copy())
                }
            },
            onModelDeselected = { candidate ->
                updateModels(models.filterNot { it.modelId.equals(candidate.modelId, ignoreCase = true) })
            },
            onAllSelected = { candidates ->
                val toAdd =
                    candidates.filter { c ->
                        models.none { existing -> existing.modelId.equals(c.modelId, ignoreCase = true) }
                    }
                updateModels(models + toAdd.map { it.copy() })
            },
            onAllDeselected = { candidates ->
                updateModels(
                    models.filter { existing ->
                        candidates.none { c -> c.modelId.equals(existing.modelId, ignoreCase = true) }
                    },
                )
            },
            onDismiss = { onShowModelPickerSheetChange(false) },
        )
    }
}

@Composable
internal fun MediaModelPickerButton(
    availableCount: Int,
    onClick: () -> Unit,
) {
    BadgedBox(
        badge = {
            if (availableCount > 0) {
                Badge {
                    Text(availableCount.toString())
                }
            }
        },
    ) {
        IconButton(onClick = onClick) {
            Icon(HugeIcons.Package01, stringResource(R.string.setting_provider_page_avaliable_models))
        }
    }
}

@Composable
private fun MediaModelPickerSheet(
    availableModels: List<MediaGenerationModel>,
    selectedModels: List<MediaGenerationModel>,
    onModelSelected: (MediaGenerationModel) -> Unit,
    onModelDeselected: (MediaGenerationModel) -> Unit,
    onAllSelected: (List<MediaGenerationModel>) -> Unit,
    onAllDeselected: (List<MediaGenerationModel>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
    ) {
        var filterText by remember { mutableStateOf("") }
        val filterKeywords = remember(filterText) { filterText.split(" ").filter { it.isNotBlank() } }
        val filteredModels =
            remember(availableModels, filterKeywords) {
                availableModels.fastFilter { candidate ->
                    if (filterKeywords.isEmpty()) {
                        true
                    } else {
                        filterKeywords.all { kw ->
                            candidate.modelId.contains(kw, ignoreCase = true) ||
                                candidate.displayName.contains(kw, ignoreCase = true)
                        }
                    }
                }
            }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(8.dp)
                    .imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.setting_provider_page_avaliable_models),
                    style = MaterialTheme.typography.titleMedium,
                )

                val unselectedCount =
                    filteredModels.count { candidate ->
                        selectedModels.none { it.modelId.equals(candidate.modelId, ignoreCase = true) }
                    }

                TextButton(
                    onClick = {
                        if (unselectedCount > 0) {
                            onAllSelected(filteredModels)
                        } else {
                            onAllDeselected(filteredModels)
                        }
                    },
                ) {
                    Text(
                        if (unselectedCount > 0) {
                            stringResource(R.string.setting_provider_page_select_all, unselectedCount)
                        } else {
                            stringResource(R.string.setting_provider_page_deselect_models)
                        },
                    )
                }
            }

            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                contentPadding = PaddingValues(8.dp),
            ) {
                itemsIndexed(filteredModels, key = { _, m -> "${m.kind}:${m.modelId}" }) { index, candidate ->
                    Surface(
                        shape = cardGroupItemShape(index, filteredModels.size),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                        ) {
                            AutoAIIcon(
                                candidate.modelId,
                                Modifier.size(30.dp),
                            )
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = candidate.modelId,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Tag(type = TagType.INFO) {
                                        Text(candidate.kind.label)
                                    }
                                    if (candidate.displayName.isNotBlank() && candidate.displayName != candidate.modelId) {
                                        Text(
                                            text = candidate.displayName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            val added = selectedModels.any { it.modelId.equals(candidate.modelId, ignoreCase = true) }
                            FilledTonalIconToggleButton(
                                checked = added,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        onModelSelected(candidate)
                                    } else {
                                        onModelDeselected(candidate)
                                    }
                                },
                            ) {
                                Icon(if (added) HugeIcons.Tick01 else HugeIcons.Add01, null)
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                label = { Text(stringResource(R.string.setting_provider_page_filter_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.setting_provider_page_filter_example)) },
                singleLine = true,
            )
        }
    }
}

@Composable
internal fun rememberMediaAvailableModels(setting: MediaGenerationProviderSetting): List<MediaGenerationModel> {
    val client = koinInject<OkHttpClient>()
    val presets = remember(setting::class) { presetMediaModelsFor(setting) }
    val remoteModels by produceState(emptyList<MediaGenerationModel>(), setting.baseUrl, setting.apiKey, setting::class) {
        value = fetchRemoteMediaModels(client, setting)
    }
    return remember(presets, remoteModels, setting.supportedKinds) {
        val merged = LinkedHashMap<String, MediaGenerationModel>()
        for (m in presets) {
            if (m.kind in setting.supportedKinds) {
                merged[m.modelId.lowercase()] = m
            }
        }
        for (m in remoteModels) {
            if (m.kind in setting.supportedKinds && m.modelId.lowercase() !in merged) {
                merged[m.modelId.lowercase()] = m
            }
        }
        merged.values.toList()
    }
}

internal fun presetMediaModelsFor(setting: MediaGenerationProviderSetting): List<MediaGenerationModel> =
    when (setting) {
        is MediaGenerationProviderSetting.OpenAI ->
            listOf(
                MediaGenerationModel("gpt-image-2", MediaKind.IMAGE, "GPT Image 2"),
                MediaGenerationModel("gpt-image-1", MediaKind.IMAGE, "GPT Image 1"),
                MediaGenerationModel("gpt-image-1-mini", MediaKind.IMAGE, "GPT Image 1 Mini"),
                MediaGenerationModel("dall-e-3", MediaKind.IMAGE, "DALL·E 3"),
                MediaGenerationModel("dall-e-2", MediaKind.IMAGE, "DALL·E 2"),
                MediaGenerationModel("grok-imagine-image", MediaKind.IMAGE, "Grok Imagine Image"),
                MediaGenerationModel("grok-2-image-1212", MediaKind.IMAGE, "Grok 2 Image"),
                MediaGenerationModel("flux-kontext-pro", MediaKind.IMAGE, "FLUX.1 Kontext Pro"),
                MediaGenerationModel("flux-kontext-max", MediaKind.IMAGE, "FLUX.1 Kontext Max"),
                MediaGenerationModel("flux-1.1-pro-ultra", MediaKind.IMAGE, "FLUX 1.1 Pro Ultra"),
                MediaGenerationModel("flux-1.1-pro", MediaKind.IMAGE, "FLUX 1.1 Pro"),
                MediaGenerationModel("flux-dev", MediaKind.IMAGE, "FLUX.1 Dev"),
                MediaGenerationModel("flux-schnell", MediaKind.IMAGE, "FLUX.1 Schnell"),
                MediaGenerationModel("kolors", MediaKind.IMAGE, "Kolors"),
            )

        is MediaGenerationProviderSetting.Aliyun ->
            listOf(
                MediaGenerationModel("wan2.7-image-pro", MediaKind.IMAGE, "Wan 2.7 Image Pro"),
                MediaGenerationModel("wan2.7-image", MediaKind.IMAGE, "Wan 2.7 Image"),
                MediaGenerationModel("wanx2.1-t2i-plus", MediaKind.IMAGE, "Wanx 2.1 T2I Plus"),
                MediaGenerationModel("wanx2.1-t2i-turbo", MediaKind.IMAGE, "Wanx 2.1 T2I Turbo"),
                MediaGenerationModel("wanx2.0-t2i-turbo", MediaKind.IMAGE, "Wanx 2.0 T2I Turbo"),
                MediaGenerationModel("qwen-image", MediaKind.IMAGE, "Qwen Image"),
                MediaGenerationModel("qwen-image-edit", MediaKind.IMAGE, "Qwen Image Edit"),
                MediaGenerationModel("wan3.0-video", MediaKind.VIDEO, "Wan 3.0 Video"),
                MediaGenerationModel("wan2.6-t2v", MediaKind.VIDEO, "Wan 2.6 T2V"),
                MediaGenerationModel("wan2.6-i2v", MediaKind.VIDEO, "Wan 2.6 I2V"),
                MediaGenerationModel("wanx2.1-t2v-plus", MediaKind.VIDEO, "Wanx 2.1 T2V Plus"),
                MediaGenerationModel("wanx2.1-t2v-turbo", MediaKind.VIDEO, "Wanx 2.1 T2V Turbo"),
                MediaGenerationModel("wanx2.1-i2v-plus", MediaKind.VIDEO, "Wanx 2.1 I2V Plus"),
                MediaGenerationModel("wanx2.1-i2v-turbo", MediaKind.VIDEO, "Wanx 2.1 I2V Turbo"),
            )

        is MediaGenerationProviderSetting.Volcengine ->
            listOf(
                MediaGenerationModel("doubao-seedream-5-0-pro-260628", MediaKind.IMAGE, "Seedream 5.0 Pro"),
                MediaGenerationModel("doubao-seedream-5-0", MediaKind.IMAGE, "Seedream 5.0"),
                MediaGenerationModel("doubao-seedream-4-5-251128", MediaKind.IMAGE, "Seedream 4.5"),
                MediaGenerationModel("doubao-seedream-4-0-250828", MediaKind.IMAGE, "Seedream 4.0"),
                MediaGenerationModel("doubao-seedream-3-0-t2i-250415", MediaKind.IMAGE, "Seedream 3.0 T2I"),
                MediaGenerationModel("doubao-seededit-3-0-i2i-250628", MediaKind.IMAGE, "SeedEdit 3.0 I2I"),
                MediaGenerationModel("doubao-seedance-2-0-260128", MediaKind.VIDEO, "Seedance 2.0"),
                MediaGenerationModel("doubao-seedance-1-5-pro-251215", MediaKind.VIDEO, "Seedance 1.5 Pro"),
                MediaGenerationModel("doubao-seedance-1-0-pro-250528", MediaKind.VIDEO, "Seedance 1.0 Pro"),
                MediaGenerationModel("doubao-seedance-1-0-lite-t2v-250428", MediaKind.VIDEO, "Seedance 1.0 Lite T2V"),
                MediaGenerationModel("doubao-seedance-1-0-lite-i2v-250428", MediaKind.VIDEO, "Seedance 1.0 Lite I2V"),
            )

        is MediaGenerationProviderSetting.MiniMax ->
            listOf(
                MediaGenerationModel("MiniMax-H3", MediaKind.VIDEO, "MiniMax H3"),
                MediaGenerationModel("MiniMax-Hailuo-02", MediaKind.VIDEO, "MiniMax Hailuo 02"),
                MediaGenerationModel("T2V-01-Director", MediaKind.VIDEO, "T2V-01 Director"),
                MediaGenerationModel("T2V-01", MediaKind.VIDEO, "T2V-01"),
                MediaGenerationModel("I2V-01-Director", MediaKind.VIDEO, "I2V-01 Director"),
                MediaGenerationModel("I2V-01-live", MediaKind.VIDEO, "I2V-01 Live"),
                MediaGenerationModel("I2V-01", MediaKind.VIDEO, "I2V-01"),
                MediaGenerationModel("S2V-01", MediaKind.VIDEO, "S2V-01"),
            )

        is MediaGenerationProviderSetting.OpenRouter ->
            listOf(
                MediaGenerationModel("openai/gpt-image-2", MediaKind.IMAGE, "OpenAI GPT Image 2"),
                MediaGenerationModel("openai/gpt-image-1", MediaKind.IMAGE, "OpenAI GPT Image 1"),
                MediaGenerationModel("google/gemini-2.5-flash-image-preview", MediaKind.IMAGE, "Gemini 2.5 Flash Image"),
                MediaGenerationModel("black-forest-labs/flux.1-kontext-pro", MediaKind.IMAGE, "FLUX.1 Kontext Pro"),
                MediaGenerationModel("black-forest-labs/flux.1-kontext-max", MediaKind.IMAGE, "FLUX.1 Kontext Max"),
                MediaGenerationModel("black-forest-labs/flux-1.1-pro-ultra", MediaKind.IMAGE, "FLUX 1.1 Pro Ultra"),
                MediaGenerationModel("black-forest-labs/flux-1.1-pro", MediaKind.IMAGE, "FLUX 1.1 Pro"),
                MediaGenerationModel("ideogram/ideogram-v3", MediaKind.IMAGE, "Ideogram V3"),
                MediaGenerationModel("recraft-ai/recraft-v3", MediaKind.IMAGE, "Recraft V3"),
                MediaGenerationModel("google/veo-3.1", MediaKind.VIDEO, "Google Veo 3.1"),
                MediaGenerationModel("google/veo-3", MediaKind.VIDEO, "Google Veo 3"),
                MediaGenerationModel("google/veo-2", MediaKind.VIDEO, "Google Veo 2"),
                MediaGenerationModel("openai/sora-2", MediaKind.VIDEO, "OpenAI Sora 2"),
                MediaGenerationModel("klingai/kling-v2.1-master", MediaKind.VIDEO, "Kling V2.1 Master"),
                MediaGenerationModel("minimax/hailuo-02", MediaKind.VIDEO, "MiniMax Hailuo 02"),
                MediaGenerationModel("luma/ray-2", MediaKind.VIDEO, "Luma Ray 2"),
            )
    }

private val mediaModelsJson = Json { ignoreUnknownKeys = true }
private val imageModelKeywords =
    listOf("image", "dall-e", "flux", "sdxl", "stable-diffusion", "seedream", "seededit", "wanx", "wan2", "ideogram", "recraft", "kolors", "imagen", "midjourney")
private val videoModelKeywords =
    listOf("video", "sora", "veo", "seedance", "wan3", "t2v", "i2v", "s2v", "hailuo", "kling", "luma", "runway", "pika", "cogvideo", "hunyuan-video", "vidu")

private fun resolveRemoteBaseUrl(setting: MediaGenerationProviderSetting): String? {
    val base = setting.baseUrl.trimEnd('/')
    if (base.isBlank()) return null
    if (setting is MediaGenerationProviderSetting.Aliyun) {
        val placeholder = MediaGenerationProviderSetting.Aliyun.WORKSPACE_PLACEHOLDER
        if (placeholder in base && setting.workspaceId.isBlank()) return null
        return base.replace(placeholder, setting.workspaceId.trim())
    }
    return base
}

private fun parseRemoteMediaModel(
    elem: kotlinx.serialization.json.JsonElement,
    supportedKinds: Set<MediaKind>,
): MediaGenerationModel? {
    val obj = elem as? JsonObject ?: return null
    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (id.isEmpty()) return null
    val displayName = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val lower = id.lowercase()
    val kind =
        when {
            videoModelKeywords.any(lower::contains) && MediaKind.VIDEO in supportedKinds -> MediaKind.VIDEO
            imageModelKeywords.any(lower::contains) && MediaKind.IMAGE in supportedKinds -> MediaKind.IMAGE
            else -> return null
        }
    return MediaGenerationModel(modelId = id, kind = kind, displayName = displayName)
}

private suspend fun fetchRemoteMediaModels(
    client: OkHttpClient,
    setting: MediaGenerationProviderSetting,
): List<MediaGenerationModel> =
    withContext(Dispatchers.IO) {
        val resolvedBase = resolveRemoteBaseUrl(setting) ?: return@withContext emptyList()
        val resolvedKey = VaultProviderKeyRefs.resolveValue(setting.apiKey).trim()
        if (setting !is MediaGenerationProviderSetting.OpenRouter && resolvedKey.isBlank()) {
            return@withContext emptyList()
        }
        runCatching {
            val reqBuilder = Request.Builder().url("$resolvedBase/models").get()
            if (resolvedKey.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $resolvedKey")
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val root = mediaModelsJson.parseToJsonElement(resp.body.string()).jsonObject
                val data = root["data"] as? JsonArray ?: return@use emptyList()
                data.mapNotNull { elem -> parseRemoteMediaModel(elem, setting.supportedKinds) }
            }
        }.getOrDefault(emptyList())
    }
