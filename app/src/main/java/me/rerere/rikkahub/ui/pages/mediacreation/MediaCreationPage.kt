package me.rerere.rikkahub.ui.pages.mediacreation

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.MediaCreationNode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * 一个媒体创作会话：一条时间线，最新的记录在最下面，紧挨着输入区，上一轮的产出可以直接放回输入区继续用。
 * 再来一次、修改后重新生成的结果留在原来那条记录的位置上，作为它的另一个版本。
 */
@Composable
fun MediaCreationPage(id: String) {
    val vm: MediaCreationVM = koinViewModel(parameters = { parametersOf(id) })
    val navController = LocalNavController.current
    val toaster = LocalToaster.current

    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val uploadConfigured by vm.uploadConfigured.collectAsStateWithLifecycle()
    val recentOutputs by vm.recentOutputs.collectAsStateWithLifecycle()
    val selectedNodes by vm.selectedNodes.collectAsStateWithLifecycle()
    val nodes = vm.nodes.collectAsLazyPagingItems()

    var showUploadDialog by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedNodes != null) {
        vm.endSelection()
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is MediaCreationEvent.Error -> toaster.show(event.message, type = ToastType.Error)
                MediaCreationEvent.UploadNotConfigured -> showUploadDialog = true
                MediaCreationEvent.SessionGone -> navController.popBackStack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedNodes?.let { stringResource(R.string.media_creation_page_selected_count, it.size) }
                            ?: session?.title?.ifBlank { null }
                            ?: stringResource(R.string.media_creation_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (selectedNodes != null) {
                        val exitLabel = stringResource(R.string.media_creation_page_exit_selection)
                        IconButton(onClick = vm::endSelection) {
                            Icon(HugeIcons.Cancel01, contentDescription = exitLabel)
                        }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    if (selectedNodes == null && nodes.itemCount > 0) {
                        val label = stringResource(R.string.media_creation_page_select)
                        IconButton(onClick = { vm.startSelection() }) {
                            Icon(HugeIcons.CheckList, contentDescription = label)
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        // 底部的安全区和键盘留给输入区自己处理，它的背景要一直铺到屏幕底边
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                )
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                MediaCreationTimeline(
                    vm = vm,
                    nodes = nodes,
                    editingNodeId = draft.editingNodeId,
                    selectedNodes = selectedNodes,
                    hasModels = settings.mediaGenerationProviders.any { it.models.isNotEmpty() },
                    onOpenMediaSettings = { navController.navigate(Screen.SettingMedia) },
                )
            }
            val selected = selectedNodes
            if (selected != null) {
                MediaCreationSelectionBar(
                    vm = vm,
                    selectedCount = selected.size,
                    allSelected = nodes.itemCount > 0 && selected.size >= nodes.itemCount,
                    onDelete = { confirmDeleteSelected = true },
                )
            } else {
                MediaCreationComposer(
                    vm = vm,
                    draft = draft,
                    selection = selection,
                    providers = settings.mediaGenerationProviders,
                    recentOutputs = recentOutputs,
                    uploadConfigured = uploadConfigured,
                    onOpenMediaSettings = { navController.navigate(Screen.SettingMedia) },
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = confirmDeleteSelected,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            confirmDeleteSelected = false
            vm.deleteSelected()
        },
        onDismiss = { confirmDeleteSelected = false },
    ) {
        Text(stringResource(R.string.media_creation_page_delete_selected_text, selectedNodes?.size ?: 0))
    }

    RikkaConfirmDialog(
        show = showUploadDialog,
        title = stringResource(R.string.media_creation_page_upload_dialog_title),
        confirmText = stringResource(R.string.media_creation_page_go_configure),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showUploadDialog = false
            navController.navigate(Screen.SettingMedia)
        },
        onDismiss = { showUploadDialog = false },
    ) {
        Text(stringResource(R.string.media_creation_page_upload_dialog_text))
    }
}

@Composable
private fun MediaCreationTimeline(
    vm: MediaCreationVM,
    nodes: LazyPagingItems<MediaCreationNode>,
    editingNodeId: Uuid?,
    selectedNodes: Set<Uuid>?,
    hasModels: Boolean,
    onOpenMediaSettings: () -> Unit,
) {
    val listState = rememberLazyListState()

    // 列表是倒序排布的，第 0 项就是最下面那条最新的记录。停在底部时，新记录出现后跟着露出来；
    // 往上翻看旧记录时不打扰。
    val newestId = nodes.newestId()
    LaunchedEffect(newestId) {
        if (newestId != null && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    // 自己提交的记录无论当前翻到哪里都滚过去；新版本出现在原来那一项上，它还在屏幕里时不动
    LaunchedEffect(vm) {
        vm.submitted.collect { submitted ->
            val key = submitted.nodeId.toString()
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == key }) {
                listState.scrollToItem(submitted.position)
            }
        }
    }

    if (nodes.loadState.refresh is LoadState.NotLoading && nodes.itemCount == 0) {
        TimelineEmpty(hasModels = hasModels, onOpenMediaSettings = onOpenMediaSettings)
        return
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            count = nodes.itemCount,
            // 以项为单位：切换版本时原地换内容，不会被当成另一项
            key = nodes.itemKey { it.id.toString() },
        ) { index ->
            val node = nodes[index]
            if (node != null) {
                MediaCreationRecordItem(
                    node = node,
                    editing = node.id == editingNodeId,
                    selected = selectedNodes?.let { node.id in it },
                    vm = vm,
                )
            } else {
                // 还没加载到的记录先占位，保证列表总长度和滚动位置稳定
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {}
            }
        }
    }
}

private fun LazyPagingItems<MediaCreationNode>.newestId(): Uuid? =
    if (itemCount > 0) peek(0)?.id else null

@Composable
private fun TimelineEmpty(
    hasModels: Boolean,
    onOpenMediaSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (hasModels) {
            Text(
                text = stringResource(R.string.media_creation_page_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text = stringResource(R.string.media_creation_page_no_providers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenMediaSettings) {
                Text(stringResource(R.string.media_creation_page_go_configure))
            }
        }
    }
}

/** 多选时代替输入区：退出选择、全选/取消全选，以及对所选产出的批量操作。 */
@Composable
// IO/权限/空间类异常都会落到这里展示；按具体类型枚举无收益
@Suppress("TooGenericExceptionCaught")
private fun MediaCreationSelectionBar(
    vm: MediaCreationVM,
    selectedCount: Int,
    allSelected: Boolean,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    // 资源取值走 LocalResources（configuration-aware）：直接 context.getString 会被 lint 报 LocalContextGetResourceValueCall
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 8.dp)
            .padding(bottom = 8.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val exitLabel = stringResource(R.string.media_creation_page_exit_selection)
            IconButton(onClick = vm::endSelection, enabled = !saving) {
                Icon(HugeIcons.Cancel01, contentDescription = exitLabel)
            }
            TextButton(
                onClick = { if (allSelected) vm.clearSelection() else vm.selectAll() },
                enabled = !saving,
            ) {
                Text(stringResource(if (allSelected) R.string.media_creation_page_deselect_all else R.string.media_creation_page_select_all))
            }
            Spacer(Modifier.weight(1f))
            val saveLabel = stringResource(if (saving) R.string.media_creation_page_saving else R.string.media_creation_page_save)
            IconButton(
                onClick = {
                    scope.launch {
                        saving = true
                        try {
                            val result = saveSelected(context, vm)
                            when {
                                result.total == 0 -> toaster.show(
                                    resources.getString(R.string.media_creation_page_nothing_to_save),
                                    type = ToastType.Info,
                                )
                                result.failures.isEmpty() -> toaster.show(
                                    resources.getString(R.string.media_creation_page_saved_files, result.saved),
                                    type = ToastType.Success,
                                )
                                result.saved == 0 -> toaster.show(
                                    result.failures.first().message ?: resources.getString(R.string.media_creation_page_gallery_write_failed),
                                    type = ToastType.Error,
                                )
                                else -> toaster.show(
                                    resources.getString(
                                        R.string.media_creation_page_saved_files_partial,
                                        result.saved,
                                        result.failures.size,
                                        result.failures.first().message ?: resources.getString(R.string.media_creation_page_gallery_write_failed),
                                    ),
                                    type = ToastType.Error,
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            toaster.show(
                                e.message ?: resources.getString(R.string.media_creation_page_gallery_write_failed),
                                type = ToastType.Error,
                            )
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = selectedCount > 0 && !saving,
            ) {
                Icon(HugeIcons.Download01, contentDescription = saveLabel)
            }
            val deleteLabel = stringResource(R.string.delete)
            IconButton(
                onClick = onDelete,
                enabled = selectedCount > 0 && !saving,
            ) {
                Icon(HugeIcons.Delete01, contentDescription = deleteLabel, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 逐项保存当前成功版本的产出；单项失败不会阻止其余文件写入。 */
private data class MediaCreationSaveResult(
    val total: Int,
    val saved: Int,
    val failures: List<Exception>,
)

// 批量保存：单个失败只记入 failures 不中断后续，宽捕获是有意的
@Suppress("TooGenericExceptionCaught")
private suspend fun saveSelected(context: Context, vm: MediaCreationVM): MediaCreationSaveResult {
    val outputs = vm.getSelectedOutputs()
    var saved = 0
    val failures = mutableListOf<Exception>()
    outputs.forEach { output ->
        try {
            saveMediaToGallery(context, vm.resolve(output.path), output.mimeType)
            saved++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += e
        }
    }
    return MediaCreationSaveResult(outputs.size, saved, failures)
}
