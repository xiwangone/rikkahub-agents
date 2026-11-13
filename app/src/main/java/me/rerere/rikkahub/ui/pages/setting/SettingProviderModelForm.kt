package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.filterByKeywords
import me.rerere.ai.registry.ModelRegistry
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ModelAbilityTag
import me.rerere.rikkahub.ui.components.ai.ModelModalityTag
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.ConnectedMultiChoiceButtons
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.components.setting.SettingTextField
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomBodies
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomHeaders

private enum class ModelFormTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    BASIC(HugeIcons.Package01, R.string.setting_provider_page_basic_settings),
    ADVANCED(HugeIcons.Settings03, R.string.setting_provider_page_advanced_settings),
    BUILT_IN_TOOLS(HugeIcons.Tools, R.string.setting_page_built_in_tools);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
internal fun ModelSettingsForm(
    model: Model,
    onModelChange: (Model) -> Unit,
    isEdit: Boolean,
    parentProvider: ProviderSetting? = null,
    // 点过提交才亮字段错误：刚打开表单必填项就是空的，没点确认就标红太吵。
    showErrors: Boolean = false,
) {
    val pagerState = rememberPagerState { ModelFormTab.entries.size }

    fun setModelId(id: String) {
        val inputModality = ModelRegistry.MODEL_INPUT_MODALITIES.getData(id)
        val outputModality = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id)
        val abilities = ModelRegistry.MODEL_ABILITIES.getData(id)
        onModelChange(
            model.copy(
                modelId = id,
                displayName = id,
                inputModalities = inputModality,
                outputModalities = outputModality,
                abilities = abilities,
            ),
        )
    }

    Column {
        PagerTabs(
            pagerState = pagerState,
            tabs = ModelFormTab.entries,
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            when (ModelFormTab.entries[page]) {
                ModelFormTab.BASIC -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(vertical = 16.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        SettingTextField(
                            value = model.modelId,
                            onValueChange = {
                                if (!isEdit) {
                                    setModelId(it.trim())
                                }
                            },
                            label = { Text(stringResource(R.string.setting_provider_page_model_id)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                if (!isEdit) {
                                    Text(stringResource(R.string.setting_provider_page_model_id_placeholder))
                                }
                            },
                            enabled = !isEdit,
                            isError = showErrors && model.modelId.isBlank(),
                            supportingText = {
                                if (showErrors && model.modelId.isBlank()) {
                                    Text(stringResource(R.string.setting_provider_model_form_model_id_required), color = MaterialTheme.colorScheme.error)
                                }
                            },
                        )

                        SettingTextField(
                            value = model.displayName,
                            onValueChange = {
                                onModelChange(model.copy(displayName = it))
                            },
                            label = {
                                Text(
                                    stringResource(
                                        if (isEdit) R.string.setting_provider_page_model_name else R.string.setting_provider_page_model_display_name,
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                if (!isEdit) {
                                    Text(stringResource(R.string.setting_provider_page_model_display_name_placeholder))
                                }
                            },
                            isError = showErrors && model.displayName.isBlank(),
                            supportingText = {
                                if (showErrors && model.displayName.isBlank()) {
                                    Text(stringResource(R.string.setting_provider_model_form_name_required), color = MaterialTheme.colorScheme.error)
                                }
                            },
                        )

                        ModelTypeSelector(
                            selectedType = model.type,
                            onTypeSelected = {
                                onModelChange(model.copy(type = it))
                            },
                        )

                        ModelModalitySelector(
                            model = model,
                            inputModalities = model.inputModalities,
                            onUpdateInputModalities = {
                                onModelChange(model.copy(inputModalities = it))
                            },
                            outputModalities = model.outputModalities,
                            onUpdateOutputModalities = {
                                onModelChange(model.copy(outputModalities = it))
                            },
                        )

                        if (model.type == ModelType.CHAT) {
                            ModalAbilitySelector(
                                abilities = model.abilities,
                                onUpdateAbilities = {
                                    onModelChange(model.copy(abilities = it))
                                },
                            )
                            ModelContextWindowEditor(
                                contextLength = model.contextLength,
                                onChange = { onModelChange(model.copy(contextLength = it)) },
                            )
                        }
                    }
                }

                ModelFormTab.ADVANCED -> {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ProviderOverrideSettings(
                            providerOverride = model.providerOverwrite,
                            onUpdateProviderOverride = { providerOverride ->
                                onModelChange(model.copy(providerOverwrite = providerOverride))
                            },
                            parentProvider = parentProvider,
                        )

                        CustomHeaders(
                            headers = model.customHeaders,
                            onUpdate = { headers ->
                                onModelChange(model.copy(customHeaders = headers))
                            },
                        )

                        CustomBodies(
                            customBodies = model.customBodies,
                            onUpdate = { bodies ->
                                onModelChange(model.copy(customBodies = bodies))
                            },
                        )
                    }
                }

                ModelFormTab.BUILT_IN_TOOLS -> {
                    BuiltInToolsSettings(
                        tools = model.tools,
                        onUpdateTools = { tools ->
                            onModelChange(model.copy(tools = tools))
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun AddModelButton(
    models: List<Model>,
    loading: Boolean = false,
    error: String? = null,
    onRefresh: () -> Unit = {},
    selectedModels: List<Model>,
    expanded: Boolean,
    onAddModel: (Model) -> Unit,
    onRemoveModel: (Model) -> Unit,
    parentProvider: ProviderSetting,
    onUpdateProvider: (ProviderSetting) -> Unit,
) {
    val dialogState = useEditState<Model> {
        onAddModel(it.copy(displayName = it.displayName.trim()))
    }
    val scope = rememberCoroutineScope()

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelPicker(
            models = models,
            loading = loading,
            error = error,
            onRefresh = onRefresh,
            selectedModels = selectedModels,
            onModelSelected = { model ->
                onAddModel(model.enrichCapabilities())
            },
            onModelDeselected = { model ->
                onRemoveModel(model)
            },
            onAllModelSelected = {
                onUpdateProvider(
                    parentProvider.copyProvider(
                        models =
                            parentProvider.models +
                                it
                                    .filter { model ->
                                        parentProvider.models.none { existing -> existing.modelId == model.modelId }
                                    }.map { model -> model.enrichCapabilities() },
                    ),
                )
            },
            onAllModelDeselected = { filteredModels ->
                onUpdateProvider(
                    parentProvider.copyProvider(
                        models =
                            parentProvider.models.filter { model ->
                                filteredModels.none { filtered -> filtered.modelId == model.modelId }
                            },
                    ),
                )
            },
        )

        Button(
            onClick = {
                dialogState.open(Model())
            },
        ) {
            Row(
                modifier = Modifier,
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    HugeIcons.Add01,
                    contentDescription = stringResource(R.string.setting_provider_page_add_model),
                )
                AnimatedVisibility(expanded) {
                    Row {
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.setting_provider_page_add_new_model))
                    }
                }
            }
        }
    }

    if (dialogState.isEditing) {
        dialogState.currentState?.let { modelState ->
            val sheetState =
                rememberBottomSheetState(
                    initialValue = SheetValue.Hidden,
                    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                )
            // 提交尝试过才显示字段错误：刚打开弹层两个必填项都是空的，没点确认就标红太吵；
            // 点确认时校验不通过就亮出错误，告诉用户缺什么。
            var submitAttempted by remember { mutableStateOf(false) }
            ModalBottomSheet(
                onDismissRequest = {
                    dialogState.dismiss()
                },
                sheetState = sheetState,
                sheetGesturesEnabled = false,
                dragHandle = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                dialogState.dismiss()
                            }
                        },
                    ) {
                        Icon(HugeIcons.ArrowDown01, null)
                    }
                },
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.95f)
                            .padding(16.dp)
                            .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_add_model),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                    ) {
                        ModelSettingsForm(
                            model = modelState,
                            onModelChange = { dialogState.currentState = it },
                            isEdit = false,
                            parentProvider = parentProvider,
                            showErrors = submitAttempted,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(
                            onClick = {
                                dialogState.dismiss()
                            },
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = {
                                if (modelState.modelId.isNotBlank() && modelState.displayName.isNotBlank()) {
                                    dialogState.confirm()
                                } else {
                                    // 校验失败不再静默：亮出字段错误，用户知道缺什么。
                                    submitAttempted = true
                                }
                            },
                        ) {
                            Text(stringResource(R.string.setting_provider_page_add))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelPicker(
    models: List<Model>,
    loading: Boolean = false,
    error: String? = null,
    onRefresh: () -> Unit = {},
    selectedModels: List<Model>,
    onModelSelected: (Model) -> Unit,
    onModelDeselected: (Model) -> Unit,
    onAllModelSelected: (List<Model>) -> Unit,
    onAllModelDeselected: (List<Model>) -> Unit,
) {
    var showModal by remember { mutableStateOf(false) }
    if (showModal) {
        ModalBottomSheet(
            onDismissRequest = { showModal = false },
            sheetState =
                rememberBottomSheetState(
                    initialValue = SheetValue.Hidden,
                    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                ),
        ) {
            var filterText by remember { mutableStateOf("") }
            val filteredModels = models.filterByKeywords(filterText)
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
                        modifier = Modifier.weight(1f),
                    )

                    IconButton(
                        onClick = onRefresh,
                        enabled = !loading,
                    ) {
                        Icon(HugeIcons.Refresh01, contentDescription = stringResource(R.string.setting_provider_model_list_refresh))
                    }

                    val unselectedCount =
                        filteredModels.count { model ->
                            selectedModels.none { it.modelId == model.modelId }
                        }

                    TextButton(
                        onClick = {
                            if (unselectedCount > 0) {
                                onAllModelSelected(filteredModels)
                            } else {
                                onAllModelDeselected(filteredModels)
                            }
                        },
                    ) {
                        Text(
                            if (unselectedCount > 0) {
                                stringResource(
                                    R.string.setting_provider_page_select_all,
                                    unselectedCount,
                                )
                            } else {
                                stringResource(R.string.setting_provider_page_deselect_models)
                            },
                        )
                    }
                }

                if (loading && filteredModels.isNotEmpty()) {
                    // 已有列表时手动刷新：顶上只显示一条波浪进度线，列表保留可见；
                    // 首次拉取（列表为空）时仍走下面的全区转圈。
                    LinearWavyProgressIndicator(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                    )
                }

                error?.let { message ->
                    // 拉取失败时保留上次拿到的列表，只在顶上显示原因；重试用标题栏的刷新按钮。
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                if (loading && filteredModels.isEmpty()) {
                    // 远程模型列表还在拉取：先转圈，别先给一个空列表；
                    // 拉完确实为空时再显示空态。
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (filteredModels.isEmpty()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.setting_provider_page_no_models),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                    itemsIndexed(filteredModels, key = { _, item -> item.id }) { index, it ->
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
                                    it.modelId,
                                    Modifier.size(30.dp),
                                )
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = it.modelId,
                                        style = MaterialTheme.typography.titleSmall,
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        val modelMeta = remember(it) { it.enrichCapabilities() }
                                        ModelModalityTag(
                                            model = modelMeta,
                                        )
                                        ModelAbilityTag(
                                            model = modelMeta,
                                        )
                                    }
                                }
                                val added = selectedModels.any { model -> model.modelId == it.modelId }
                                FilledTonalIconToggleButton(
                                    checked = added,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            onModelSelected(it)
                                        } else {
                                            onModelDeselected(
                                                selectedModels.firstOrNull { model -> model.modelId == it.modelId }
                                                    ?: it,
                                            )
                                        }
                                    },
                                ) {
                                    Icon(if (added) HugeIcons.Tick01 else HugeIcons.Add01, null)
                                }
                            }
                        }
                    }
                    }
                }
                SettingTextField(
                    value = filterText,
                    onValueChange = {
                        filterText = it
                    },
                    label = { Text(stringResource(R.string.setting_provider_page_filter_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.setting_provider_page_filter_example))
                    },
                    singleLine = true,
                )
            }
        }
    }
    BadgedBox(
        badge = {
            if (models.isNotEmpty()) {
                Badge {
                    Text(models.size.toString())
                }
            }
        },
    ) {
        IconButton(
            onClick = {
                showModal = true
            },
        ) {
            Icon(HugeIcons.Package01, null)
        }
    }
}

@Composable
private fun ModelTypeSelector(
    selectedType: ModelType,
    onTypeSelected: (ModelType) -> Unit,
) {
    Text(
        stringResource(R.string.setting_provider_page_model_type),
        style = MaterialTheme.typography.titleSmall,
    )
    ConnectedChoiceButtons(
        options = ModelType.entries,
        selected = selectedType,
        onSelect = onTypeSelected,
    ) { type, selected ->
        ConnectedChoiceLabel(
            text =
                stringResource(
                    when (type) {
                        ModelType.CHAT -> R.string.setting_provider_page_chat_model
                        ModelType.EMBEDDING -> R.string.setting_provider_page_embedding_model
                        ModelType.IMAGE -> R.string.setting_provider_page_image_model
                    },
                ),
            selected = selected,
        )
    }
}

@Composable
private fun ModelModalitySelector(
    model: Model,
    inputModalities: List<Modality>,
    onUpdateInputModalities: (List<Modality>) -> Unit,
    outputModalities: List<Modality>,
    onUpdateOutputModalities: (List<Modality>) -> Unit,
) {
    if (model.type == ModelType.CHAT) {
        Text(
            stringResource(R.string.setting_provider_page_input_modality),
            style = MaterialTheme.typography.titleSmall,
        )
        ConnectedMultiChoiceButtons(
            options = Modality.entries,
            selected = inputModalities,
            onToggle = { modality, checked ->
                onUpdateInputModalities(if (checked) inputModalities + modality else inputModalities - modality)
            },
        ) { modality, selected ->
            ConnectedChoiceLabel(
                text =
                    stringResource(
                        when (modality) {
                            Modality.TEXT -> R.string.setting_provider_page_text
                            Modality.IMAGE -> R.string.setting_provider_page_image
                        },
                    ),
                selected = selected,
                icon = HugeIcons.Tick01,
            )
        }

        Text(
            stringResource(R.string.setting_provider_page_output_modality),
            style = MaterialTheme.typography.titleSmall,
        )
        ConnectedMultiChoiceButtons(
            options = Modality.entries,
            selected = outputModalities,
            onToggle = { modality, checked ->
                onUpdateOutputModalities(if (checked) outputModalities + modality else outputModalities - modality)
            },
        ) { modality, selected ->
            ConnectedChoiceLabel(
                text =
                    stringResource(
                        when (modality) {
                            Modality.TEXT -> R.string.setting_provider_page_text
                            Modality.IMAGE -> R.string.setting_provider_page_image
                        },
                    ),
                selected = selected,
                icon = HugeIcons.Tick01,
            )
        }
    }
}

@Composable
private fun ModelContextWindowEditor(
    contextLength: Int?,
    onChange: (Int?) -> Unit,
) {
    var customValue by remember(contextLength) { mutableStateOf(contextLength?.toString().orEmpty()) }
    Text(
        stringResource(R.string.setting_provider_page_context_window_title),
        style = MaterialTheme.typography.titleSmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(128_000, 200_000, 1_000_000).forEach { preset ->
            TextButton(onClick = { customValue = preset.toString(); onChange(preset) }) { Text(preset.toString()) }
        }
        TextButton(onClick = { customValue = ""; onChange(null) }) {
            Text(stringResource(R.string.setting_provider_page_context_window_unknown))
        }
    }
    SettingTextField(
        value = customValue,
        onValueChange = { raw ->
            customValue = raw.filter(Char::isDigit)
            onChange(customValue.toIntOrNull()?.takeIf { it > 0 })
        },
        label = { Text(stringResource(R.string.setting_provider_page_context_window_custom)) },
        singleLine = true,
    )
}

@Composable
fun ModalAbilitySelector(
    abilities: List<ModelAbility>,
    onUpdateAbilities: (List<ModelAbility>) -> Unit,
) {
    Text(
        stringResource(R.string.setting_provider_page_abilities),
        style = MaterialTheme.typography.titleSmall,
    )
    ConnectedMultiChoiceButtons(
        options = ModelAbility.entries,
        selected = abilities,
        onToggle = { ability, checked ->
            onUpdateAbilities(if (checked) abilities + ability else abilities - ability)
        },
    ) { ability, selected ->
        ConnectedChoiceLabel(
            text =
                stringResource(
                    when (ability) {
                        ModelAbility.TOOL -> R.string.setting_provider_page_tool
                        ModelAbility.REASONING -> R.string.setting_provider_page_reasoning
                    },
                ),
            selected = selected,
            icon = HugeIcons.Tick01,
        )
    }
}

private fun Model.enrichCapabilities(): Model =
    if (supportedParameters.isNotEmpty()) {
        this
    } else {
        copy(
            inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId),
            outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId),
            abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId),
        )
    }
