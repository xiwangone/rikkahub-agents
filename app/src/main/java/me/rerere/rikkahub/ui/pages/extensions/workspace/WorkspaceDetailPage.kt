package me.rerere.rikkahub.ui.pages.extensions.workspace

import com.dokar.sonner.ToastType
import android.content.Intent
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.repository.ChrootEnableFailure
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.workspace.WorkspaceFileEntry
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 工作区详情中枢：工作区环境管理、Rootfs 安装与状态、文件浏览与操作、终端与镜像设置。
 *
 * 本文件很长（2500+ 行）。阅读与维护时请按下面的段落名检索符号，不要依赖行号：
 *  - 页面入口与主导航：[WorkspaceDetailPage]（两页 HorizontalPager：基本信息 / 文件系统）
 *  - 基本信息页：[WorkspaceBasicPage] / [WorkspaceInfoRow] / [ShellStatusPanel] / [RootfsProgress]
 *  - 工具审批与权限：[WorkspaceToolApprovalCard] / [workspaceToolApprovalItems]
 *  - Rootfs 安装与镜像源：[InstallRootfsDialog] / [MirrorPickerDialog] / [MirrorRow] / [presetRootfs]
 *  - 文件系统主面板：[WorkspaceFilesPage] / [WorkspaceAreaSelector] / [WorkspacePathBar] / [WorkspaceClipboardBar]
 *  - 文件列表与卡片：[WorkspaceFileCard] / [WorkspaceSortBar] / [sortWorkspaceEntries] / [fileMetaLine]
 *  - 文件对话框：[WorkspaceRenameDialog] / [WorkspaceDirectoryPickerDialog] / [TagEditDialog]
 *
 * 相关文件：状态与导出 [WorkspaceDetailVM]；文件编辑器 [WorkspaceFileEditorPage]；终端 [WorkspaceTerminalPage]。
 */
@Composable
fun WorkspaceDetailPage(id: String) {
    val navController = LocalNavController.current
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    val folderExportProgress by vm.folderExportProgress.collectAsStateWithLifecycle()
    val settingsError by vm.settingsError.collectAsStateWithLifecycle()
    val caRepairCount by vm.caRepairCount.collectAsStateWithLifecycle()
    val sdcardEnabled by vm.sdcardEnabled.collectAsStateWithLifecycle()
    val chrootEnabled by vm.chrootEnabled.collectAsStateWithLifecycle()
    val chrootBackendReady = vm.chrootBackendReady
    val stats by vm.stats.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var renameTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var moveTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var showPasteOverwrite by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }
    val mirrors by vm.mirrors.collectAsStateWithLifecycle()
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    var openingFileName by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
    val filePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val fileName =
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    } else {
                        null
                    }
                } ?: uri.lastPathSegment ?: "imported_file"
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
            vm.importFile(inputStream, fileName)
        }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var exportFolderTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var exportZipTargets by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var selectedWorkspacePaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    val clipboard = LocalClipboardManager.current
    val exportZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val entries = exportZipTargets.also { exportZipTargets = emptyList() }
        if (uri == null || entries.isEmpty()) return@rememberLauncherForActivityResult
        val output = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
        if (output == null) {
            toaster.show(resources.getString(R.string.workspace_detail_export_zip_failed, "cannot open destination"))
        } else {
            vm.exportEntriesZip(entries, output, onCancelled = {
                runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.delete() }
            }) { result ->
                selectedWorkspacePaths = emptySet()
                toaster.show(if (result.isSuccess) resources.getString(R.string.workspace_detail_export_zip_done) else resources.getString(R.string.workspace_detail_export_zip_failed, result.exceptionOrNull()?.message.orEmpty()))
            }
        }
    }
    val exportFolderLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            val entry =
                exportFolderTarget.also { exportFolderTarget = null }
                    ?: return@rememberLauncherForActivityResult
            if (uri == null) return@rememberLauncherForActivityResult
            val tree =
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                    ?: return@rememberLauncherForActivityResult
            // 在所选目录下新建同名文件夹，避免把内容直接铺进用户选中的目录
            val targetDir = tree.createDirectory(entry.name) ?: tree
            vm.exportFolder(entry, targetDir) { outcome ->
                toaster.show(
                    resources.getString(
                        R.string.workspace_detail_export_folder_done,
                        outcome.fileCount,
                        outcome.failures,
                    ),
                )
            }
        }
    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("*/*"),
        ) { uri ->
            val entry = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
            if (uri == null) return@rememberLauncherForActivityResult
            vm.exportFile(entry, uri, context.contentResolver)
        }

    BackHandler(enabled = pagerState.currentPage == 1 && state.path.isNotBlank()) {
        vm.goUp()
    }

    // 导出进行中：拦截返回键，避免中途离开触发取消（与 exportFolder 的取消安全处理配套）。
    // 必须晚于上面的 BackHandler 声明，导出时才优先拦截。
    BackHandler(enabled = folderExportProgress != null) {
        // 有意为空：仅拦截
    }

    folderExportProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = { },
            title = {
                Text(
                    stringResource(
                        R.string.workspace_detail_folder_export_progress_title,
                        progress.folderName,
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(
                        progress = {
                            (progress.done.toFloat() / progress.total.coerceAtLeast(1))
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(
                            R.string.workspace_detail_folder_export_progress_count,
                            progress.done,
                            progress.total,
                        ),
                    )
                    Text(
                        text = stringResource(R.string.workspace_detail_folder_export_progress_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.workspace?.name ?: stringResource(R.string.workspace_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (pagerState.currentPage == 1) {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(
                                HugeIcons.FileImport,
                                contentDescription = stringResource(R.string.workspace_detail_import_file),
                            )
                        }
                    }
                    IconButton(onClick = { vm.refreshStats() }) {
                        Icon(
                            HugeIcons.Refresh01,
                            contentDescription = stringResource(R.string.accessibility_refresh_workspace),
                        )
                    }
                    if (state.workspace?.supportsTerminal() == true) {
                        IconButton(onClick = { navController.navigate(Screen.WorkspaceTerminal(id)) }) {
                            Icon(
                                HugeIcons.ComputerTerminal01,
                                contentDescription = stringResource(R.string.workspace_detail_terminal),
                            )
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    label = { Text(stringResource(R.string.workspace_detail_tab_basic)) },
                    icon = { Icon(HugeIcons.Settings03, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    label = { Text(stringResource(R.string.workspace_detail_tab_files)) },
                    icon = { Icon(HugeIcons.File02, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> {
                    WorkspaceBasicPage(
                        workspace = state.workspace,
                        distro = state.distro,
                        stats = stats,
                        installProgress = installProgress,
                        onEditSsh = { navController.navigate(Screen.WorkspaceSshEditor(id)) },
                        mirrors = mirrors,
                        onInstallRootfs = { showInstallDialog = true },
                        onToolApprovalChange = vm::setToolApproval,
                        onShellCompatibilityModeChange = vm::setShellCompatibilityMode,
                        onApplyMirrors = vm::applyMirrors,
                        onRepairCaCerts = vm::repairCaCerts,
                        sdcardEnabled = sdcardEnabled,
                        onSdcardAccessChange = vm::setSdcardAccess,
                        chrootEnabled = chrootEnabled,
                        chrootBackendReady = chrootBackendReady,
                        onChrootEnabledChange = vm::setChrootEnabled,
                        onSetTags = vm::setTags,
                    )
                }

                1 -> {
                    WorkspaceFilesPage(
                        state = state,
                        contentPadding = PaddingValues(),
                        onSelectArea = { selectedWorkspacePaths = emptySet(); vm.selectArea(it) },
                        onGoUp = { selectedWorkspacePaths = emptySet(); vm.goUp() },
                        onResolveImage = { entry, area -> vm.resolveImageFile(entry, area) },
                        onOpen = { entry ->
                            when {
                                entry.isDirectory -> {
                                    vm.open(entry)
                                }

                                // svg：直接进编辑器用 WebView 预览（优先于图片弹窗）
                                entry.name.substringAfterLast('.').equals("svg", ignoreCase = true) -> {
                                    navController.navigate(
                                        Screen.WorkspaceFileEditor(id, state.area.name, entry.path),
                                    )
                                }

                                else -> {
                                    when (entry.detectFileType()) {
                                        WorkspaceFileType.TEXT -> {
                                            navController.navigate(
                                                Screen.WorkspaceFileEditor(id, state.area.name, entry.path),
                                            )
                                        }

                                        WorkspaceFileType.IMAGE -> {
                                            vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                                // 传绝对路径 (而非 content:// URI): Coil 可直接加载,
                                                // 预览弹窗的保存按钮 saveMessageImage 只认 "/" 开头路径, content URI 会报错
                                                previewImageUri = file.absolutePath
                                            }
                                        }

                                        WorkspaceFileType.OTHER -> {
                                            openingFileName = entry.name
                                            vm.exportToCacheFile(
                                                entry,
                                                context.cacheDir,
                                                onReady = { file ->
                                                    openingFileName = null
                                                    val uri =
                                                    FileProvider.getUriForFile(
                                                        context,
                                                        "${context.packageName}.fileprovider",
                                                        file,
                                                    )
                                                val mime =
                                                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                                        file.extension.lowercase(),
                                                    ) ?: "*/*"
                                                val intent =
                                                    Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(uri, mime)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                runCatching {
                                                    context.startActivity(Intent.createChooser(intent, null))
                                                }
                                            },
                                                onFail = { openingFileName = null },
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        onDelete = { deleteTarget = it },
                        onCopy = { entry ->
                            vm.copyToClipboard(entry)
                            toaster.show(resources.getString(R.string.workspace_detail_clipboard_copied, entry.name))
                        },
                        onMove = { moveTarget = it },
                        onRename = { renameTarget = it },
                        onExportZip = { entry ->
                            exportZipTargets = listOf(entry)
                            exportZipLauncher.launch("${entry.name}.zip")
                        },
                        onCopyPath = { entry, area ->
                            val absolutePath = workspaceRootfsAbsolutePath(area, entry.path)
                            runCatching { clipboard.setText(AnnotatedString(absolutePath)) }
                                .onSuccess { toaster.show(resources.getString(R.string.workspace_detail_path_copied)) }
                                .onFailure { toaster.show(resources.getString(R.string.workspace_detail_path_copy_failed)) }
                        },
                        onExport = { entry ->
                            if (entry.isDirectory) {
                                // 目录：整棵子树导出到用户选定的目录（SAF 目录树）
                                exportFolderTarget = entry
                                exportFolderLauncher.launch(null)
                            } else {
                                exportTarget = entry
                                exportLauncher.launch(entry.name)
                            }
                        },
                        onShare = { entry ->
                            vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                val uri =
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                val intent =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/octet-stream"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        },
                        selectedPaths = selectedWorkspacePaths,
                        onToggleSelection = { entry ->
                            val key = "${state.area.name}:${entry.path}"
                            selectedWorkspacePaths = if (key in selectedWorkspacePaths) selectedWorkspacePaths - key else selectedWorkspacePaths + key
                        },
                        onSelectAll = {
                            selectedWorkspacePaths = if (selectedWorkspacePaths.size == state.entries.size) emptySet()
                            else state.entries.mapTo(mutableSetOf()) { "${state.area.name}:${it.path}" }
                        },
                        onExportSelection = {
                            val selected = state.entries.filter { "${state.area.name}:${it.path}" in selectedWorkspacePaths }
                            if (selected.isNotEmpty()) {
                                exportZipTargets = selected
                                exportZipLauncher.launch("workspace-selection.zip")
                            }
                        },
                        clipboard = state.clipboard,
                        onPaste = { clip ->
                            // 同名先二次确认（覆盖是危险操作），无冲突直接落盘
                            if (state.entries.any { it.name == clip.name }) showPasteOverwrite = true else vm.paste(overwrite = false)
                        },
                        onClearClipboard = vm::clearClipboard,
                    )
                }
            }
        }
    }

    state.workspace?.let { workspace ->
        if (showInstallDialog) {
            InstallRootfsDialog(
                workspace = workspace,
                onDismiss = { showInstallDialog = false },
                onConfirm = { url ->
                    vm.installRootfs(url)
                    showInstallDialog = false
                },
                onConfirmFile = { path ->
                    vm.installRootfsFromFile(path)
                    showInstallDialog = false
                },
            )
        }
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissInstallError,
            title = { Text(stringResource(R.string.workspace_detail_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissInstallError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    settingsError?.let { err ->
        AlertDialog(
            onDismissRequest = vm::dismissSettingsError,
            title = { Text(stringResource(R.string.workspace_detail_settings_save_failed)) },
            text = {
                Column {
                    Text(
                        when (err.reason) {
                            ChrootEnableFailure.ROOT_REQUIRED -> stringResource(R.string.workspace_detail_chroot_need_root)
                            ChrootEnableFailure.NO_WORKSPACE -> stringResource(R.string.workspace_detail_chroot_no_workspace)
                            ChrootEnableFailure.PROBE_FAILED -> stringResource(R.string.workspace_detail_chroot_probe_failed)
                            null -> err.raw.ifBlank { stringResource(R.string.workspace_detail_settings_save_failed) }
                        },
                    )
                    // 已知原因下附技术细节原文（探测输出等），便于排障
                    if (err.reason != null && err.detail.isNotBlank()) {
                        Text(err.detail)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::dismissSettingsError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    val caRepairMessage = caRepairCount?.let { stringResource(R.string.workspace_detail_cacerts_repaired, it) }
    LaunchedEffect(caRepairMessage) {
        if (caRepairMessage != null) {
            toaster.show(message = caRepairMessage, type = ToastType.Success)
            vm.dismissCaRepairHint()
        }
    }

    openingFileName?.let { name ->
        AlertDialog(
            onDismissRequest = {},
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text(stringResource(R.string.workspace_export_preparing, name))
                }
            },
            confirmButton = {},
        )
    }
    previewImageUri?.let { uri ->
        ImagePreviewDialog(
            images = listOf(uri),
            onDismissRequest = { previewImageUri = null },
        )
    }

    deleteTarget?.let { entry ->
        RikkaConfirmDialog(
            show = true,
            title =
                if (entry.isDirectory) {
                    stringResource(
                        R.string.workspace_detail_delete_directory,
                    )
                } else {
                    stringResource(R.string.workspace_detail_delete_file)
                },
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.delete(entry)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.workspace_detail_will_delete, entry.path))
        }
    }

    renameTarget?.let { entry ->
        WorkspaceRenameDialog(
            entry = entry,
            takenNames = state.entries
                .filter { it.path != entry.path }
                .mapTo(mutableSetOf()) { it.name },
            onSubmit = { newName ->
                vm.rename(entry, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    moveTarget?.let { entry ->
        WorkspaceDirectoryPickerDialog(
            blockedPath = if (entry.isDirectory) entry.path else null,
            startPath = state.path,
            onListDirectories = vm::listSubDirectories,
            onConfirm = { targetDir ->
                vm.moveEntry(entry, targetDir)
                moveTarget = null
            },
            onDismiss = { moveTarget = null },
        )
    }

    state.clipboard?.let { clip ->
        if (showPasteOverwrite) {
            RikkaConfirmDialog(
                show = true,
                title = stringResource(R.string.workspace_detail_paste_overwrite_title),
                confirmText = stringResource(R.string.common_confirm),
                dismissText = stringResource(R.string.common_cancel),
                onConfirm = {
                    vm.paste(overwrite = true)
                    showPasteOverwrite = false
                },
                onDismiss = { showPasteOverwrite = false },
            ) {
                Text(
                    stringResource(
                        R.string.workspace_detail_paste_overwrite_message,
                        clip.name,
                        state.path.ifBlank { "/" },
                    ),
                )
            }
        }
    }
}

