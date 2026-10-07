package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.rikkahub.data.ai.diagnoseFailure
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.ui.context.showFailure
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ModelAbilityTag
import me.rerere.rikkahub.ui.components.ai.ModelModalityTag
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.providerDisplayName
import me.rerere.rikkahub.ui.components.ai.ModelTypeTag
import me.rerere.rikkahub.ui.components.ai.ProviderBalanceText
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.ShareSheet
import me.rerere.rikkahub.ui.components.ui.SiliconFlowPowerByIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.rememberShareSheetState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomBodies
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomHeaders
import me.rerere.rikkahub.ui.pages.setting.components.CodexProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.GrokProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.BackendProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConnectionTester
import me.rerere.rikkahub.ui.pages.setting.components.SettingProviderBalanceOption
import me.rerere.rikkahub.ui.pages.setting.components.isUsingDefaultBaseUrl
import me.rerere.rikkahub.ui.pages.setting.components.resetBaseUrlToDefault
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.rikkahub.data.ai.catalog.ModelCatalogRepository

@Composable
fun SettingProviderDetailPage(
    id: Uuid,
    vm: SettingVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val provider = settings.providers.find { it.id == id } ?: return
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current

    // 多选删除状态（提升到页面级，供顶栏 actions 使用；与 ModelList 共享）
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<Uuid>()) }

    // 切 tab 时自动退出多选，避免状态残留
    LaunchedEffect(pager.currentPage) {
        if (selectionMode) {
            selectionMode = false
            selectedIds = emptySet()
        }
    }

    val onEdit = { newProvider: ProviderSetting ->
        val newSettings =
            settings.copy(
                providers =
                    settings.providers.map {
                        if (newProvider.id == it.id) {
                            newProvider
                        } else {
                            it
                        }
                    },
            )
        vm.updateSettings(newSettings)
    }
    val onDelete = {
        val newSettings =
            settings.copy(
                providers = settings.providers - provider,
            )
        vm.updateSettings(newSettings)
        navController.popBackStack()
    }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.topBarColors,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AutoAIIcon(provider.name, modifier = Modifier.size(22.dp))
                        Text(text = providerDisplayName(provider), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (selectionMode) {
                        // 多选模式：全选 / 删除选中 / 关闭
                        TextButton(
                            onClick = {
                                selectedIds =
                                    if (selectedIds.size == provider.models.size) {
                                        emptySet()
                                    } else {
                                        provider.models.map { it.id }.toSet()
                                    }
                            },
                        ) {
                            Text(stringResource(R.string.setting_provider_page_multi_select_all))
                        }
                        TextButton(
                            onClick = {
                                val toDelete = provider.models.filter { it.id in selectedIds }
                                var updated = provider
                                toDelete.forEach { updated = updated.delModel(it) }
                                onEdit(updated)
                                selectedIds = emptySet()
                                selectionMode = false
                            },
                        ) {
                            Text(
                                stringResource(R.string.setting_provider_page_delete_selected, selectedIds.size),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        IconButton(onClick = {
                            selectedIds = emptySet()
                            selectionMode = false
                        }) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                    } else if (
                        provider !is ProviderSetting.Codex &&
                        provider !is ProviderSetting.Grok &&
                        provider !is ProviderSetting.Backend
                    ) {
                        val shareSheetState = rememberShareSheetState()
                        ShareSheet(shareSheetState)
                        IconButton(
                            onClick = {
                                shareSheetState.show(provider)
                            },
                        ) {
                            Icon(HugeIcons.Share01, null)
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor,
            ) {
                NavigationBarItem(
                    selected = pager.currentPage == 0,
                    label = { Text(stringResource(id = R.string.setting_provider_page_configuration)) },
                    icon = { Icon(HugeIcons.Tools, null) },
                    onClick = {
                        scope.launch {
                            pager.animateScrollToPage(0)
                        }
                    },
                )
                NavigationBarItem(
                    selected = pager.currentPage == 1,
                    label = { Text(stringResource(id = R.string.setting_provider_page_models)) },
                    icon = { Icon(HugeIcons.Package01, null) },
                    onClick = {
                        scope.launch {
                            pager.animateScrollToPage(1)
                        }
                    },
                )
                NavigationBarItem(
                    selected = pager.currentPage == 2,
                    label = { Text(stringResource(id = R.string.setting_provider_page_advanced_settings)) },
                    icon = { Icon(HugeIcons.Settings03, null) },
                    onClick = {
                        scope.launch {
                            pager.animateScrollToPage(2)
                        }
                    },
                )
            }
        },
    ) {
        HorizontalPager(
            state = pager,
            modifier =
                Modifier
                    .padding(it)
                    .consumeWindowInsets(it),
        ) { page ->
            when (page) {
                0 -> {
                    SettingProviderConfigPage(
                        provider = provider,
                        onEdit = {
                            onEdit(it)
                            toaster.show(
                                context.getString(R.string.setting_provider_page_save_success),
                                type = ToastType.Success,
                            )
                        },
                        onDelete = {
                            onDelete()
                        },
                    )
                }

                1 -> {
                    SettingProviderModelPage(
                        provider = provider,
                        onEdit = onEdit,
                        selectionMode = selectionMode,
                        selectedIds = selectedIds,
                        onSelectionModeChange = { selectionMode = it },
                        onSelectedIdsChange = { selectedIds = it },
                    )
                }

                2 -> {
                    SettingProviderAdvancedPage(
                        provider = provider,
                        onEdit = onEdit,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingProviderConfigPage(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    onDelete: () -> Unit,
) {
    if (provider is ProviderSetting.Codex) {
        CodexProviderConfigure(
            provider = provider,
            onEdit = onEdit,
        )
        return
    }
    if (provider is ProviderSetting.Grok) {
        GrokProviderConfigure(
            provider = provider,
            onEdit = onEdit,
        )
        return
    }
    if (provider is ProviderSetting.Backend) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BackendProviderConfigure(
                provider = provider,
                onEdit = onEdit,
            )
        }
        return
    }
    var internalProvider by remember(provider) { mutableStateOf(provider) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProviderConfigure(
            provider = internalProvider,
            onEdit = {
                internalProvider = it
            },
        )

        if (internalProvider is ProviderSetting.OpenAI) {
            SettingProviderBalanceOption(
                provider = internalProvider,
                balanceOption = internalProvider.balanceOption,
                onEdit = { internalProvider = internalProvider.copyProvider(balanceOption = it) },
            )
            ProviderBalanceText(providerSetting = provider, style = MaterialTheme.typography.labelSmall)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderConnectionTester(
                internalProvider = internalProvider,
            )

            Spacer(Modifier.weight(1f))

            if (!internalProvider.builtIn) {
                IconButton(
                    onClick = {
                        showDeleteDialog = true
                    },
                ) {
                    Icon(HugeIcons.Delete01, null)
                }
            }

            IconButton(
                onClick = {
                    internalProvider = internalProvider.resetBaseUrlToDefault()
                },
                enabled = !internalProvider.isUsingDefaultBaseUrl(),
            ) {
                Icon(
                    imageVector = HugeIcons.Refresh03,
                    contentDescription = stringResource(R.string.setting_model_page_reset_to_default),
                )
            }

            Button(
                onClick = {
                    val providerToSave: ProviderSetting = internalProvider
                    onEdit(providerToSave.copyProvider(name = providerToSave.name.trim()))
                }
            ) {
                Text(stringResource(R.string.setting_provider_page_save))
            }
        }

        // 硅基流动图标
        if (provider is ProviderSetting.OpenAI && provider.baseUrl.contains("siliconflow.cn")) {
            SiliconFlowPowerByIcon(
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 16.dp),
            )
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(stringResource(R.string.confirm_delete))
            },
            text = {
                Text(stringResource(R.string.setting_provider_page_delete_dialog_text))
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
        )
    }
}

@Composable
private fun SettingProviderModelPage(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    selectionMode: Boolean,
    selectedIds: Set<Uuid>,
    onSelectionModeChange: (Boolean) -> Unit,
    onSelectedIdsChange: (Set<Uuid>) -> Unit,
) {
    ModelList(
        providerSetting = provider,
        onUpdateProvider = onEdit,
        selectionMode = selectionMode,
        selectedIds = selectedIds,
        onSelectionModeChange = onSelectionModeChange,
        onSelectedIdsChange = onSelectedIdsChange,
    )
}

@Composable
private fun SettingProviderAdvancedPage(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CustomHeaders(
            headers = provider.customHeaders,
            onUpdate = { headers ->
                onEdit(provider.copyProvider(customHeaders = headers))
            },
        )
    }
}

@Composable
private fun ModelList(
    providerSetting: ProviderSetting,
    onUpdateProvider: (ProviderSetting) -> Unit,
    selectionMode: Boolean,
    selectedIds: Set<Uuid>,
    onSelectionModeChange: (Boolean) -> Unit,
    onSelectedIdsChange: (Set<Uuid>) -> Unit,
) {
    val providerManager = koinInject<ProviderManager>()
    val catalog = koinInject<ModelCatalogRepository>()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 资源在组合期取好：回调里再读 LocalContext 的配置值会被 Lint 判为"非配置感知"
    val matchedDoneFormat = stringResource(R.string.setting_provider_page_match_abilities_done)
    val refreshDoneFormat = stringResource(R.string.setting_provider_page_refresh_catalog_done)
    val refreshFailedFormat = stringResource(R.string.setting_provider_page_refresh_catalog_failed)
    val catalogStatusFormat = stringResource(R.string.setting_provider_page_catalog_status)
    // 目录状态：条目数与上次更新时间（可能随刷新变化，故用可变状态）
    var catalogStatus by remember { mutableStateOf(catalog.status()) }
    var refreshing by remember { mutableStateOf(false) }
    val modelList by produceState(emptyList(), providerSetting) {
        runCatching {
            value =
                providerManager
                    .getProviderByType(providerSetting)
                    .listModels(providerSetting)
                    .sortedBy { it.modelId }
                    .toList()
        }.onFailure { error ->
            // runCatching catches Throwable, which includes CancellationException
            // (e.g. when the user navigates away from the Models tab mid-fetch
            // and Compose cancels this produceState's coroutine). Re-throw so
            // we don't print a stack trace + show a toast for normal teardown.
            if (error is kotlinx.coroutines.CancellationException) throw error
            error.printStackTrace()
            // Surface real failures (missing/invalid API key, providers like
            // Minimax that return an HTTP 200 error envelope instead of a 4xx).
            toaster.showFailure(context, error)
        }
    }
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    // 拖动期间只改本地顺序：onUpdateProvider 会写设置（DataStore），每移动一格提交一次会明显卡顿
    // → 停手 delay(400) 后再提交一次。providerSetting 变化（增删改 / 外部更新）时重置本地顺序。
    val localModels =
        remember {
            // 初始就带上当前模型（避免首帧空列表闪烁）；后续变化由下面的 LaunchedEffect 同步
            mutableStateListOf<Model>().apply { addAll(providerSetting.models) }
        }
    LaunchedEffect(providerSetting.models) {
        localModels.clear()
        localModels.addAll(providerSetting.models)
    }
    var reorderCommitJob by remember { mutableStateOf<Job?>(null) }
    // 拖动性能埋点：记录每格移动的处理耗时、相邻移动间隔、帧节奏、渲染细分与重组次数，停手后落 ReorderPerf 日志
    val activity = LocalActivity.current
    val reorderPerf = remember(activity) { ReorderPerfTracker(activity?.window) }
    DisposableEffect(reorderPerf) {
        onDispose { reorderPerf.dispose() }
    }
    val reorderableLazyListState =
        rememberReorderableLazyListState(lazyListState) { from, to ->
            val perfStartNanos = System.nanoTime()
            // from/to 是 LazyColumn 的 item（索引含页面前置项）→ 用模型 id（item key）定位，
            // 避免索引错位（此前直接用 from.index 导致 IndexOutOfBounds 崩溃）。
            val fromIdx = localModels.indexOfFirst { it.id == from.key }
            val toIdx = localModels.indexOfFirst { it.id == to.key }
            if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
                localModels.add(toIdx, localModels.removeAt(fromIdx))
                reorderCommitJob?.cancel()
                reorderCommitJob =
                    scope.launch {
                        delay(400)
                        reorderPerf.flush()
                        // 按本地顺序重排当前模型集合；期间新增/删除的项原样保留在尾部
                        val byOrder =
                            localModels.mapNotNull { m ->
                                providerSetting.models.find { it.id == m.id }
                            }
                        val rest =
                            providerSetting.models.filter { m ->
                                byOrder.none { it.id == m.id }
                            }
                        val reordered = byOrder + rest
                        if (reordered != providerSetting.models) {
                            onUpdateProvider(providerSetting.copyProvider(models = reordered))
                        }
                    }
            }
            reorderPerf.record((System.nanoTime() - perfStartNanos) / 1000)
        }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .floatingToolbarVerticalNestedScroll(
                        expanded = expanded,
                        onExpand = { expanded = true },
                        onCollapse = { expanded = false },
                    ),
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState,
        ) {
            // 「匹配 / 刷新」：匹配 = 用现有能力表回填当前页面的模型（纯本地）；
            // 刷新 = 联网拉最新能力目录后再匹配（只影响本页面这些模型）。
            if (providerSetting.models.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = catalogStatusFormat.format(catalogStatus.entryCount, formatCatalogTime(catalogStatus.updatedAtMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            enabled = !refreshing,
                            onClick = {
                                refreshing = true
                                scope.launch {
                                    val result = catalog.refresh()
                                    catalogStatus = catalog.status()
                                    refreshing = false
                                    toaster.show(
                                        result.fold(
                                            onSuccess = { refreshDoneFormat.format(it) },
                                            onFailure = { refreshFailedFormat.format(it.message ?: "") },
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(HugeIcons.Refresh03, contentDescription = null)
                            Text(stringResource(R.string.setting_provider_page_refresh_catalog))
                        }
                        Button(
                            onClick = {
                                var changed = 0
                                val updated =
                                    providerSetting.models.map { m ->
                                        val inputs = ModelRegistry.MODEL_INPUT_MODALITIES.getData(m.modelId)
                                        val outputs = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(m.modelId)
                                        val abilities = ModelRegistry.MODEL_ABILITIES.getData(m.modelId)
                                        if (
                                            inputs != m.inputModalities ||
                                                outputs != m.outputModalities ||
                                                abilities != m.abilities
                                        ) {
                                            changed++
                                            m.copy(
                                                inputModalities = inputs,
                                                outputModalities = outputs,
                                                abilities = abilities,
                                            )
                                        } else {
                                            m
                                        }
                                    }
                                if (changed > 0) {
                                    // ProviderSetting 是密封类，没有统一的 copy(models=...)：逐项替换变化过的模型
                                    val applied =
                                        providerSetting.models.zip(updated).fold(providerSetting) { acc, (old, new) ->
                                            if (old === new) acc else acc.editModel(new)
                                        }
                                    onUpdateProvider(applied)
                                }
                                toaster.show(matchedDoneFormat.format(changed))
                            },
                        ) {
                            Icon(HugeIcons.MagicWand01, contentDescription = null)
                            Text(stringResource(R.string.setting_provider_page_match_abilities))
                        }
                    }
                }
            }

            // 模型列表
            if (providerSetting.models.isEmpty()) {
                item {
                    Column(
                        modifier =
                            Modifier
                                .fillParentMaxHeight(0.8f)
                                .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.setting_provider_page_no_models),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.setting_provider_page_add_models_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            } else {
                items(localModels, key = { it.id }) { item ->
                    // 拖动性能埋点：统计本次拖动会话内的卡片重组次数（临时）
                    SideEffect { ReorderRecomposeCounter.count++ }
                    ReorderableItem(
                        state = reorderableLazyListState,
                        key = item.id,
                    ) { isDragging ->
                        ModelCard(
                            model = item,
                            onDelete = {
                                onUpdateProvider(providerSetting.delModel(item))
                            },
                            onEdit = { editedModel ->
                                onUpdateProvider(providerSetting.editModel(editedModel))
                            },
                            parentProvider = providerSetting,
                            selectionMode = selectionMode,
                            selected = item.id in selectedIds,
                            onToggleSelect = {
                                onSelectedIdsChange(
                                    if (item.id in selectedIds) {
                                        selectedIds - item.id
                                    } else {
                                        selectedIds + item.id
                                    },
                                )
                            },
                            onEnterMultiSelect = {
                                onSelectionModeChange(true)
                                onSelectedIdsChange(setOf(item.id))
                            },
                            modifier =
                                Modifier
                                    .let { mod ->
                                        if (selectionMode) {
                                            mod.combinedClickable(
                                                onClick = {
                                                    onSelectedIdsChange(
                                                        if (item.id in selectedIds) {
                                                            selectedIds - item.id
                                                        } else {
                                                            selectedIds + item.id
                                                        },
                                                    )
                                                },
                                            )
                                        } else {
                                            mod.longPressDraggableHandle()
                                        }
                                    }.graphicsLayer {
                                        if (isDragging) {
                                            scaleX = 1.05f
                                            scaleY = 1.05f
                                        } else {
                                            scaleX = 1f
                                            scaleY = 1f
                                        }
                                    },
                        )
                    }
                }
            }
        }
        HorizontalFloatingToolbar(
            expanded = expanded,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -ScreenOffset),
        ) {
            AddModelButton(
                models = modelList,
                selectedModels = providerSetting.models,
                onAddModel = {
                    onUpdateProvider(providerSetting.addModel(it))
                },
                onRemoveModel = {
                    onUpdateProvider(providerSetting.delModel(it))
                },
                expanded = expanded,
                parentProvider = providerSetting,
                onUpdateProvider = onUpdateProvider,
            )
        }
    }
}

@Composable
private fun ModelSettingsForm(
    model: Model,
    onModelChange: (Model) -> Unit,
    isEdit: Boolean,
    parentProvider: ProviderSetting? = null,
) {
    val pagerState = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()

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
        SecondaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
        ) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(0)
                    }
                },
                text = { Text(stringResource(R.string.setting_provider_page_basic_settings)) },
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(1)
                    }
                },
                text = { Text(stringResource(R.string.setting_provider_page_advanced_settings)) },
            )
            Tab(
                selected = pagerState.currentPage == 2,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(2)
                    }
                },
                text = { Text(stringResource(R.string.setting_page_built_in_tools)) },
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            when (page) {
                0 -> {
                    // 基本设置页面
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(vertical = 16.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        OutlinedTextField(
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
                        )

                        OutlinedTextField(
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
                        }
                    }
                }

                1 -> {
                    // 高级设置页面
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
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

                2 -> {
                    // 内置工具页面
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
private fun AddModelButton(
    models: List<Model>,
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
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.setting_provider_page_add_new_model),
                        style = MaterialTheme.typography.bodyLarge,
                    )
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
                            .padding(16.dp),
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
                        TextButton(
                            onClick = {
                                if (modelState.modelId.isNotBlank() && modelState.displayName.isNotBlank()) {
                                    dialogState.confirm()
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
private fun ModelPicker(
    models: List<Model>,
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
            val filterKeywords = filterText.split(" ").filter { it.isNotBlank() }
            val filteredModels =
                models.fastFilter {
                    if (filterKeywords.isEmpty()) {
                        true
                    } else {
                        filterKeywords.all { keyword ->
                            it.modelId.contains(keyword, ignoreCase = true) ||
                                it.displayName.contains(keyword, ignoreCase = true)
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
                // 标题栏和添加所有按钮
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

                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(filteredModels, key = { it.id }) {
                        Card {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement =
                                    Arrangement.spacedBy(
                                        8.dp,
                                    ),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                            ) {
                                AutoAIIcon(
                                    it.modelId,
                                    Modifier.size(32.dp),
                                )
                                Column(
                                    verticalArrangement =
                                        Arrangement.spacedBy(
                                            4.dp,
                                        ),
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
                                IconButton(
                                    onClick = {
                                        if (selectedModels.any { model -> model.modelId == it.modelId }) {
                                            // 从selectedModels中计算出要删除的model，因为删除需要id匹配，而不是ModelId
                                            onModelDeselected(
                                                selectedModels.firstOrNull { model -> model.modelId == it.modelId }
                                                    ?: it,
                                            )
                                        } else {
                                            onModelSelected(it)
                                        }
                                    },
                                ) {
                                    if (selectedModels.any { model -> model.modelId == it.modelId }) {
                                        Icon(HugeIcons.Cancel01, null)
                                    } else {
                                        Icon(HugeIcons.Add01, null)
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = filterText,
                    onValueChange = {
                        filterText = it
                    },
                    label = { Text(stringResource(R.string.setting_provider_page_filter_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.setting_provider_page_filter_example))
                    },
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
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
    ) {
        ModelType.entries.forEachIndexed { index, type ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index, ModelType.entries.size),
                label = {
                    Text(
                        text =
                            stringResource(
                                when (type) {
                                    ModelType.CHAT -> R.string.setting_provider_page_chat_model
                                    ModelType.EMBEDDING -> R.string.setting_provider_page_embedding_model
                                    ModelType.IMAGE -> R.string.setting_provider_page_image_model
                                },
                            ),
                    )
                },
                selected = selectedType == type,
                onClick = { onTypeSelected(type) },
            )
        }
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
        MultiChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Modality.entries.forEachIndexed { index, modality ->
                SegmentedButton(
                    checked = modality in inputModalities,
                    shape = SegmentedButtonDefaults.itemShape(index, Modality.entries.size),
                    onCheckedChange = {
                        if (it) {
                            onUpdateInputModalities(inputModalities + modality)
                        } else {
                            onUpdateInputModalities(inputModalities - modality)
                        }
                    },
                ) {
                    Text(
                        text =
                            stringResource(
                                when (modality) {
                                    Modality.TEXT -> R.string.setting_provider_page_text
                                    Modality.IMAGE -> R.string.setting_provider_page_image
                                },
                            ),
                    )
                }
            }
        }

        Text(
            stringResource(R.string.setting_provider_page_output_modality),
            style = MaterialTheme.typography.titleSmall,
        )
        MultiChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Modality.entries.forEachIndexed { index, modality ->
                SegmentedButton(
                    checked = modality in outputModalities,
                    shape = SegmentedButtonDefaults.itemShape(index, Modality.entries.size),
                    onCheckedChange = {
                        if (it) {
                            onUpdateOutputModalities(outputModalities + modality)
                        } else {
                            onUpdateOutputModalities(outputModalities - modality)
                        }
                    },
                ) {
                    Text(
                        text =
                            stringResource(
                                when (modality) {
                                    Modality.TEXT -> R.string.setting_provider_page_text
                                    Modality.IMAGE -> R.string.setting_provider_page_image
                                },
                            ),
                    )
                }
            }
        }
    }
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
    MultiChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
    ) {
        ModelAbility.entries.forEachIndexed { index, ability ->
            SegmentedButton(
                checked = ability in abilities,
                shape = SegmentedButtonDefaults.itemShape(index, ModelAbility.entries.size),
                onCheckedChange = {
                    if (it) {
                        onUpdateAbilities(abilities + ability)
                    } else {
                        onUpdateAbilities(abilities - ability)
                    }
                },
                label = {
                    Text(
                        text =
                            stringResource(
                                when (ability) {
                                    ModelAbility.TOOL -> R.string.setting_provider_page_tool
                                    ModelAbility.REASONING -> R.string.setting_provider_page_reasoning
                                },
                            ),
                    )
                },
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: Model,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    onEdit: (Model) -> Unit,
    parentProvider: ProviderSetting,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onEnterMultiSelect: () -> Unit = {},
) {
    val dialogState = useEditState<Model> {
        onEdit(it.copy(displayName = it.displayName.trim()))
    }
    val swipeToDismissBoxState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()

    if (dialogState.isEditing) {
        dialogState.currentState?.let { editingModel ->
            val sheetState =
                rememberBottomSheetState(
                    initialValue = SheetValue.Hidden,
                    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                )
            ModalBottomSheet(
                onDismissRequest = {
                    dialogState.dismiss()
                },
                sheetState = sheetState,
                sheetGesturesEnabled = false,
                dragHandle = null,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.95f)
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    sheetState.hide()
                                    dialogState.dismiss()
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterStart),
                        ) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                        Text(
                            text = stringResource(R.string.setting_provider_page_edit_model),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                    ) {
                        ModelSettingsForm(
                            model = editingModel,
                            onModelChange = { dialogState.currentState = it },
                            isEdit = true,
                            parentProvider = parentProvider,
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
                        TextButton(
                            onClick = {
                                if (editingModel.displayName.isNotBlank()) {
                                    dialogState.confirm()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                }
            }
        }
    }

    SwipeToDismissBox(
        state = swipeToDismissBoxState,
        backgroundContent = {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = {
                        scope.launch {
                            swipeToDismissBoxState.reset()
                        }
                        onEnterMultiSelect()
                    },
                ) {
                    Icon(
                        HugeIcons.CheckList,
                        contentDescription = stringResource(R.string.setting_provider_page_multi_select),
                    )
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            swipeToDismissBoxState.reset()
                        }
                    },
                ) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledIconButton(
                    onClick = {
                        scope.launch {
                            onDelete()
                            swipeToDismissBoxState.reset()
                        }
                    },
                ) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.chat_page_delete),
                    )
                }
            }
        },
        enableDismissFromStartToEnd = false,
        gesturesEnabled = !selectionMode,
        modifier = modifier,
    ) {
        OutlinedCard(
            border =
                if (selectionMode && selected) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                } else {
                    CardDefaults.outlinedCardBorder()
                },
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() },
                        colors =
                            CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    AutoAIIcon(
                        name = model.modelId,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (model.providerOverwrite != null) {
                            Tag(type = TagType.INFO) {
                                Text(
                                    model.providerOverwrite?.javaClass?.simpleName ?: model.providerOverwrite?.name
                                        ?: "ProviderOverwrite",
                                )
                            }
                        }
                        ModelTypeTag(model = model)
                        ModelModalityTag(model = model)
                        ModelAbilityTag(model = model)
                    }
                }

                // Edit button
                if (!selectionMode) {
                    IconButton(
                        onClick = {
                            dialogState.open(model.copy())
                        },
                    ) {
                        Icon(HugeIcons.Tools, stringResource(R.string.provider_action_edit))
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltInToolsSettings(
    tools: Set<BuiltInTools>,
    onUpdateTools: (Set<BuiltInTools>) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_page_built_in_tools),
            style = MaterialTheme.typography.titleMedium,
        )

        Text(
            text = stringResource(R.string.setting_page_built_in_tools_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val availableTools =
            listOf(
                BuiltInTools.Search to
                    Pair(
                        stringResource(R.string.setting_page_built_in_tools_search),
                        stringResource(R.string.setting_page_built_in_tools_search_desc),
                    ),
                BuiltInTools.UrlContext to
                    Pair(
                        stringResource(R.string.setting_page_built_in_tools_url_context),
                        stringResource(R.string.setting_page_built_in_tools_url_context_desc),
                    ),
                BuiltInTools.ImageGeneration to
                    Pair(
                        stringResource(R.string.setting_page_built_in_tools_image_generation),
                        stringResource(R.string.setting_page_built_in_tools_image_generation_desc),
                    ),
            )

        availableTools.forEach { (tool, info) ->
            val (title, description) = info
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = tool in tools,
                        onCheckedChange = { checked ->
                            if (checked) {
                                onUpdateTools(tools + tool)
                            } else {
                                onUpdateTools(tools - tool)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderOverrideSettings(
    providerOverride: ProviderSetting?,
    onUpdateProviderOverride: (ProviderSetting?) -> Unit,
    parentProvider: ProviderSetting?,
) {
    var showProviderConfig by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderSetting?>(null) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_provider_page_provider_override),
            style = MaterialTheme.typography.titleSmall,
        )

        Text(
            text = stringResource(R.string.setting_provider_page_provider_override_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (providerOverride != null) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AutoAIIcon(
                            providerOverride.name,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = stringResource(R.string.provider_override_label, providerOverride.name),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                editingProvider = providerOverride
                                showProviderConfig = true
                            },
                        ) {
                            Icon(HugeIcons.Tools, contentDescription = stringResource(R.string.provider_action_edit_override))
                        }
                        IconButton(
                            onClick = {
                                onUpdateProviderOverride(null)
                            },
                        ) {
                            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.provider_action_remove_override))
                        }
                    }
                }
            }
        } else {
            Button(
                onClick = {
                    editingProvider =
                        parentProvider?.copyProvider(
                            id = Uuid.random(),
                            builtIn = false,
                            models = emptyList(), // 这里必须设置为空，不然会导致循环依赖JSON
                        )
                    showProviderConfig = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Add01, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.setting_provider_page_add_provider_override))
            }
        }

        // Provider configuration modal
        if (showProviderConfig && editingProvider != null) {
            ModalBottomSheet(
                onDismissRequest = {
                    showProviderConfig = false
                    editingProvider = null
                },
                sheetState =
                    rememberBottomSheetState(
                        initialValue = SheetValue.Hidden,
                        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                    ),
            ) {
                var internalProvider by remember(editingProvider) { mutableStateOf(editingProvider!!) }

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.9f)
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_configure_provider_override),
                        style = MaterialTheme.typography.titleLarge,
                    )

                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ProviderConfigure(
                            provider = internalProvider,
                            onEdit = { internalProvider = it },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(
                            onClick = {
                                showProviderConfig = false
                                editingProvider = null
                            },
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                onUpdateProviderOverride(internalProvider.copyProvider(name = internalProvider.name.trim()))
                                showProviderConfig = false
                                editingProvider = null
                            },
                        ) {
                            Text(stringResource(R.string.setting_provider_page_save))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Prefer capabilities auto-detected at fetch time (OpenRouter's /models populates
 * supportedParameters, modalities, abilities and the IMAGE model type); only fall back to
 * the static ModelRegistry lookup for providers that return bare model ids. Without this,
 * the registry lookup clobbered OpenRouter's detected image/tool/reasoning capabilities.
 */
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

/** 目录上次更新时间：时间戳为 0 表示尚未拉取，显示占位。 */
private fun formatCatalogTime(ms: Long): String =
    if (ms <= 0L) {
        "—"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }

/** 拖动会话序号（进程内累计）：seq=1 即冷启动后第一次拖动 —— 用于验证「首次拖动更卡」。 */
private object ReorderPerfSeq {
    var value = 0
}

/** 拖动会话内的卡片重组次数（临时埋点）：验证「声明 Model 稳定」是否真的减少了重组。 */
private object ReorderRecomposeCounter {
    var count = 0
}

/**
 * 拖动排序性能埋点（临时，用于定位“拖动不跟手”），一次拖动会话累计四类数据：
 * ① 移动统计：相邻移动间隔 gap（混了手速与主线程阻塞，只能同场景横向比）、单次处理耗时 handle。
 * ② 帧节奏：Choreographer 帧间隔（p50 直接暴露刷新周期、jank33/50 记掉帧）。
 * ③ 渲染细分：FrameMetrics —— sync=组合+测量+布局、draw=绘制指令录制、unknownDelay=帧开始前主线程被占、
 *    firstDraw=含首次绘制的帧数、gpu（API 31+）、dropped=系统报的丢帧。
 * ④ 重组次数：拖动列表 item 内 SideEffect 自增，反映“每格重排触发几次卡片重组”。
 * 判读：handleMaxUs 大 → onMove 本身重；syncP95／recompose 大 → 重组/布局重；
 * 帧节奏与 sync 都正常而体感仍卡 → 属“位移不跟手”，不是渲染开销。
 */
private class ReorderPerfTracker(private val window: Window? = null) {
    private var moves = 0
    private var gapSumMs = 0L
    private var gapMaxMs = 0L
    private var handleSumUs = 0L
    private var handleMaxUs = 0L
    private var lastAtMs = 0L
    private var startMs = 0L

    // —— 拖动期间的帧间隔（Choreographer：主线程忙时回调顺延，间隔随之变大）——
    // 停手到 flush（400ms）之间仍会计帧，属可接受；p50 会直接暴露屏幕刷新周期（60Hz≈17ms / 120Hz≈8ms）
    private val choreographer: Choreographer? by lazy {
        runCatching { Choreographer.getInstance() }.getOrNull()
    }
    private val frameGapsMs = mutableListOf<Long>()
    private var frameRunning = false
    private var lastFrameNanos = 0L
    private var sessionSeq = 0
    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!frameRunning) return
                if (lastFrameNanos != 0L) {
                    frameGapsMs.add((frameTimeNanos - lastFrameNanos) / 1_000_000)
                }
                lastFrameNanos = frameTimeNanos
                choreographer?.postFrameCallback(this)
            }
        }

    // —— 渲染细分（FrameMetrics：无需新依赖，API 24+；单位统一存微秒）——
    // 回调走主线程 Handler（每帧一条消息，开销可忽略）；gpu 仅 Android 12+ 可读
    private val fmHandler = Handler(Looper.getMainLooper())
    private var fmRunning = false
    private var fmFrames = 0
    private var fmDropped = 0
    private var fmFirstDraw = 0
    private val fmSyncUs = mutableListOf<Long>()
    private val fmDrawUs = mutableListOf<Long>()
    private val fmTotalUs = mutableListOf<Long>()
    private val fmUnknownUs = mutableListOf<Long>()
    private val fmGpuUs = mutableListOf<Long>()
    private val fmListener =
        object : Window.OnFrameMetricsAvailableListener {
            override fun onFrameMetricsAvailable(
                window: Window,
                frameMetrics: FrameMetrics,
                dropCountSinceLastInvocation: Int,
            ) {
                if (!fmRunning) return
                fmFrames++
                fmDropped += dropCountSinceLastInvocation
                if (frameMetrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) fmFirstDraw++
                fmSyncUs.add(frameMetrics.getMetric(FrameMetrics.SYNC_DURATION) / 1_000)
                fmDrawUs.add(frameMetrics.getMetric(FrameMetrics.DRAW_DURATION) / 1_000)
                fmTotalUs.add(frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000)
                fmUnknownUs.add(frameMetrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) / 1_000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    fmGpuUs.add(frameMetrics.getMetric(FrameMetrics.GPU_DURATION) / 1_000)
                }
            }
        }

    // —— 系统压力（会话起止各一次快照：GC / CPU / 堆 / 负载 / 温控降频）——
    // 用于排除「偶发卡」的环境因素：后台 GC、CPU 竞争、温控降频都会让同一份代码时好时坏
    private var stressCpuStartMs = 0L
    private var stressGcStart = 0L
    private var stressGcMsStart = 0L
    private var stressBgcStart = 0L
    private var stressBgcMsStart = 0L
    private var stressHeapStartMb = 0L
    private var stressNativeStartMb = 0L
    private var stressStartInfo = ""

    private fun runtimeStat(key: String): Long =
        runCatching { Debug.getRuntimeStat(key)?.trim()?.toLongOrNull() ?: -1L }.getOrDefault(-1L)

    private fun javaHeapMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / MB
    }

    private fun loadAvg1(): String =
        runCatching { File("/proc/loadavg").readText().trim().substringBefore(' ') }.getOrNull() ?: "?"

    private fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "?"
        val pm = window?.context?.getSystemService(PowerManager::class.java) ?: return "?"
        return pm.currentThermalStatus.toString()
    }

    private fun powerSaveMode(): String =
        if (window?.context?.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) "1" else "0"

    /** 各核当前频率取最大（MHz）——温控降频时这个值会明显掉。 */
    private fun cpuMaxFreqMhz(): String {
        var max = 0L
        for (i in 0 until CPU_FREQ_PROBES) {
            val freq =
                runCatching {
                    File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLong()
                }.getOrNull() ?: continue
            if (freq > max) max = freq
        }
        return if (max > 0) (max / 1000).toString() else "?"
    }

    private fun snapshotStressStart() {
        stressCpuStartMs = android.os.Process.getElapsedCpuTime()
        stressGcStart = runtimeStat(GC_COUNT)
        stressGcMsStart = runtimeStat(GC_TIME)
        stressBgcStart = runtimeStat(BLOCKING_GC_COUNT)
        stressBgcMsStart = runtimeStat(BLOCKING_GC_TIME)
        stressHeapStartMb = javaHeapMb()
        stressNativeStartMb = Debug.getNativeHeapAllocatedSize() / MB
        stressStartInfo =
            "load1=${loadAvg1()} thermal=${thermalStatus()} saver=${powerSaveMode()} cpuMaxMhz=${cpuMaxFreqMhz()}"
    }

    private fun startSession() {
        sessionSeq = ++ReorderPerfSeq.value
        ReorderRecomposeCounter.count = 0
        snapshotStressStart()
        if (!frameRunning) {
            frameRunning = true
            lastFrameNanos = 0L
            frameGapsMs.clear()
            choreographer?.postFrameCallback(frameCallback)
        }
        val fmWindow = window
        if (!fmRunning && fmWindow != null) {
            fmRunning = true
            fmFrames = 0
            fmDropped = 0
            fmFirstDraw = 0
            fmSyncUs.clear()
            fmDrawUs.clear()
            fmTotalUs.clear()
            fmUnknownUs.clear()
            fmGpuUs.clear()
            fmWindow.addOnFrameMetricsAvailableListener(fmListener, fmHandler)
        }
    }

    private fun stopSession() {
        frameRunning = false
        choreographer?.removeFrameCallback(frameCallback)
        if (fmRunning) {
            fmRunning = false
            window?.removeOnFrameMetricsAvailableListener(fmListener)
        }
    }

    /** 页面离开时兜底停表，避免监听器残留。 */
    fun dispose() {
        moves = 0
        stopSession()
    }

    private fun percentile(sorted: List<Long>, p: Int): Long =
        if (sorted.isEmpty()) 0L else sorted[(sorted.size - 1) * p / 100]

    fun record(handleUs: Long) {
        val now = System.currentTimeMillis()
        if (moves > 0 && now - lastAtMs > SESSION_GAP_MS) flush()
        if (moves == 0) {
            startMs = now
            gapSumMs = 0
            gapMaxMs = 0
            handleSumUs = 0
            handleMaxUs = 0
            startSession()
        } else {
            val gap = now - lastAtMs
            gapSumMs += gap
            if (gap > gapMaxMs) gapMaxMs = gap
        }
        moves++
        handleSumUs += handleUs
        if (handleUs > handleMaxUs) handleMaxUs = handleUs
        lastAtMs = now
    }

    fun flush() {
        if (moves == 0) return
        val n = moves
        val frames = frameGapsMs.sorted()
        val sync = fmSyncUs.sorted()
        val draw = fmDrawUs.sorted()
        val total = fmTotalUs.sorted()
        val unknown = fmUnknownUs.sorted()
        val gpu = fmGpuUs.sorted()
        val recomposed = ReorderRecomposeCounter.count
        stopSession()
        AppLog.i(
            TAG,
            "seq=$sessionSeq moves=$n span=${lastAtMs - startMs}ms gapAvg=${if (n > 1) gapSumMs / (n - 1) else 0}ms " +
                "gapMax=${gapMaxMs}ms handleAvgUs=${handleSumUs / n} handleMaxUs=$handleMaxUs | " +
                "frames=${frames.size} p50=${percentile(frames, 50)}ms p95=${percentile(frames, 95)}ms " +
                "max=${frames.lastOrNull() ?: 0L}ms jank33=${frames.count { it >= 33 }} jank50=${frames.count { it >= 50 }} | " +
                "recompose=$recomposed",
        )
        AppLog.i(
            TAG,
            "fm seq=$sessionSeq n=$fmFrames syncP95=${percentile(sync, 95)}us syncMax=${sync.lastOrNull() ?: 0L}us " +
                "drawP95=${percentile(draw, 95)}us totalMax=${total.lastOrNull() ?: 0L}us " +
                "unkP95=${percentile(unknown, 95)}us gpuP95=${percentile(gpu, 95)}us " +
                "firstDraw=$fmFirstDraw dropped=$fmDropped",
        )
        AppLog.i(
            TAG,
            "stress seq=$sessionSeq cpu=+${android.os.Process.getElapsedCpuTime() - stressCpuStartMs}ms " +
                "gc=+${runtimeStat(GC_COUNT) - stressGcStart} gcMs=+${runtimeStat(GC_TIME) - stressGcMsStart} " +
                "bgc=+${runtimeStat(BLOCKING_GC_COUNT) - stressBgcStart} " +
                "bgcMs=+${runtimeStat(BLOCKING_GC_TIME) - stressBgcMsStart} " +
                "heap=${stressHeapStartMb}->${javaHeapMb()}MB " +
                "native=${stressNativeStartMb}->${Debug.getNativeHeapAllocatedSize() / MB}MB | " +
                "start[$stressStartInfo] end[load1=${loadAvg1()} thermal=${thermalStatus()}]",
        )
        moves = 0
    }

    private companion object {
        const val TAG = "ReorderPerf"
        const val SESSION_GAP_MS = 800L
        const val MB = 1024L * 1024L
        const val CPU_FREQ_PROBES = 8
        const val GC_COUNT = "art.gc.gc-count"
        const val GC_TIME = "art.gc.gc-time"
        const val BLOCKING_GC_COUNT = "art.gc.blocking-gc-count"
        const val BLOCKING_GC_TIME = "art.gc.blocking-gc-time"
    }
}
