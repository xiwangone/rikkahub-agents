package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.filterByKeywords
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.catalog.CapabilitySource
import me.rerere.rikkahub.data.ai.catalog.ModelCapabilityOverrides
import me.rerere.rikkahub.data.ai.catalog.ModelCapabilityResolver
import me.rerere.rikkahub.data.ai.catalog.ResolvedCapabilities
import me.rerere.rikkahub.data.ai.mcp.server.LocalMcpServerManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.setting.SettingTextField
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.RikkaDeleteConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 模型能力可视化编辑器。
 *
 * 模型列表展示每个模型的生效能力（输入/输出模态、能力、上下文长度）及来源；
 * 点一个模型即可编辑其本机覆盖条目，保存后写回本机表并立即生效。
 * 覆盖语义与外置表一致：命中条目的非空字段直接采用，空字段不覆盖。
 */
@Composable
fun SettingModelCapabilityEditorPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val overrides = koinInject<ModelCapabilityOverrides>()
    val mcpManager = koinInject<LocalMcpServerManager>()
    val mcpState by mcpManager.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var query by remember { mutableStateOf("") }
    var customMatch by remember { mutableStateOf("") }
    var selectedMatch by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    val models = remember(settings.providers) {
        settings.providers.filter { it.enabled }
            .flatMap { it.models }
            .distinctBy { it.modelId }
            .sortedBy { it.modelId.lowercase() }
    }
    val filteredModels = remember(models, query) {
        if (query.isBlank()) models else models.filterByKeywords(query)
    }
    var resolutions by remember { mutableStateOf<Map<String, ResolvedCapabilities>>(emptyMap()) }
    LaunchedEffect(models, refreshTick) {
        resolutions = withContext(Dispatchers.Default) {
            models.associate { it.modelId to ModelCapabilityResolver.resolve(it.modelId, overrides) }
        }
    }
    val localEntries = remember(refreshTick) { overrides.localEntries() }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_capability_editor_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_model_page_capability_editor_search_hint)) },
                    leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                    singleLine = true,
                )
            }
            item {
                CustomMatchRow(
                    customMatch = customMatch,
                    onCustomMatchChange = { customMatch = it },
                    onEdit = {
                        val match = customMatch.trim()
                        if (match.isNotEmpty()) selectedMatch = match
                    },
                )
            }
            item {
                Text(
                    stringResource(R.string.setting_model_page_capability_editor_models_title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (filteredModels.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.setting_model_page_capability_editor_models_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(filteredModels, key = { it.modelId }) { model ->
                ModelCapabilityRow(
                    model = model,
                    resolved = resolutions[model.modelId],
                    onClick = { selectedMatch = model.modelId },
                )
            }
            val match = selectedMatch
            if (match != null) {
                item {
                    CapabilityEditForm(
                        match = match,
                        overrides = overrides,
                        localEntries = localEntries,
                        refreshTick = refreshTick,
                        onChanged = { refreshTick++ },
                        onClose = { selectedMatch = null },
                    )
                }
            }
            item {
                LocalOverridesSection(
                    localEntries = localEntries,
                    onEdit = { selectedMatch = it },
                    overrides = overrides,
                    onChanged = { refreshTick++ },
                )
            }
            item {
                McpToolsSection(
                    mcpEnabled = settings.localMcpServerEnabled,
                    mcpRunning = mcpState.isRunning,
                    mcpPort = mcpState.port,
                    mcpToolCount = mcpState.toolCount,
                )
            }
        }
    }
}

@Composable
private fun CustomMatchRow(
    customMatch: String,
    onCustomMatchChange: (String) -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingTextField(
            value = customMatch,
            onValueChange = onCustomMatchChange,
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.setting_model_page_capability_editor_custom_match_label)) },
            placeholder = { Text(stringResource(R.string.setting_model_page_capability_editor_custom_match_hint)) },
            singleLine = true,
        )
        TextButton(onClick = onEdit, enabled = customMatch.isNotBlank()) {
            Text(stringResource(R.string.edit))
        }
    }
}

@Composable
private fun ModelCapabilityRow(
    model: Model,
    resolved: ResolvedCapabilities?,
    onClick: () -> Unit,
) {
    CardGroup {
        item(
            onClick = onClick,
            headlineContent = { Text(model.displayName.ifBlank { model.modelId }) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(model.modelId, style = MaterialTheme.typography.bodySmall)
                    if (resolved != null) {
                        CapabilityChips(resolved)
                    }
                }
            },
            trailingContent = {
                overrideBadge(resolved)?.let { badge ->
                    CardGroupValue(badge)
                }
            },
        )
    }
}

/** 有覆盖时显示短来源徽标（本机优先于基线）；无覆盖不显示。 */
@Composable
private fun overrideBadge(resolved: ResolvedCapabilities?): String? {
    if (resolved == null) return null
    val sources = setOf(
        resolved.inputSource,
        resolved.outputSource,
        resolved.abilitiesSource,
        resolved.contextLengthSource,
    )
    return when {
        CapabilitySource.LOCAL in sources ->
            stringResource(R.string.setting_model_page_capability_editor_source_local_short)

        CapabilitySource.BASELINE in sources ->
            stringResource(R.string.setting_model_page_capability_editor_source_baseline_short)

        else -> null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityChips(resolved: ResolvedCapabilities) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(
                R.string.setting_model_page_capability_editor_chip_input,
                modalityLabels(resolved.input),
            ),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            stringResource(
                R.string.setting_model_page_capability_editor_chip_output,
                modalityLabels(resolved.output),
            ),
            style = MaterialTheme.typography.labelMedium,
        )
        if (resolved.abilities.isNotEmpty()) {
            Text(
                abilityLabels(resolved.abilities),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        resolved.contextLength?.let {
            Text(
                stringResource(R.string.setting_model_page_capability_editor_chip_context, it),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun modalityLabel(modality: Modality): String =
    stringResource(
        when (modality) {
            Modality.TEXT -> R.string.setting_model_page_capability_editor_modality_text
            Modality.IMAGE -> R.string.setting_model_page_capability_editor_modality_image
            Modality.VIDEO -> R.string.setting_model_page_capability_editor_modality_video
        },
    )

/**
 * 把一组模态拼成“文本+图像+视频”形式的标签。逐个调 [modalityLabel] 必须走 for 循环：
 * `joinToString { }` 的 transform 不是内联 lambda 上下文，里面不能调 @Composable 函数。
 */
@Composable
private fun modalityLabels(modalities: Set<Modality>): String {
    val labels = ArrayList<String>(modalities.size)
    for (modality in modalities) labels += modalityLabel(modality)
    return labels.joinToString("+")
}

@Composable
private fun abilityLabel(ability: ModelAbility): String =
    stringResource(
        when (ability) {
            ModelAbility.TOOL -> R.string.setting_model_page_capability_editor_ability_tool
            ModelAbility.REASONING -> R.string.setting_model_page_capability_editor_ability_reasoning
        },
    )

/** 把一组能力拼成“工具+推理”形式的标签（与 [modalityLabels] 同理，走 for 循环）。 */
@Composable
private fun abilityLabels(abilities: Set<ModelAbility>): String {
    val labels = ArrayList<String>(abilities.size)
    for (ability in abilities) labels += abilityLabel(ability)
    return labels.joinToString("+")
}

@Composable
private fun sourceLabel(source: CapabilitySource): String =
    stringResource(
        when (source) {
            CapabilitySource.LOCAL -> R.string.setting_model_page_capability_editor_source_local
            CapabilitySource.BASELINE -> R.string.setting_model_page_capability_editor_source_baseline
            CapabilitySource.MERGED -> R.string.setting_model_page_capability_editor_source_merged
            CapabilitySource.DEFAULT -> R.string.setting_model_page_capability_editor_source_default
        },
    )

/** 编辑草稿：每组一个“覆盖”开关；关 = 该项不写入（null），开 = 按控件值写入。 */
private data class EditorDraft(
    val match: String,
    val overrideInput: Boolean,
    val input: Set<Modality>,
    val overrideOutput: Boolean,
    val output: Set<Modality>,
    val overrideAbilities: Boolean,
    val abilities: Set<ModelAbility>,
    val overrideContext: Boolean,
    val contextLength: String,
) {
    companion object {
        fun initial(
            match: String,
            localEntry: ModelCapabilityOverrides.Entry?,
            resolved: ResolvedCapabilities?,
        ): EditorDraft = EditorDraft(
            match = match,
            overrideInput = localEntry?.input != null,
            input = localEntry?.input ?: resolved?.input ?: setOf(Modality.TEXT),
            overrideOutput = localEntry?.output != null,
            output = localEntry?.output ?: resolved?.output ?: setOf(Modality.TEXT),
            overrideAbilities = localEntry?.abilities != null,
            abilities = localEntry?.abilities ?: resolved?.abilities ?: emptySet(),
            overrideContext = localEntry?.contextLength != null,
            contextLength = localEntry?.contextLength?.toString()
                ?: resolved?.contextLength?.toString().orEmpty(),
        )
    }
}

private enum class SaveOutcome { SAVED, REMOVED, NOTHING, FAILED }

@Composable
private fun CapabilityEditForm(
    match: String,
    overrides: ModelCapabilityOverrides,
    localEntries: List<ModelCapabilityOverrides.Entry>,
    refreshTick: Int,
    onChanged: () -> Unit,
    onClose: () -> Unit,
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val resolved = remember(match, refreshTick) { ModelCapabilityResolver.resolve(match, overrides) }
    val localEntry = remember(match, localEntries) {
        localEntries.firstOrNull { it.match.equals(match, ignoreCase = true) }
    }
    var draft by remember(match, refreshTick) {
        mutableStateOf(EditorDraft.initial(match, localEntry, resolved))
    }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val savedText = stringResource(R.string.setting_model_page_capability_editor_saved)
    val saveFailedText = stringResource(R.string.setting_model_page_capability_editor_save_failed)
    val deletedText = stringResource(R.string.setting_model_page_capability_editor_deleted)
    val nothingText = stringResource(R.string.setting_model_page_capability_editor_no_override)
    val matchRequiredText = stringResource(R.string.setting_model_page_capability_editor_match_required)

    fun save() {
        val newMatch = draft.match.trim()
        if (newMatch.isEmpty()) {
            toaster.show(matchRequiredText)
            return
        }
        val entry = ModelCapabilityOverrides.Entry(
            match = newMatch,
            input = if (draft.overrideInput) draft.input.takeIf { it.isNotEmpty() } else null,
            output = if (draft.overrideOutput) draft.output.takeIf { it.isNotEmpty() } else null,
            abilities = if (draft.overrideAbilities) draft.abilities else null,
            contextLength = if (draft.overrideContext) {
                draft.contextLength.toIntOrNull()?.takeIf { it > 0 }
            } else {
                null
            },
        )
        val hadLocal = localEntries.any { it.match.equals(newMatch, ignoreCase = true) }
        val allNull = entry.input == null && entry.output == null &&
            entry.abilities == null && entry.contextLength == null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                when {
                    allNull && hadLocal ->
                        if (overrides.removeLocal(newMatch)) SaveOutcome.REMOVED else SaveOutcome.FAILED

                    allNull -> SaveOutcome.NOTHING
                    else ->
                        if (overrides.upsertLocal(entry)) SaveOutcome.SAVED else SaveOutcome.FAILED
                }
            }
            when (outcome) {
                SaveOutcome.SAVED -> {
                    toaster.show(savedText)
                    onChanged()
                }

                SaveOutcome.REMOVED -> {
                    toaster.show(deletedText)
                    onChanged()
                }

                SaveOutcome.NOTHING -> toaster.show(nothingText)
                SaveOutcome.FAILED -> toaster.show(saveFailedText)
            }
        }
    }

    CardGroup(title = { Text(stringResource(R.string.setting_model_page_capability_editor_edit_title)) }) {
        formItem {
            SettingTextField(
                value = draft.match,
                onValueChange = { draft = draft.copy(match = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setting_model_page_capability_editor_match_label)) },
                singleLine = true,
            )
        }
        capabilityFieldGroups(
            draft = draft,
            onDraftChange = { draft = it },
            resolved = resolved,
        )
        formItem {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (localEntry != null) {
                    OutlinedButton(onClick = { showDeleteDialog = true }) {
                        Text(stringResource(R.string.delete))
                    }
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.cancel))
                }
                Button(onClick = ::save) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }

    RikkaDeleteConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.confirm_delete),
        message = stringResource(R.string.setting_model_page_capability_editor_delete_confirm, match),
        onConfirm = {
            showDeleteDialog = false
            scope.launch {
                val ok = withContext(Dispatchers.IO) { overrides.removeLocal(match) }
                if (ok) {
                    toaster.show(deletedText)
                    onChanged()
                } else {
                    toaster.show(saveFailedText)
                }
            }
        },
        onDismiss = { showDeleteDialog = false },
    )
}

/** 四组能力字段：每组标题行（来源 + 覆盖开关），下方是控件或“跟随系统”。 */
private fun CardGroupScope.capabilityFieldGroups(
    draft: EditorDraft,
    onDraftChange: (EditorDraft) -> Unit,
    resolved: ResolvedCapabilities,
) {
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_model_page_capability_editor_input_label)) },
        supportingContent = { Text(sourceLabel(resolved.inputSource)) },
        trailingContent = {
            Switch(
                checked = draft.overrideInput,
                onCheckedChange = { onDraftChange(draft.copy(overrideInput = it)) },
            )
        },
    ) {
        if (draft.overrideInput) {
            ModalityChips(
                selected = draft.input,
                onChange = { onDraftChange(draft.copy(input = it)) },
            )
        } else {
            Text(stringResource(R.string.setting_model_page_capability_editor_follow_system))
        }
    }
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_model_page_capability_editor_output_label)) },
        supportingContent = { Text(sourceLabel(resolved.outputSource)) },
        trailingContent = {
            Switch(
                checked = draft.overrideOutput,
                onCheckedChange = { onDraftChange(draft.copy(overrideOutput = it)) },
            )
        },
    ) {
        if (draft.overrideOutput) {
            ModalityChips(
                selected = draft.output,
                onChange = { onDraftChange(draft.copy(output = it)) },
            )
        } else {
            Text(stringResource(R.string.setting_model_page_capability_editor_follow_system))
        }
    }
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_model_page_capability_editor_abilities_label)) },
        supportingContent = { Text(sourceLabel(resolved.abilitiesSource)) },
        trailingContent = {
            Switch(
                checked = draft.overrideAbilities,
                onCheckedChange = { onDraftChange(draft.copy(overrideAbilities = it)) },
            )
        },
    ) {
        if (draft.overrideAbilities) {
            AbilitySwitches(
                selected = draft.abilities,
                onChange = { onDraftChange(draft.copy(abilities = it)) },
            )
        } else {
            Text(stringResource(R.string.setting_model_page_capability_editor_follow_system))
        }
    }
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_model_page_capability_editor_context_label)) },
        supportingContent = { Text(sourceLabel(resolved.contextLengthSource)) },
        trailingContent = {
            Switch(
                checked = draft.overrideContext,
                onCheckedChange = { onDraftChange(draft.copy(overrideContext = it)) },
            )
        },
    ) {
        if (draft.overrideContext) {
            ContextLengthField(
                value = draft.contextLength,
                onChange = { onDraftChange(draft.copy(contextLength = it)) },
            )
        } else {
            Text(stringResource(R.string.setting_model_page_capability_editor_follow_system))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModalityChips(
    selected: Set<Modality>,
    onChange: (Set<Modality>) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Modality.entries.forEach { modality ->
            FilterChip(
                selected = modality in selected,
                onClick = {
                    onChange(
                        if (modality in selected) selected - modality else selected + modality,
                    )
                },
                label = { Text(modalityLabel(modality)) },
            )
        }
    }
}

@Composable
private fun AbilitySwitches(
    selected: Set<ModelAbility>,
    onChange: (Set<ModelAbility>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ModelAbility.entries.forEach { ability ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = ability in selected,
                    onCheckedChange = { checked ->
                        onChange(if (checked) selected + ability else selected - ability)
                    },
                )
                Text(abilityLabel(ability))
            }
        }
    }
}

@Composable
private fun ContextLengthField(
    value: String,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(128_000, 200_000, 1_000_000).forEach { preset ->
                TextButton(onClick = { onChange(preset.toString()) }) {
                    Text(preset.toString())
                }
            }
        }
        SettingTextField(
            value = value,
            onValueChange = { raw -> onChange(raw.filter(Char::isDigit)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.setting_model_page_capability_editor_context_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
    }
}

@Composable
private fun LocalOverridesSection(
    localEntries: List<ModelCapabilityOverrides.Entry>,
    onEdit: (String) -> Unit,
    overrides: ModelCapabilityOverrides,
    onChanged: () -> Unit,
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val deleteFailedText = stringResource(R.string.setting_model_page_capability_editor_save_failed)
    val deletedText = stringResource(R.string.setting_model_page_capability_editor_deleted)
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    CardGroup(title = { Text(stringResource(R.string.setting_model_page_capability_editor_local_title)) }) {
        if (localEntries.isEmpty()) {
            item(
                headlineContent = {
                    Text(stringResource(R.string.setting_model_page_capability_editor_local_empty))
                },
            )
        }
        localEntries.forEach { entry ->
            item(
                onClick = { onEdit(entry.match) },
                leadingContent = {
                    CardGroupIcon(HugeIcons.Database02, containerColor = containerColor, contentColor = contentColor)
                },
                headlineContent = { Text(entry.match) },
                supportingContent = { Text(entry.summary()) },
                trailingContent = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { overrides.removeLocal(entry.match) }
                                if (ok) {
                                    toaster.show(deletedText)
                                    onChanged()
                                } else {
                                    toaster.show(deleteFailedText)
                                }
                            }
                        },
                    ) {
                        Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.delete))
                    }
                },
            )
        }
    }
}

@Composable
private fun ModelCapabilityOverrides.Entry.summary(): String {
    val parts = mutableListOf<String>()
    input?.let {
        parts += stringResource(
            R.string.setting_model_page_capability_editor_chip_input,
            modalityLabels(it),
        )
    }
    output?.let {
        parts += stringResource(
            R.string.setting_model_page_capability_editor_chip_output,
            modalityLabels(it),
        )
    }
    abilities?.let { parts += abilityLabels(it) }
    contextLength?.let {
        parts += stringResource(R.string.setting_model_page_capability_editor_chip_context, it)
    }
    return if (parts.isEmpty()) {
        stringResource(R.string.setting_model_page_capability_editor_no_override)
    } else {
        parts.joinToString(" · ")
    }
}

@Composable
private fun McpToolsSection(
    mcpEnabled: Boolean,
    mcpRunning: Boolean,
    mcpPort: Int,
    mcpToolCount: Int,
) {
    val navController = LocalNavController.current
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    CardGroup(title = { Text(stringResource(R.string.setting_model_page_capability_editor_tools_title)) }) {
        item(
            leadingContent = {
                CardGroupIcon(HugeIcons.Tools, containerColor = containerColor, contentColor = contentColor)
            },
            headlineContent = {
                Text(
                    if (mcpEnabled && mcpRunning) {
                        stringResource(
                            R.string.setting_model_page_capability_editor_tools_on,
                            mcpPort,
                            mcpToolCount,
                        )
                    } else {
                        stringResource(R.string.setting_model_page_capability_editor_tools_off)
                    },
                )
            },
            supportingContent = {
                Text(stringResource(R.string.setting_model_page_capability_editor_tools_desc))
            },
        )
        item(
            onClick = { navController.navigate(Screen.SettingMcp) },
            leadingContent = {
                CardGroupIcon(HugeIcons.AiEditing, containerColor = containerColor, contentColor = contentColor)
            },
            headlineContent = { Text(stringResource(R.string.setting_model_page_capability_editor_tools_open)) },
        )
    }
}
