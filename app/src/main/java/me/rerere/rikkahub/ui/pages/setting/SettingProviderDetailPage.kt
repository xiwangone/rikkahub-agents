package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ProviderBalanceText
import me.rerere.rikkahub.ui.components.ai.providerDisplayName
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.RikkaDeleteConfirmDialog
import me.rerere.rikkahub.ui.components.ui.ShareSheet
import me.rerere.rikkahub.ui.components.ui.SiliconFlowPowerByIcon
import me.rerere.rikkahub.ui.components.ui.rememberShareSheetState
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomHeaders
import me.rerere.rikkahub.ui.pages.setting.components.BackendProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.CodexProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.GrokProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConnectionTester
import me.rerere.rikkahub.ui.pages.setting.components.SettingProviderBalanceOption
import me.rerere.rikkahub.ui.pages.setting.components.isUsingDefaultBaseUrl
import me.rerere.rikkahub.ui.pages.setting.components.resetBaseUrlToDefault
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

private enum class ProviderDetailTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    CONFIG(HugeIcons.Tools, R.string.setting_provider_page_configuration),
    MODELS(HugeIcons.Package01, R.string.setting_provider_page_models),
    ADVANCED(HugeIcons.Settings03, R.string.setting_provider_page_advanced_settings);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun SettingProviderDetailPage(
    id: Uuid,
    vm: SettingVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val provider = settings.providers.find { it.id == id } ?: return
    val pager = rememberPagerState { ProviderDetailTab.entries.size }
    val toaster = LocalToaster.current
    val saveSuccessText = stringResource(R.string.setting_provider_page_save_success)

    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<Uuid>()) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    // 配置页草稿提升到页级：HorizontalPager 会销毁切走的页，草稿放配置页里，
    // 切到“模型”页再回来就丢了；放这里切页也不丢。
    var configDraft by remember { mutableStateOf<ProviderSetting?>(null) }

    val onEdit = { newProvider: ProviderSetting ->
        vm.updateSettings { current ->
            current.copy(providers = current.providers.map { if (it.id == newProvider.id) newProvider else it })
        }
    }
    val onDelete = {
        vm.updateSettings { current -> current.copy(providers = current.providers - provider) }
        navController.popBackStack()
    }

    var lastPageIsConfig by remember { mutableStateOf(true) }
    val latestDraft by rememberUpdatedState(configDraft)
    val latestProvider by rememberUpdatedState(provider)
    LaunchedEffect(pager.currentPage) {
        val leavingConfig = lastPageIsConfig && pager.currentPage != ProviderDetailTab.CONFIG.ordinal
        lastPageIsConfig = pager.currentPage == ProviderDetailTab.CONFIG.ordinal
        if (leavingConfig) {
            // 切走前把草稿写回已保存的配置：不写回的话，改完 Key/地址、切到“模型”页
            // 看一眼再回来，刚才的修改全丢。模型列表与自定义请求头以已保存的为准
            // （模型页/高级页直接写库），避免保存草稿时把刚加的模型或请求头覆盖掉。
            latestDraft?.takeIf { it != latestProvider }?.let { draft ->
                onEdit(
                    draft.copyProvider(
                        name = draft.name.trim(),
                        models = latestProvider.models,
                        customHeaders = latestProvider.customHeaders,
                    ),
                )
                configDraft = null
                toaster.show(
                    saveSuccessText,
                    type = ToastType.Success,
                )
            }
        }
        if (selectionMode) {
            selectionMode = false
            selectedIds = emptySet()
        }
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
                                showDeleteSelectedDialog = true
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
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
        ) {
            PagerTabs(
                pagerState = pager,
                tabs = ProviderDetailTab.entries,
                modifier = Modifier.padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            HorizontalPager(
                state = pager,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            ) { page ->
                when (ProviderDetailTab.entries[page]) {
                    ProviderDetailTab.CONFIG -> {
                        SettingProviderConfigPage(
                            provider = provider,
                            draft = configDraft,
                            onDraftChange = { configDraft = it },
                            onEdit = {
                                onEdit(it)
                                configDraft = null
                                toaster.show(
                                    saveSuccessText,
                                    type = ToastType.Success,
                                )
                            },
                            onDelete = {
                                onDelete()
                            },
                        )
                    }

                    ProviderDetailTab.MODELS -> {
                        SettingProviderModelPage(
                            provider = provider,
                            onEdit = onEdit,
                            selectionMode = selectionMode,
                            selectedIds = selectedIds,
                            onSelectionModeChange = { selectionMode = it },
                            onSelectedIdsChange = { selectedIds = it },
                        )
                    }

                    ProviderDetailTab.ADVANCED -> {
                        SettingProviderAdvancedPage(
                            provider = provider,
                            onEdit = onEdit,
                        )
                    }
                }
            }
        }
    }

    // 批量删除二次确认：之前一点就把选中的模型全删了，无确认、无撤销。
    RikkaDeleteConfirmDialog(
        show = showDeleteSelectedDialog,
        title = stringResource(R.string.confirm_delete),
        message = "将删除已选的 ${selectedIds.size} 个模型，此操作不可恢复",
        onConfirm = {
            showDeleteSelectedDialog = false
            val toDelete = provider.models.filter { it.id in selectedIds }
            var updated = provider
            toDelete.forEach { updated = updated.delModel(it) }
            onEdit(updated)
            selectedIds = emptySet()
            selectionMode = false
        },
        onDismiss = { showDeleteSelectedDialog = false },
    )
}

@Composable
private fun SettingProviderConfigPage(
    provider: ProviderSetting,
    draft: ProviderSetting?,
    onDraftChange: (ProviderSetting?) -> Unit,
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
    // 草稿由父级持有（切页不丢）；同 id 的草稿优先于已保存的配置展示。
    val internalProvider = draft?.takeIf { it.id == provider.id } ?: provider
    var showDeleteDialog by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(true) }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .imePadding(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .floatingToolbarVerticalNestedScroll(
                        expanded = expanded,
                        onExpand = { expanded = true },
                        onCollapse = { expanded = false },
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProviderConfigure(
                provider = internalProvider,
                onEdit = {
                    onDraftChange(it)
                },
            )

            if (internalProvider is ProviderSetting.OpenAI) {
                SettingProviderBalanceOption(
                    provider = internalProvider,
                    balanceOption = internalProvider.balanceOption,
                    onEdit = { onDraftChange(internalProvider.copyProvider(balanceOption = it)) },
                )
                ProviderBalanceText(providerSetting = provider, style = MaterialTheme.typography.labelSmall)
            }

            if (provider is ProviderSetting.OpenAI && provider.baseUrl.contains("siliconflow.cn")) {
                SiliconFlowPowerByIcon(
                    modifier =
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 16.dp),
                )
            }
        }

        // 底部操作栏常驻：之前随键盘隐藏，键盘一弹起保存/测试/删除全消失，必须先收键盘
        // 才能保存；Box 带 imePadding，键盘弹起时工具栏顶在键盘上方，仍可见可点。
        HorizontalFloatingToolbar(
            expanded = expanded,
            colors = CustomColors.floatingToolbarColors,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -ScreenOffset),
            leadingContent = {
                    ProviderConnectionTester(
                        internalProvider = internalProvider,
                    )

                    if (!internalProvider.builtIn) {
                        IconButton(
                            onClick = {
                                showDeleteDialog = true
                            },
                        ) {
                            Icon(HugeIcons.Delete01, stringResource(R.string.delete))
                        }
                    }

                    if (!internalProvider.isUsingDefaultBaseUrl()) {
                        IconButton(
                            onClick = {
                                onDraftChange(internalProvider.resetBaseUrlToDefault())
                            },
                        ) {
                            Icon(
                                imageVector = HugeIcons.Refresh03,
                                contentDescription = stringResource(R.string.setting_model_page_reset_to_default),
                            )
                        }
                    }
                },
            ) {
                Button(
                    onClick = {
                        val providerToSave: ProviderSetting = internalProvider
                        // 模型列表与自定义请求头以已保存的为准（模型页/高级页直接写库），
                        // 避免保存草稿时把刚加的模型或请求头覆盖掉。
                        onEdit(
                            providerToSave.copyProvider(
                                name = providerToSave.name.trim(),
                                models = provider.models,
                                customHeaders = provider.customHeaders,
                            ),
                        )
                    },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            HugeIcons.FloppyDisk,
                            contentDescription = stringResource(R.string.setting_provider_page_save),
                        )
                        AnimatedVisibility(expanded) {
                            Row {
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.setting_provider_page_save))
                            }
                        }
                    }
                }
        }
    }

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
internal fun BuiltInToolsSettings(
    tools: Set<BuiltInTools>,
    onUpdateTools: (Set<BuiltInTools>) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_page_built_in_tools_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
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

        CardGroup {
            availableTools.forEach { (tool, info) ->
                val (title, description) = info
                switchItem(
                    checked = tool in tools,
                    onCheckedChange = { checked ->
                        if (checked) {
                            onUpdateTools(tools + tool)
                        } else {
                            onUpdateTools(tools - tool)
                        }
                    },
                    headlineContent = { Text(title) },
                    supportingContent = { Text(description) },
                )
            }
        }
    }
}

@Composable
internal fun ProviderOverrideSettings(
    providerOverride: ProviderSetting?,
    onUpdateProviderOverride: (ProviderSetting?) -> Unit,
    parentProvider: ProviderSetting?,
) {
    var showProviderConfig by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderSetting?>(null) }
    var showRemoveOverrideDialog by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardGroup(title = { Text(stringResource(R.string.setting_provider_page_provider_override)) }) {
            if (providerOverride != null) {
                item(
                    leadingContent = {
                        AutoAIIcon(
                            providerOverride.name,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    headlineContent = {
                        Text(stringResource(R.string.provider_override_label, providerOverride.name))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_provider_page_provider_override_desc))
                    },
                    trailingContent = {
                        Row {
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
                                    showRemoveOverrideDialog = true
                                },
                            ) {
                                Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.provider_action_remove_override))
                            }
                        }
                    },
                )
            } else {
                item(
                    onClick = {
                        editingProvider =
                            parentProvider?.copyProvider(
                                id = Uuid.random(),
                                builtIn = false,
                                models = emptyList(),
                            )
                        showProviderConfig = true
                    },
                    leadingContent = { Icon(HugeIcons.Add01, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.setting_provider_page_add_provider_override)) },
                    supportingContent = {
                        Text(stringResource(R.string.setting_provider_page_provider_override_desc))
                    },
                )
            }
        }

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

    // 移除覆盖二次确认：之前一点就把模型级的供应商覆盖删了，无确认、无撤销。
    RikkaDeleteConfirmDialog(
        show = showRemoveOverrideDialog,
        title = stringResource(R.string.confirm_delete),
        message = "将移除供应商覆盖，模型改用父级供应商配置，此操作不可恢复",
        onConfirm = {
            showRemoveOverrideDialog = false
            onUpdateProviderOverride(null)
        },
        onDismiss = { showRemoveOverrideDialog = false },
    )
}
