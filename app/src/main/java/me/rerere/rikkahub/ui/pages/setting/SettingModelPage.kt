package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.ui.context.rememberSafeLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.BubbleChat
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Heading
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.ScanImage
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.catalog.ModelCapabilityOverrides
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.ModelListState
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private enum class ModelSettingTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    MODEL(HugeIcons.AiBrain01, R.string.setting_model_page_tab_model),
    PROMPT(HugeIcons.AiEditing, R.string.setting_model_page_tab_prompt);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { ModelSettingTab.entries.size }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = ModelSettingTab.entries,
                modifier = Modifier.padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            val contentPadding =
                PaddingValues(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                )
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            ) { page ->
                when (ModelSettingTab.entries[page]) {
                    ModelSettingTab.MODEL ->
                        ModelSettingsPage(
                            settings = settings,
                            vm = vm,
                            contentPadding = contentPadding,
                        )

                    ModelSettingTab.PROMPT ->
                        PromptSettingsPage(
                            settings = settings,
                            vm = vm,
                            contentPadding = contentPadding,
                        )
                }
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(
    settings: Settings,
    vm: SettingVM,
    contentPadding: PaddingValues,
) {
    val chatModel =
        rememberModelListState(
            modelId = settings.chatModelId,
            providers = settings.providers,
            type = ModelType.CHAT,
        )
    val fastModel =
        rememberModelListState(
            modelId = settings.fastModelId,
            providers = settings.providers,
            type = ModelType.CHAT,
        )
    val titleModel =
        rememberModelListState(
            modelId = settings.titleModelId,
            providers = settings.providers,
            type = ModelType.CHAT,
        )
    val suggestionModel =
        rememberModelListState(
            modelId = settings.suggestionModelId,
            providers = settings.providers,
            type = ModelType.CHAT,
        )
    val translateModel =
        rememberModelListState(
            modelId = settings.translateModeId,
            providers = settings.providers,
            type = ModelType.CHAT,
        )
    val ocrModel =
        rememberModelListState(
            modelId = settings.ocrModelId,
            providers = settings.providers,
            type = ModelType.CHAT,
        )
    val compressModel =
        rememberModelListState(
            modelId = settings.compressModelId,
            providers = settings.providers,
            type = ModelType.CHAT,
        )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            val containerColor = MaterialTheme.colorScheme.primaryContainer
            val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            CardGroup {
                modelItem(
                    icon = HugeIcons.BubbleChat,
                    title = R.string.setting_model_page_chat_model,
                    description = R.string.setting_model_page_chat_model_desc,
                    state = chatModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
            }
        }
        item {
            val containerColor = MaterialTheme.colorScheme.tertiaryContainer
            val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            CardGroup {
                modelItem(
                    icon = HugeIcons.Zap,
                    title = R.string.setting_model_page_fast_model,
                    description = R.string.setting_model_page_fast_model_desc,
                    state = fastModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
                modelItem(
                    icon = HugeIcons.Heading,
                    title = R.string.setting_model_page_title_model,
                    description = R.string.setting_model_page_title_model_desc,
                    state = titleModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                    onClear = { vm.updateSettings { current -> current.copy(titleModelId = null) } },
                )
                switchItem(
                    checked = settings.enableSuggestion,
                    onCheckedChange = {
                        vm.updateSettings { current -> current.copy(enableSuggestion = it) }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_model_page_enable_suggestion)) },
                )
                if (settings.enableSuggestion) {
                    modelItem(
                        icon = HugeIcons.Idea01,
                        title = R.string.setting_model_page_suggestion_model,
                        description = R.string.setting_model_page_suggestion_model_desc,
                        state = suggestionModel,
                        iconContainerColor = containerColor,
                        iconContentColor = contentColor,
                        onClear = { vm.updateSettings { current -> current.copy(suggestionModelId = null) } },
                    )
                }
            }
        }
        item {
            val containerColor = MaterialTheme.colorScheme.secondaryContainer
            val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            CardGroup {
                modelItem(
                    icon = HugeIcons.Translate,
                    title = R.string.setting_model_page_translate_model,
                    description = R.string.setting_model_page_translate_model_desc,
                    state = translateModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
                modelItem(
                    icon = HugeIcons.ScanImage,
                    title = R.string.setting_model_page_ocr_model,
                    description = R.string.setting_model_page_ocr_model_desc,
                    state = ocrModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
                modelItem(
                    icon = HugeIcons.Package01,
                    title = R.string.setting_model_page_compress_model,
                    description = R.string.setting_model_page_compress_model_desc,
                    state = compressModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
            }
        }
        item {
            CapabilityTableSettingItem()
        }
    }

    ModelListSheet(state = chatModel) {
        vm.updateSettings { current -> current.copy(chatModelId = it.id) }
    }
    ModelListSheet(state = fastModel) {
        vm.updateSettings { current -> current.copy(fastModelId = it.id) }
    }
    ModelListSheet(state = titleModel) {
        vm.updateSettings { current -> current.copy(titleModelId = it.id) }
    }
    ModelListSheet(state = suggestionModel) {
        vm.updateSettings { current -> current.copy(suggestionModelId = it.id) }
    }
    ModelListSheet(state = translateModel) {
        vm.updateSettings { current -> current.copy(translateModeId = it.id) }
    }
    ModelListSheet(state = ocrModel) {
        vm.updateSettings { current -> current.copy(ocrModelId = it.id) }
    }
    ModelListSheet(state = compressModel) {
        vm.updateSettings { current -> current.copy(compressModelId = it.id) }
    }
}

private fun CardGroupScope.modelItem(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes description: Int,
    state: ModelListState,
    iconContainerColor: Color,
    iconContentColor: Color,
    @StringRes placeholder: Int = R.string.model_list_select_model,
    onClear: (() -> Unit)? = null,
) = formItem(
    headlineContent = { Text(stringResource(title)) },
    supportingContent = { Text(stringResource(description)) },
    leadingContent = {
        CardGroupIcon(icon, containerColor = iconContainerColor, contentColor = iconContentColor)
    },
) {
    ModelField(
        model = state.currentModel,
        onClick = { state.open() },
        placeholder = stringResource(placeholder),
        onClear = onClear,
    )
}

@Composable
private fun CapabilityTableSettingItem() {
    val overrides = koinInject<ModelCapabilityOverrides>()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val importedFormat = stringResource(R.string.setting_model_page_capability_imported)
    val importFailed = stringResource(R.string.setting_model_page_capability_import_failed)
    val exported = stringResource(R.string.setting_model_page_capability_exported)
    val exportFailed = stringResource(R.string.setting_model_page_capability_export_failed)
    val cleared = stringResource(R.string.setting_model_page_capability_cleared)
    val clearFailed = stringResource(R.string.setting_model_page_capability_clear_failed)
    var localCount by remember { mutableStateOf(overrides.localEntryCount()) }
    var baselineCount by remember { mutableStateOf(overrides.baselineEntryCount()) }

    val importLauncher =
        rememberSafeLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberSafeLauncherForActivityResult
            scope.launch {
                val text =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        }.getOrNull()
                    }
                val ok = text != null && withContext(Dispatchers.IO) { overrides.importLocal(text) }
                if (ok) {
                    localCount = overrides.localEntryCount()
                    baselineCount = overrides.baselineEntryCount()
                }
                toaster.show(if (ok) importedFormat.format(localCount) else importFailed)
            }
        }
    val exportLauncher =
        rememberSafeLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberSafeLauncherForActivityResult
            val text = overrides.exportBaselineText().orEmpty()
            scope.launch {
                val ok =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                            true
                        }.getOrDefault(false)
                    }
                toaster.show(if (ok) exported else exportFailed)
            }
        }

    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    CardGroup(title = { Text(stringResource(R.string.setting_model_page_capability_table)) }) {
        item(
            leadingContent = { CardGroupIcon(HugeIcons.Database02, containerColor = containerColor, contentColor = contentColor) },
            headlineContent = { Text(stringResource(R.string.setting_model_page_capability_table)) },
            supportingContent = {
                Text(
                    stringResource(
                        R.string.setting_model_page_capability_table_desc,
                        localCount,
                        baselineCount,
                    ),
                )
            },
        )
        item(
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            leadingContent = { CardGroupIcon(HugeIcons.FileImport, containerColor = containerColor, contentColor = contentColor) },
            headlineContent = { Text(stringResource(R.string.setting_model_page_capability_import)) },
        )
        item(
            onClick = { exportLauncher.launch("model-capabilities.json") },
            leadingContent = { CardGroupIcon(HugeIcons.FileExport, containerColor = containerColor, contentColor = contentColor) },
            headlineContent = { Text(stringResource(R.string.setting_model_page_capability_export)) },
        )
        item(
            onClick = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { overrides.clearLocal() }
                    localCount = overrides.localEntryCount()
                    baselineCount = overrides.baselineEntryCount()
                    toaster.show(if (ok) cleared else clearFailed)
                }
            },
            leadingContent = { CardGroupIcon(HugeIcons.Delete01, containerColor = containerColor, contentColor = contentColor) },
            headlineContent = { Text(stringResource(R.string.setting_model_page_capability_clear)) },
        )
    }
}
