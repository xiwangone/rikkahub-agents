package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import me.rerere.rikkahub.ui.context.rememberSafeLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
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
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.workspace.WorkspaceFileEntry
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private enum class WorkspaceDetailTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    BASIC(HugeIcons.Settings03, R.string.workspace_detail_tab_basic),
    FILES(HugeIcons.File02, R.string.workspace_detail_tab_files),
    ;

    override val label: String
        @Composable get() = stringResource(labelRes)
}

/**
 * 工作区详情中枢：工作区环境管理、Rootfs 安装与状态、文件浏览与操作、终端与镜像设置。
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
    val upgradingPackages by vm.upgradingPackages.collectAsStateWithLifecycle()
    val packageUpgradeResult by vm.packageUpgradeResult.collectAsStateWithLifecycle()
    val statsRefreshFailure by vm.statsRefreshFailure.collectAsStateWithLifecycle()
    val sdcardEnabled by vm.sdcardEnabled.collectAsStateWithLifecycle()
    val shellBackend by vm.shellBackend.collectAsStateWithLifecycle()
    val backendFallbackReason by vm.backendFallbackReason.collectAsStateWithLifecycle()
    val chrootEnabled by vm.chrootEnabled.collectAsStateWithLifecycle()
    val chrootBackendReady = vm.chrootBackendReady
    val stats by vm.stats.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { WorkspaceDetailTab.entries.size }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var renameTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var moveTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    // 新建条目对话框：null = 未打开，true = 新建文件夹，false = 新建文件。
    var createEntry by remember { mutableStateOf<Boolean?>(null) }
    var showPasteOverwrite by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val mirrors by vm.mirrors.collectAsStateWithLifecycle()
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    var openingFileName by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
    val filePicker =
        rememberSafeLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberSafeLauncherForActivityResult
            val fileName =
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    } else {
                        null
                    }
                } ?: uri.lastPathSegment ?: "imported_file"
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@rememberSafeLauncherForActivityResult
            vm.importFile(inputStream, fileName)
        }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var exportFolderTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    // 重复导出到同一位置时待确认的导出（目标目录里已有同名文件夹）。
    var pendingFolderExport by remember { mutableStateOf<PendingFolderExport?>(null) }
    var exportZipTargets by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var selectedWorkspacePaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    val clipboard = LocalClipboardManager.current
    val exportZipLauncher =
        rememberSafeLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val entries = exportZipTargets.also { exportZipTargets = emptyList() }
            if (uri == null || entries.isEmpty()) return@rememberSafeLauncherForActivityResult
            val output = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
            if (output == null) {
                toaster.show(resources.getString(R.string.workspace_detail_export_zip_failed, "cannot open destination"))
            } else {
                vm.exportEntriesZip(
                    entries,
                    output,
                    onCancelled = {
                        runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.delete() }
                    },
                ) { result ->
                    selectedWorkspacePaths = emptySet()
                    toaster.show(
                        if (result.isSuccess) {
                            resources.getString(R.string.workspace_detail_export_zip_done)
                        } else {
                            resources.getString(
                                R.string.workspace_detail_export_zip_failed,
                                result.exceptionOrNull()?.message.orEmpty(),
                            )
                        },
                    )
                }
            }
        }
    val exportFolderLauncher =
        rememberSafeLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            val entry =
                exportFolderTarget.also { exportFolderTarget = null }
                    ?: return@rememberSafeLauncherForActivityResult
            if (uri == null) return@rememberSafeLauncherForActivityResult
            val tree =
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                    ?: return@rememberSafeLauncherForActivityResult
            // 目标里已有同名文件夹时不再静默处理：之前 createDirectory 返回 null 就退回
            // 根目录混导（顶层文件进错位置、子目录整棵被跳过），只剩一句失败计数。
            // 先弹确认，让用户选覆盖 / 合并 / 取消。
            val existing = tree.findFile(entry.name)?.takeIf { it.isDirectory }
            if (existing != null) {
                pendingFolderExport = PendingFolderExport(entry, tree, existing)
                return@rememberSafeLauncherForActivityResult
            }
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
        rememberSafeLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("*/*"),
        ) { uri ->
            val entry = exportTarget.also { exportTarget = null } ?: return@rememberSafeLauncherForActivityResult
            if (uri == null) return@rememberSafeLauncherForActivityResult
            vm.exportFile(entry, uri, context.contentResolver)
        }

    val onFilesTab = pagerState.currentPage == WorkspaceDetailTab.FILES.ordinal

    BackHandler(enabled = onFilesTab && state.path.isNotBlank()) {
        vm.goUp()
    }

    BackHandler(enabled = folderExportProgress != null) {
        // 导出进行中拦截返回键，避免中途离开触发取消
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
            // 取消是导出中唯一的退出口：点后取消 VM 任务，已导出的文件保留，
            // 进度弹窗随 _folderExportProgress 清空而关闭。返回键仍拦截，避免误触。
            confirmButton = {
                TextButton(onClick = { vm.cancelFolderExport() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // 重复导出到同一位置：目标里已有同名文件夹，三选一，不再静默合并。
    // 文案直接写中文（没有现成的同名字符串资源）。
    pendingFolderExport?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingFolderExport = null },
            title = { Text("“${pending.entry.name}”已存在") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "目标位置已有同名文件夹，请选择如何处理：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // 三个选择平铺在正文里，各自一行、点即执行并关弹窗。
                    TextButton(
                        onClick = {
                            // 覆盖：先删掉已存在的同名文件夹，再新建并导出。
                            pendingFolderExport = null
                            runCatching { pending.existing.delete() }
                            val targetDir = pending.tree.createDirectory(pending.entry.name) ?: pending.tree
                            vm.exportFolder(pending.entry, targetDir) { outcome ->
                                toaster.show(
                                    resources.getString(
                                        R.string.workspace_detail_export_folder_done,
                                        outcome.fileCount,
                                        outcome.failures,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("覆盖（删除已有文件夹后导出）") }
                    TextButton(
                        onClick = {
                            // 合并：复用已存在的同名文件夹往里导（VM 层 createDirectory
                            // 返回 null 时会复用该目录，子目录不再被跳过）。
                            pendingFolderExport = null
                            vm.exportFolder(pending.entry, pending.existing) { outcome ->
                                toaster.show(
                                    resources.getString(
                                        R.string.workspace_detail_export_folder_done,
                                        outcome.fileCount,
                                        outcome.failures,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("合并到现有文件夹") }
                    TextButton(
                        onClick = { pendingFolderExport = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.common_cancel)) }
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
                    if (onFilesTab) {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(
                                HugeIcons.FileImport,
                                contentDescription = stringResource(R.string.workspace_detail_import_file),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            if (onFilesTab) vm.refresh() else vm.refreshStats()
                        },
                    ) {
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
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = WorkspaceDetailTab.entries,
                modifier = Modifier.padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            ) { page ->
                when (WorkspaceDetailTab.entries[page]) {
                    WorkspaceDetailTab.BASIC -> {
                        WorkspaceBasicPage(
                            workspace = state.workspace,
                            distro = state.distro,
                            stats = stats,
                            installProgress = installProgress,
                            onEditSsh = { navController.navigate(Screen.WorkspaceSshEditor(id)) },
                            mirrors = mirrors,
                            onInstallRootfs = { showInstallDialog = true },
                            onUpdateRootfs = { showUpdateDialog = true },
                            upgradingPackages = upgradingPackages,
                            onToolApprovalChange = vm::setToolApproval,
                            onShellCompatibilityModeChange = vm::setShellCompatibilityMode,
                            onApplyMirrors = vm::applyMirrors,
                            onRepairCaCerts = vm::repairCaCerts,
                            sdcardEnabled = sdcardEnabled,
                            onSdcardAccessChange = vm::setSdcardAccess,
                            chrootEnabled = chrootEnabled,
                            chrootBackendReady = chrootBackendReady,
                            onChrootEnabledChange = vm::setChrootEnabled,
                            backend = shellBackend,
                            effectiveBackend = vm.effectiveShellBackend(),
                            backendFallbackReason = backendFallbackReason,
                            onBackendChange = vm::setShellBackend,
                            onSetTags = vm::setTags,
                        )
                    }

                    WorkspaceDetailTab.FILES -> {
                        WorkspaceFilesPage(
                            state = state,
                            contentPadding = PaddingValues(),
                            onSelectArea = {
                                selectedWorkspacePaths = emptySet()
                                vm.selectArea(it)
                            },
                            onGoUp = {
                                selectedWorkspacePaths = emptySet()
                                vm.goUp()
                            },
                            onResolveImage = { entry, area -> vm.resolveImageFile(entry, area) },
                            onOpen = { entry ->
                                when {
                                    entry.isDirectory -> {
                                        // 进子目录即清空旧选择并提示：多选只按当前目录算，导出也只导当前目录；
                                        // 不清的话，别的目录里还勾着的条目会被导出静默丢掉。
                                        if (selectedWorkspacePaths.isNotEmpty()) {
                                            selectedWorkspacePaths = emptySet()
                                            toaster.show("已清空其他目录的选择")
                                        }
                                        vm.open(entry)
                                    }

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
                                selectedWorkspacePaths =
                                    if (key in selectedWorkspacePaths) {
                                        selectedWorkspacePaths - key
                                    } else {
                                        selectedWorkspacePaths + key
                                    }
                            },
                            onSelectAll = {
                                selectedWorkspacePaths =
                                    if (selectedWorkspacePaths.size == state.entries.size) {
                                        emptySet()
                                    } else {
                                        state.entries.mapTo(mutableSetOf()) { "${state.area.name}:${it.path}" }
                                    }
                            },
                            onClearSelection = {
                                selectedWorkspacePaths = emptySet()
                            },
                            onExportSelection = {
                                val selected = state.entries.filter { "${state.area.name}:${it.path}" in selectedWorkspacePaths }
                                if (selected.isNotEmpty()) {
                                    exportZipTargets = selected
                                    exportZipLauncher.launch("workspace-selection.zip")
                                }
                            },
                            onCreateFile = { createEntry = false },
                            onCreateFolder = { createEntry = true },
                            clipboard = state.clipboard,
                            onPaste = { clip ->
                                if (state.entries.any { it.name == clip.name }) {
                                    showPasteOverwrite = true
                                } else {
                                    vm.paste(overwrite = false)
                                }
                            },
                            onClearClipboard = vm::clearClipboard,
                        )
                    }
                }
            }
        }
    }

    state.workspace?.let { workspace ->
        if (showInstallDialog) {
            InstallRootfsDialog(
                workspace = workspace,
                isUpdate = false,
                distro = state.distro,
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
        if (showUpdateDialog) {
            InstallRootfsDialog(
                workspace = workspace,
                isUpdate = true,
                distro = state.distro,
                onDismiss = { showUpdateDialog = false },
                onConfirm = { url ->
                    vm.updateRootfs(url)
                    showUpdateDialog = false
                },
                onConfirmFile = { path ->
                    vm.updateRootfsFromFile(path)
                    showUpdateDialog = false
                },
                onUpgradePackages = {
                    vm.upgradeDistroPackages()
                    showUpdateDialog = false
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

    val packageUpgradeToast =
        packageUpgradeResult?.let { outcome ->
            val msg =
                if (outcome.success) {
                    stringResource(R.string.workspace_detail_upgrade_packages_success)
                } else {
                    stringResource(R.string.workspace_detail_upgrade_packages_failed, outcome.detail)
                }
            outcome.success to msg
        }
    LaunchedEffect(packageUpgradeToast) {
        packageUpgradeToast?.let { (ok, msg) ->
            toaster.show(message = msg, type = if (ok) ToastType.Success else ToastType.Error)
            vm.dismissPackageUpgradeResult()
        }
    }

    val statsRefreshFailureMessage = statsRefreshFailure?.let { stringResource(R.string.workspace_detail_stats_refresh_failed, it) }
    LaunchedEffect(statsRefreshFailureMessage) {
        if (statsRefreshFailureMessage != null) {
            toaster.show(message = statsRefreshFailureMessage, type = ToastType.Error)
            vm.dismissStatsRefreshFailure()
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
            takenNames =
                state.entries
                    .filter { it.path != entry.path }
                    .mapTo(mutableSetOf()) { it.name },
            onSubmit = { newName ->
                vm.rename(entry, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    createEntry?.let { isDirectory ->
        WorkspaceCreateEntryDialog(
            isDirectory = isDirectory,
            takenNames =
                state.entries
                    .mapTo(mutableSetOf()) { it.name },
            onSubmit = { name ->
                if (isDirectory) vm.createFolder(name) else vm.createFile(name)
                createEntry = null
            },
            onDismiss = { createEntry = null },
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
            onCreateFolder = { parent, name ->
                vm.createEntryAt(parent, name, isDirectory = true)
            },
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

/** 重复导出到同一位置时待确认的导出：要导出的文件夹、用户选的目标树、目标里已存在的同名文件夹。 */
private data class PendingFolderExport(
    val entry: WorkspaceFileEntry,
    val tree: androidx.documentfile.provider.DocumentFile,
    val existing: androidx.documentfile.provider.DocumentFile,
)
