package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.diagnoseFailure
import me.rerere.ai.registry.ModelRegistry
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.rikkahub.data.ai.catalog.ModelCatalogRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "SettingProviderModelList"

@Composable
internal fun ModelList(
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
    // 手动刷新触发器：拉取失败后点“重试”或空态点“拉取模型列表”时 +1，重新拉取。
    var refreshTrigger by remember { mutableIntStateOf(0) }
    // 拉取失败的原因（中文分类标签）：页内显示，原始报错只进日志。
    var modelLoadError by remember { mutableStateOf<String?>(null) }
    // 远程模型列表是否正在拉取：初始 true；produceState 的初始值是空列表，加载态与空态
    // 共用它的话，进页先闪一下“没有模型”，过一会儿列表才突然出现。
    var modelListLoading by remember { mutableStateOf(true) }
    // 拉取 key 只取 (providerId, baseUrl)：改 key（逐字回写）、改名、拖动排序都不应重拉 /models。
    val modelListBaseUrl = providerSetting.modelListBaseUrl()
    val modelList by produceState(emptyList(), providerSetting.id, modelListBaseUrl, refreshTrigger) {
        modelListLoading = true
        modelLoadError = null
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
            AppLog.e(TAG, "ModelList: listModels failed", error)
            // 失败只在页内显示原因与重试入口，不再只弹一个 Toast 后留空列表。
            modelLoadError = diagnoseFailure(context, error).label
        }
        modelListLoading = false
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
        // 内容一致就不重置：提交后 providerSetting 回流时，清空重建会白白触发整列重组（拖动中尤其明显）
        if (localModels != providerSetting.models) {
            localModels.clear()
            localModels.addAll(providerSetting.models)
        }
    }
    var reorderCommitJob by remember { mutableStateOf<Job?>(null) }
    // 「正在拖动」标志：只给协程读，故意不用 Compose state（手势回调里写 state 会触发重组）
    val draggingNow = remember { booleanArrayOf(false) }
    // 有未落盘的顺序改动（拖动只改本地列表）。同样用数组：只在回调/协程里读写
    val orderDirty = remember { booleanArrayOf(false) }
    // 提交时取「最新」的 providerSetting：延迟提交若用组合期捕获的旧快照，
    // 会把期间发生的其它改动（如正在编辑的模型）覆盖回去（＝“填写没保存”的一类）
    val latestProvider by rememberUpdatedState(providerSetting)
    val commitOrder: () -> Unit = {
        if (orderDirty[0]) {
            orderDirty[0] = false
            val current = latestProvider
            // 按本地顺序重排当前模型集合；期间新增/删除的项原样保留在尾部
            val byOrder =
                localModels.mapNotNull { m ->
                    current.models.find { it.id == m.id }
                }
            val rest =
                current.models.filter { m ->
                    byOrder.none { it.id == m.id }
                }
            val reordered = byOrder + rest
            if (reordered != current.models) {
                onUpdateProvider(current.copyProvider(models = reordered))
            }
        }
    }
    // 拖动性能埋点（仅 debug 构建生效）：记录每格移动的处理耗时、相邻移动间隔、帧节奏、渲染细分与重组次数，停手后落 ReorderPerf 日志
    val activity = LocalActivity.current
    val reorderPerf = remember(activity) { ReorderPerfTracker(activity?.window) }
    DisposableEffect(reorderPerf) {
        onDispose {
            // 兜底提交：拖完立刻退出页面时，上面那个 400ms 的提交协程会被取消 → 不补这一步顺序就丢了
            reorderCommitJob?.cancel()
            reorderPerf.flush()
            commitOrder()
            reorderPerf.dispose()
        }
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
                orderDirty[0] = true
                reorderCommitJob?.cancel()
                reorderCommitJob =
                    scope.launch {
                        delay(400)
                        reorderPerf.flush()
                        // 用户又接着拖时先不提交：写设置会触发整列重组，落在拖动里就是一次明显卡顿
                        // （拖动会话里能观测到一次 ~170ms 长帧，多半就是这种“提交压在拖动上”）
                        var waitedMs = 0
                        while (draggingNow[0] && waitedMs < 3000) {
                            delay(150)
                            waitedMs += 150
                        }
                        commitOrder()
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
                        if (catalogStatus.isStale) {
                            Text(
                                text = stringResource(R.string.setting_provider_page_catalog_status_stale),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
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

            // 拉取失败：页内显示原因与重试入口。
            if (modelLoadError != null) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = modelLoadError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(onClick = { refreshTrigger++ }) {
                            Text(stringResource(R.string.common_retry))
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
                        if (modelListLoading) {
                            // 远程模型列表还在拉取：先转圈，别先闪“没有模型”；
                            // 拉完确实为空时再显示空态。
                            CircularProgressIndicator()
                        } else {
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
                            Button(
                                onClick = { refreshTrigger++ },
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                Text(stringResource(R.string.setting_provider_page_fetch_models))
                            }
                        }
                    }
                }
            } else {
                items(localModels, key = { it.id }) { item ->
                    // 拖动性能埋点：统计本次拖动会话内的卡片重组次数（仅 debug 构建，release 不进入组合）
                    if (reorderPerf.enabled) {
                        SideEffect { ReorderRecomposeCounter.count++ }
                    }
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
                                            mod.longPressDraggableHandle(
                                                onDragStarted = { draggingNow[0] = true },
                                                onDragStopped = {
                                                    draggingNow[0] = false
                                                    // 拖完立即提交（不等计时器）： ① 拖完就走不会丢顺序；② 提交不会落到下一次拖动里
                                                    reorderCommitJob?.cancel()
                                                    commitOrder()
                                                },
                                            )
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
            colors = CustomColors.floatingToolbarColors,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -ScreenOffset),
        ) {
            AddModelButton(
                models = modelList,
                loading = modelListLoading,
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

/** 目录上次更新时间：时间戳为 0 表示尚未拉取，显示占位。 */
private fun formatCatalogTime(ms: Long): String =
    if (ms <= 0L) {
        "—"
    } else {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
    }

/** 模型列表拉取 key 用的 baseUrl：没有 baseUrl 的类型返回空串。 */
private fun ProviderSetting.modelListBaseUrl(): String =
    when (this) {
        is ProviderSetting.OpenAI -> baseUrl
        is ProviderSetting.Google -> baseUrl
        is ProviderSetting.Claude -> baseUrl
        is ProviderSetting.Backend -> baseUrl
        else -> ""
    }
