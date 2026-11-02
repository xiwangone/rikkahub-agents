package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiSearch02
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.SearchRemove
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.SearchAbilityTagLine
import org.koin.compose.koinInject

enum class SearchMode {
    OFF,
    LOCAL,
    BUILT_IN,
}

private val modeShapes = SearchMode.entries.map { it.shape() }

internal fun searchModeOf(
    enableSearch: Boolean,
    model: Model?,
): SearchMode =
    when {
        model?.tools?.contains(BuiltInTools.Search) == true -> SearchMode.BUILT_IN
        enableSearch -> SearchMode.LOCAL
        else -> SearchMode.OFF
    }

internal fun applySearchModeChange(
    mode: SearchMode,
    model: Model?,
    settingsStore: SettingsStore,
    scope: CoroutineScope,
    onToggleLocalSearch: (Boolean) -> Unit,
) {
    val wantBuiltIn = mode == SearchMode.BUILT_IN
    val wantLocal = mode == SearchMode.LOCAL
    if (model != null) {
        val currentlyBuiltIn = model.tools.contains(BuiltInTools.Search)
        if (currentlyBuiltIn != wantBuiltIn) {
            scope.launch {
                settingsStore.update { current ->
                    current.copy(
                        providers =
                            current.providers.map { providerSetting ->
                                providerSetting.editModel(
                                    model.copy(
                                        tools =
                                            if (wantBuiltIn) {
                                                model.tools + BuiltInTools.Search
                                            } else {
                                                model.tools - BuiltInTools.Search
                                            },
                                    ),
                                )
                            },
                    )
                }
            }
        }
    }
    onToggleLocalSearch(wantLocal)
}

@Composable
fun SearchPickerButton(
    enableSearch: Boolean,
    settings: Settings,
    modifier: Modifier = Modifier,
    onToggleSearch: (Boolean) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    model: Model?,
) {
    var showSearchPicker by remember { mutableStateOf(false) }
    var selectingProvider by remember { mutableStateOf(false) }
    val currentService = settings.searchServices.getOrNull(settings.searchServiceSelected)
    val settingsStore = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()

    ToggleSurface(
        modifier = modifier,
        checked = enableSearch || model?.tools?.contains(BuiltInTools.Search) == true,
        onClick = {
            selectingProvider = false
            showSearchPicker = true
        },
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (model?.tools?.contains(BuiltInTools.Search) == true) {
                    Icon(
                        imageVector = HugeIcons.AiSearch02,
                        contentDescription = stringResource(R.string.use_web_search),
                    )
                } else if (enableSearch && currentService != null) {
                    AutoAIIcon(
                        name = currentService.displayName,
                        color = Color.Transparent,
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.Search01,
                        contentDescription = stringResource(R.string.use_web_search),
                    )
                }
            }
        }
    }

    if (showSearchPicker) {
        ModalBottomSheet(
            onDismissRequest = {
                showSearchPicker = false
                selectingProvider = false
            },
            sheetState =
                rememberBottomSheetState(
                    initialValue = SheetValue.Hidden,
                    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                ),
        ) {
            AnimatedContent(
                targetState = selectingProvider,
                transitionSpec = {
                    if (targetState) {
                        slideInHorizontally { it } + fadeIn() togetherWith
                            slideOutHorizontally { -it } + fadeOut()
                    } else {
                        slideInHorizontally { -it } + fadeIn() togetherWith
                            slideOutHorizontally { it } + fadeOut()
                    }
                },
            ) { isProviderPage ->
                if (isProviderPage) {
                    SearchProviderPicker(
                        settings = settings,
                        onUpdateSearchService = { index ->
                            onUpdateSearchService(index)
                            selectingProvider = false
                        },
                        onBack = { selectingProvider = false },
                    )
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                    ) {
                        SearchPicker(
                            title = stringResource(R.string.use_web_search),
                            enableSearch = enableSearch,
                            settings = settings,
                            model = model,
                            onUpdateSearchMode = { mode ->
                                applySearchModeChange(
                                    mode = mode,
                                    model = model,
                                    settingsStore = settingsStore,
                                    scope = scope,
                                    onToggleLocalSearch = onToggleSearch,
                                )
                            },
                            onSelectProvider = { selectingProvider = true },
                            onDismiss = { showSearchPicker = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchPickerListItem(
    enableSearch: Boolean,
    settings: Settings,
    model: Model?,
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val mode = searchModeOf(enableSearch, model)
    val currentService = settings.searchServices.getOrNull(settings.searchServiceSelected)

    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        modifier = modifier,
        leadingContent = {
            if (mode == SearchMode.LOCAL && currentService != null) {
                AutoAIIcon(
                    name = currentService.displayName,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                CardGroupIcon(
                    icon = mode.icon(),
                    containerColor =
                        if (mode != SearchMode.OFF) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    contentColor =
                        if (mode != SearchMode.OFF) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    containerSize = 32.dp,
                    iconSize = 16.dp,
                )
            }
        },
        supportingContent = {
            Text(
                text =
                    when (mode) {
                        SearchMode.OFF -> stringResource(R.string.search_picker_status_off)
                        SearchMode.LOCAL ->
                            currentService?.displayName
                                ?: stringResource(R.string.search_picker_local_title)
                        SearchMode.BUILT_IN -> stringResource(R.string.search_picker_model_title)
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Icon(HugeIcons.ArrowRight01, contentDescription = null)
        },
    ) {
        Text(stringResource(R.string.use_web_search))
    }
}

@Composable
internal fun SearchPickerPage(
    enableSearch: Boolean,
    settings: Settings,
    model: Model?,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onSelectProvider: () -> Unit,
    onBack: () -> Unit,
    onDismissAll: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        SheetHeader(
            title = stringResource(R.string.use_web_search),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                }
            },
        )
        SearchPicker(
            title = null,
            enableSearch = enableSearch,
            settings = settings,
            model = model,
            onUpdateSearchMode = onUpdateSearchMode,
            onSelectProvider = onSelectProvider,
            onDismiss = onDismissAll,
        )
    }
}

@Composable
private fun SearchPicker(
    title: String?,
    enableSearch: Boolean,
    settings: Settings,
    model: Model?,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onSelectProvider: () -> Unit,
    onDismiss: () -> Unit,
) {
    val navBackStack = LocalNavController.current
    val provider = model?.findProvider(settings.providers)
    val supportsBuiltInSearch =
        provider is ProviderSetting.Google ||
            (provider is ProviderSetting.OpenAI && provider.useResponseApi)
    val currentMode = searchModeOf(enableSearch, model)
    val showModelSearch = model != null && (supportsBuiltInSearch || currentMode == SearchMode.BUILT_IN)
    val modes =
        buildList {
            add(SearchMode.OFF)
            add(SearchMode.LOCAL)
            if (showModelSearch) add(SearchMode.BUILT_IN)
        }

    val currentService = settings.searchServices.getOrNull(settings.searchServiceSelected)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(bottom = 16.dp),
    ) {
        val enabled = currentMode != SearchMode.OFF
        PickerValueHeader(
            title = title,
            value = currentMode,
            hint =
                when (currentMode) {
                    SearchMode.OFF -> stringResource(R.string.search_picker_off_description)
                    SearchMode.LOCAL -> stringResource(R.string.search_picker_local_description)
                    SearchMode.BUILT_IN -> stringResource(R.string.search_picker_model_description)
                },
            modifier = Modifier.padding(bottom = 16.dp),
            label = {
                when (it) {
                    SearchMode.OFF -> stringResource(R.string.search_picker_status_off)
                    SearchMode.LOCAL -> stringResource(R.string.search_picker_local_title)
                    SearchMode.BUILT_IN -> stringResource(R.string.search_picker_model_title)
                }
            },
        ) {
            PickerHero(
                shapes = modeShapes,
                index = currentMode.ordinal,
                icon = currentMode.icon(),
                containerColor =
                    if (enabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                contentColor =
                    if (enabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            modes.fastForEachIndexed { index, mode ->
                val selected = mode == currentMode
                val weight by animateFloatAsState(
                    targetValue = if (selected) 1.3f else 1f,
                    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                )
                ToggleButton(
                    checked = selected,
                    onCheckedChange = { if (!selected) onUpdateSearchMode(mode) },
                    modifier =
                        Modifier
                            .weight(weight)
                            .heightIn(min = 52.dp)
                            .semantics { role = Role.RadioButton },
                    shapes =
                        when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                    colors =
                        ToggleButtonDefaults.toggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    AnimatedVisibility(
                        visible = selected,
                        enter = expandHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(),
                        exit = shrinkHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
                    ) {
                        Row {
                            Icon(
                                imageVector = mode.icon(),
                                contentDescription = null,
                                modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                        }
                    }
                    Text(
                        text =
                            when (mode) {
                                SearchMode.OFF -> stringResource(R.string.search_picker_off)
                                SearchMode.LOCAL -> stringResource(R.string.search_picker_local_title)
                                SearchMode.BUILT_IN -> stringResource(R.string.search_picker_model_title)
                            },
                        autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 14.sp),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = currentMode == SearchMode.LOCAL,
            enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(),
            exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
        ) {
            SegmentedListItem(
                onClick = onSelectProvider,
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                modifier = Modifier.padding(top = 12.dp),
                leadingContent = {
                    if (currentService != null) {
                        AutoAIIcon(
                            name = currentService.displayName,
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        Icon(HugeIcons.GlobalSearch, contentDescription = null)
                    }
                },
                supportingContent =
                    currentService?.let {
                        { SearchAbilityTagLine(options = it) }
                    },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                onDismiss()
                                navBackStack.navigate(Screen.SettingSearch)
                            },
                        ) {
                            Icon(
                                imageVector = HugeIcons.Settings03,
                                contentDescription = stringResource(R.string.search_picker_title),
                            )
                        }
                        Icon(HugeIcons.ArrowRight01, contentDescription = null)
                    }
                },
            ) {
                Text(
                    text =
                        currentService?.displayName
                            ?: stringResource(R.string.search_picker_select_provider),
                )
            }
        }
    }
}

private fun SearchMode.icon(): ImageVector =
    when (this) {
        SearchMode.OFF -> HugeIcons.SearchRemove
        SearchMode.LOCAL -> HugeIcons.GlobalSearch
        SearchMode.BUILT_IN -> HugeIcons.AiSearch02
    }

private fun SearchMode.shape(): RoundedPolygon =
    when (this) {
        SearchMode.OFF -> MaterialShapes.Circle
        SearchMode.LOCAL -> MaterialShapes.Cookie6Sided
        SearchMode.BUILT_IN -> MaterialShapes.Cookie9Sided
    }

@Composable
internal fun SearchProviderPicker(
    settings: Settings,
    onUpdateSearchService: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val services = settings.searchServices
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(horizontal = 16.dp),
    ) {
        SheetHeader(
            title = stringResource(R.string.search_picker_select_provider),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                }
            },
        )
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .selectableGroup(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            itemsIndexed(services, key = { _, service -> service.id }) { index, service ->
                val selected = settings.searchServiceSelected == index
                SegmentedListItem(
                    selected = selected,
                    onClick = { onUpdateSearchService(index) },
                    shapes = ListItemDefaults.segmentedShapes(index = index, count = services.size),
                    leadingContent = {
                        AutoAIIcon(
                            name = service.displayName,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    supportingContent = {
                        SearchAbilityTagLine(options = service)
                    },
                    trailingContent = {
                        RadioButton(selected = selected, onClick = null)
                    },
                ) {
                    Text(service.displayName)
                }
            }
        }
    }
}
