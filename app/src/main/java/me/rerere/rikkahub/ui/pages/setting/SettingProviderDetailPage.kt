package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current

    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<Uuid>()) }

    LaunchedEffect(pager.currentPage) {
        if (selectionMode) {
            selectionMode = false
            selectedIds = emptySet()
        }
    }

    val onEdit = { newProvider: ProviderSetting ->
        vm.updateSettings { current ->
            current.copy(providers = current.providers.map { if (it.id == newProvider.id) newProvider else it })
        }
    }
    val onDelete = {
        vm.updateSettings { current -> current.copy(providers = current.providers - provider) }
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

            if (provider is ProviderSetting.OpenAI && provider.baseUrl.contains("siliconflow.cn")) {
                SiliconFlowPowerByIcon(
                    modifier =
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 16.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = !WindowInsets.isImeVisible,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -ScreenOffset),
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            HorizontalFloatingToolbar(
                expanded = expanded,
                colors = CustomColors.floatingToolbarColors,
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
                                internalProvider = internalProvider.resetBaseUrlToDefault()
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
                        onEdit(providerToSave.copyProvider(name = providerToSave.name.trim()))
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
                                    onUpdateProviderOverride(null)
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
}
