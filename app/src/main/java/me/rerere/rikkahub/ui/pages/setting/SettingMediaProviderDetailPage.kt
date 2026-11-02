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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.components.MediaGenerationProviderBasicConfigure
import me.rerere.rikkahub.ui.pages.setting.components.MediaGenerationProviderModelsConfigure
import me.rerere.rikkahub.ui.pages.setting.components.MediaModelPickerButton
import me.rerere.rikkahub.ui.pages.setting.components.rememberMediaAvailableModels
import me.rerere.rikkahub.ui.pages.setting.components.typeName
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

private enum class MediaProviderDetailTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    BASIC(HugeIcons.Tools, R.string.setting_provider_page_basic_settings),
    MODELS(HugeIcons.Package01, R.string.setting_media_page_models);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun SettingMediaProviderDetailPage(
    id: Uuid,
    vm: SettingVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val provider = settings.mediaGenerationProviders.find { it.id == id } ?: return
    val toaster = LocalToaster.current
    val resources = LocalResources.current
    val pager = rememberPagerState { MediaProviderDetailTab.entries.size }
    var draft by remember { mutableStateOf(provider) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showModelPickerSheet by remember { mutableStateOf(false) }

    val availableModels = rememberMediaAvailableModels(draft)
    val kinds = remember(draft.supportedKinds) { MediaKind.entries.filter { it in draft.supportedKinds } }

    val onSave: () -> Unit = {
        val saved = draft.copyProvider(models = draft.models.filter { it.modelId.isNotBlank() })
        vm.updateSettings { latest ->
            latest.copy(
                mediaGenerationProviders =
                    latest.mediaGenerationProviders.map {
                        if (it.id == id) saved else it
                    },
            )
        }
        draft = saved
        toaster.show(
            resources.getString(R.string.setting_provider_page_save_success),
            type = ToastType.Success,
        )
    }
    val onDelete = {
        vm.updateSettings { latest ->
            latest.copy(mediaGenerationProviders = latest.mediaGenerationProviders.filter { it.id != id })
        }
        navController.popBackStack()
    }

    val name = draft.name.ifEmpty { draft.typeName }
    val currentTab = MediaProviderDetailTab.entries[pager.currentPage]

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
                        AutoAIIcon(name, modifier = Modifier.size(22.dp))
                        Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                PagerTabs(
                    pagerState = pager,
                    tabs = MediaProviderDetailTab.entries,
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
                    val pageModifier =
                        Modifier.floatingToolbarVerticalNestedScroll(
                            expanded = expanded,
                            onExpand = { expanded = true },
                            onCollapse = { expanded = false },
                        )
                    when (MediaProviderDetailTab.entries[page]) {
                        MediaProviderDetailTab.BASIC ->
                            MediaGenerationProviderBasicConfigure(
                                setting = draft,
                                onValueChange = { draft = it },
                                modifier = pageModifier,
                            )

                        MediaProviderDetailTab.MODELS ->
                            MediaGenerationProviderModelsConfigure(
                                setting = draft,
                                availableModels = availableModels,
                                showModelPickerSheet = showModelPickerSheet,
                                onShowModelPickerSheetChange = { showModelPickerSheet = it },
                                onValueChange = { draft = it },
                                modifier = pageModifier,
                            )
                    }
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
                        if (currentTab == MediaProviderDetailTab.MODELS) {
                            MediaModelPickerButton(
                                availableCount = availableModels.size,
                                onClick = { showModelPickerSheet = true },
                            )
                            if (kinds.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        draft =
                                            draft.copyProvider(
                                                models = draft.models + MediaGenerationModel(modelId = "", kind = kinds.first()),
                                            )
                                    },
                                ) {
                                    Icon(HugeIcons.Add01, stringResource(R.string.setting_media_page_add_model))
                                }
                            }
                        } else {
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(HugeIcons.Delete01, stringResource(R.string.delete))
                            }
                        }
                    },
                ) {
                    Button(onClick = onSave) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(HugeIcons.FloppyDisk, contentDescription = stringResource(R.string.chat_page_save))
                            AnimatedVisibility(expanded) {
                                Row {
                                    Spacer(modifier = Modifier.size(8.dp))
                                    Text(stringResource(R.string.chat_page_save))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteDialog = false
            onDelete()
        },
        onDismiss = { showDeleteDialog = false },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, name))
    }
}
