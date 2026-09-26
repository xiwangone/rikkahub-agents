package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.MirrorProbe
import me.rerere.workspace.MirrorSpeedResult
import me.rerere.workspace.measureMirrorSpeeds
import me.rerere.workspace.resolveMirrorProbeUrl
import me.rerere.rikkahub.ui.context.LocalToaster
import com.dokar.sonner.ToastType
import me.rerere.workspace.WorkspaceMirrorPresets
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.RadioButton
import me.rerere.workspace.WorkspaceMirrors
import me.rerere.workspace.WorkspaceMirrorPreset
import android.content.Intent
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.plus
import me.rerere.workspace.WorkspaceDistroInfo
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceStats
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.io.File

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
    val stats by vm.stats.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var showInstallDialog by remember { mutableStateOf(false) }
    val mirrors by vm.mirrors.collectAsStateWithLifecycle()
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
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
                    context.getString(
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
            val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
            vm.exportFile(entry, outputStream)
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
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = null)
                    }
                    if (state.workspace?.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { navController.navigate(Screen.WorkspaceTerminal(id)) }) {
                            Icon(HugeIcons.ComputerTerminal01, contentDescription = null)
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
                        mirrors = mirrors,
                        onInstallRootfs = { showInstallDialog = true },
                        onToolApprovalChange = vm::setToolApproval,
                        onShellCompatibilityModeChange = vm::setShellCompatibilityMode,
                        onApplyMirrors = vm::applyMirrors,
                        onRepairCaCerts = vm::repairCaCerts,
                        sdcardEnabled = sdcardEnabled,
                        onSdcardAccessChange = vm::setSdcardAccess,
                        onSetTags = vm::setTags,
                    )
                }

                1 -> {
                    WorkspaceFilesPage(
                        state = state,
                        contentPadding = PaddingValues(),
                        onSelectArea = vm::selectArea,
                        onGoUp = vm::goUp,
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
                                            vm.exportToCacheFile(entry, context.cacheDir) { file ->
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
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        onDelete = { deleteTarget = it },
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

    settingsError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissSettingsError,
            title = { Text(stringResource(R.string.workspace_detail_settings_save_failed)) },
            text = { Text(message.ifBlank { stringResource(R.string.workspace_detail_settings_save_failed) }) },
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
}

@Composable
private fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    distro: WorkspaceDistroInfo?,
    stats: WorkspaceStats?,
    installProgress: RootfsInstallProgress?,
    mirrors: WorkspaceMirrors,
    onInstallRootfs: () -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
    onShellCompatibilityModeChange: (Boolean) -> Unit,
    onApplyMirrors: (WorkspaceMirrors) -> Unit,
    onRepairCaCerts: () -> Unit,
    sdcardEnabled: Boolean,
    onSdcardAccessChange: (Boolean) -> Unit,
    onSetTags: (List<String>) -> Unit,
) {
    var mirrorPicker by remember { mutableStateOf<MirrorPick?>(null) }
    val scope = rememberCoroutineScope()
    val speedResults = remember { mutableStateMapOf<MirrorPick, Map<String, MirrorSpeedResult>>() }
    var speedTestingPick by remember { mutableStateOf<MirrorPick?>(null) }
    var tagEditing by remember { mutableStateOf(false) }
    val shellStatus = workspace?.shellStatus
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name
    val installButtonText =
        when {
            installing -> stringResource(R.string.workspace_detail_installing)
            rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
            else -> stringResource(R.string.workspace_detail_install_rootfs)
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            CardGroup(
                title = { Text(stringResource(R.string.workspace_detail_workspace_info)) },
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_workspace_info),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    WorkspaceInfoRow(
                        stringResource(R.string.workspace_detail_name),
                        workspace?.name ?: stringResource(R.string.workspace_detail_loading),
                    )
                    WorkspaceInfoRow(
                        stringResource(R.string.workspace_detail_shell_status),
                        workspace?.shellStatus?.toShellStatusLabel() ?: "-",
                    )
                    distro?.let { info ->
                        WorkspaceInfoRow(
                            stringResource(R.string.workspace_detail_distro),
                            info.prettyName,
                        )
                    }
                    WorkspaceInfoRow(
                        stringResource(R.string.workspace_detail_stats_disk),
                        stats?.rootBytes?.fileSizeToString() ?: "-",
                    )
                    WorkspaceInfoRow(
                        stringResource(R.string.workspace_detail_stats_packages),
                        stats?.packageCount?.toString() ?: "-",
                    )
                    WorkspaceInfoRow(
                        stringResource(R.string.workspace_detail_stats_kernel),
                        stats?.kernel ?: "-",
                    )
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { tagEditing = true }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.workspace_detail_tags),
                            modifier = Modifier.weight(0.35f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            modifier = Modifier.weight(0.65f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val tags = workspace?.workspaceTags().orEmpty()
                            if (tags.isEmpty()) {
                                Text("-", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                tags.fastForEach { tag ->
                                    Surface(shape = CircleShape, tonalElevation = 1.dp) {
                                        Text(
                                            text = tag,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            CardGroup(
                title = { Text(stringResource(R.string.workspace_detail_mirrors)) },
            ) {
                MirrorRow(
                    title = stringResource(R.string.workspace_detail_mirror_apk),
                    current = mirrors.apk,
                    presets = WorkspaceMirrorPresets.APK,
                    onClick = { mirrorPicker = MirrorPick.APK },
                )
                MirrorRow(
                    title = stringResource(R.string.workspace_detail_mirror_apt),
                    current = mirrors.apt,
                    presets = WorkspaceMirrorPresets.APT,
                    onClick = { mirrorPicker = MirrorPick.APT },
                )
                MirrorRow(
                    title = stringResource(R.string.workspace_detail_mirror_pip),
                    current = mirrors.pip,
                    presets = WorkspaceMirrorPresets.PIP,
                    onClick = { mirrorPicker = MirrorPick.PIP },
                )
                MirrorRow(
                    title = stringResource(R.string.workspace_detail_mirror_npm),
                    current = mirrors.npm,
                    presets = WorkspaceMirrorPresets.NPM,
                    onClick = { mirrorPicker = MirrorPick.NPM },
                )
                item(
                    onClick = onRepairCaCerts,
                    headlineContent = { Text(stringResource(R.string.workspace_detail_repair_cacerts)) },
                    supportingContent = { Text(stringResource(R.string.workspace_detail_repair_cacerts_desc)) },
                )
            }
        }

        item {
            CardGroup(
                title = { Text(stringResource(R.string.workspace_detail_enable_shell)) },
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onInstallRootfs,
                        enabled = workspace != null && !installing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(HugeIcons.Bash, contentDescription = null)
                        Text(
                            text = installButtonText,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.workspace_detail_sdcard_access),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.workspace_detail_sdcard_access_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = sdcardEnabled,
                            onCheckedChange = onSdcardAccessChange,
                        )
                    }

                    installProgress?.let { progress ->
                        RootfsProgress(progress)
                    }
                }
            }
        }

        item {
            CardGroup(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.workspace_detail_compatibility_mode))
                        Text(
                            text = stringResource(R.string.workspace_detail_compatibility_mode_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_compatibility_mode)) },
                    trailingContent = {
                        Switch(
                            checked = workspace?.shellCompatibilityMode ?: false,
                            onCheckedChange = onShellCompatibilityModeChange,
                            enabled = workspace != null,
                        )
                    },
                )
            }
        }

        item {
            WorkspaceToolApprovalCard(
                workspace = workspace,
                onToolApprovalChange = onToolApprovalChange,
            )
        }
    }

                MirrorPickerDialog(
            pick = mirrorPicker,
            mirrors = mirrors,
            speedResults = mirrorPicker?.let { speedResults[it] }.orEmpty(),
            speedTesting = speedTestingPick != null && speedTestingPick == mirrorPicker,
            onDismiss = { mirrorPicker = null },
            onSpeedTest = onSpeedTest@{
                val pick = mirrorPicker ?: return@onSpeedTest
                if (speedTestingPick != null) return@onSpeedTest
                val probes =
                    pick.presets().mapNotNull { preset ->
                        resolveMirrorProbeUrl(preset, distro?.prettyName)?.let { MirrorProbe(preset.id, it) }
                    }
                if (probes.isEmpty()) return@onSpeedTest
                speedResults.remove(pick)
                speedTestingPick = pick
                scope.launch {
                    val results =
                        withContext(Dispatchers.IO) {
                            measureMirrorSpeeds(probes)
                        }
                    speedResults[pick] = results.associate { it.id to it }
                    speedTestingPick = null
                }
            },
            onSelect = { url ->
                onApplyMirrors(
                    when (mirrorPicker) {
                        MirrorPick.APK -> mirrors.copy(apk = url)
                        MirrorPick.APT -> mirrors.copy(apt = url)
                        MirrorPick.PIP -> mirrors.copy(pip = url)
                        MirrorPick.NPM -> mirrors.copy(npm = url)
                        null -> mirrors
                    },
                )
                mirrorPicker = null
            },
        )

        if (tagEditing) {
            TagEditDialog(
                initial = workspace?.workspaceTags().orEmpty(),
                onDismiss = { tagEditing = false },
                onSave = { tags ->
                    onSetTags(tags)
                    tagEditing = false
                },
            )
        }
}

@Composable
private fun WorkspaceToolApprovalCard(
    workspace: WorkspaceEntity?,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    val overrides = workspace?.toolApprovalOverrides().orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            workspaceToolApprovalItems().forEach { (toolName, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = toolName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = resolveWorkspaceToolApproval(toolName, overrides),
                        onCheckedChange = { onToolApprovalChange(toolName, it) },
                        enabled = workspace != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun workspaceToolApprovalItems() =
    listOf(
        "workspace_list" to stringResource(R.string.workspace_detail_tool_list_files),
        "workspace_read_folder" to stringResource(R.string.workspace_detail_tool_read_folder),
        "workspace_search_code" to stringResource(R.string.workspace_detail_tool_search_code),
        "workspace_read_file" to stringResource(R.string.workspace_detail_tool_read_file),
        "workspace_write_file" to stringResource(R.string.workspace_detail_tool_write_file),
        "workspace_create_folder" to stringResource(R.string.workspace_detail_tool_create_folder),
        "workspace_edit_file" to stringResource(R.string.workspace_detail_tool_edit_file),
        "workspace_shell" to stringResource(R.string.workspace_detail_tool_shell),
        "workspace_run_background" to stringResource(R.string.workspace_detail_tool_run_background),
        "workspace_background_status" to stringResource(R.string.workspace_detail_tool_background_status),
        "workspace_background_kill" to stringResource(R.string.workspace_detail_tool_background_kill),
    )

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction =
            progress.totalBytes?.takeIf { it > 0 }?.let {
                (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
            }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text =
                when (progress.stage) {
                    RootfsInstallStage.DOWNLOADING -> {
                        val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                        stringResource(
                            R.string.workspace_detail_downloading,
                            progress.bytesRead.fileSizeToString(),
                            total,
                        )
                    }

                    RootfsInstallStage.EXTRACTING -> {
                        val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                        stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                    }

                    RootfsInstallStage.INSTALLED -> {
                        stringResource(R.string.workspace_detail_install_complete)
                    }
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onConfirmFile: (String) -> Unit,
) {
    var url by rememberSaveable(workspace.id) { mutableStateOf(DEFAULT_ROOTFS_URL) }
    val context = LocalContext.current
    // Debian 预置地址含构建日期，需在线解析最新目录后再填入（见 resolveLxcDebianRootfs）
    val scope = rememberCoroutineScope()
    // 本地导入：先把所选归档复制到应用缓存，再交给安装流程（安装线程无法直接读 SAF 流）
    val pickArchiveLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: android.net.Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "rootfs.tar.gz"
            val suffix =
                if (name.endsWith(".tar.xz", true) || name.endsWith(".txz", true)) ".tar.xz" else ".tar.gz"
            val target = File(context.cacheDir, "rootfs-import-${System.currentTimeMillis()}$suffix")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Unable to read selected file")
            }.onSuccess {
                onConfirmFile(target.absolutePath)
            }
        }

    var savedUrls by remember { mutableStateOf(loadSavedRootfsUrls(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_detail_download_url)) },
                    maxLines = 5,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_preset_rootfs_urls),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PRESET_ROOTFS_URLS.forEach { preset ->
                        AssistChip(
                            onClick = {
                                if (preset.lxcDebian) {
                                    // 地址含构建日期：点击时解析最新目录，失败回落到写死地址
                                    scope.launch { url = resolveLxcDebianRootfs() ?: preset.url }
                                } else {
                                    url = preset.url
                                }
                            },
                            label = {
                                Text(text = preset.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                        )
                    }
                }
                if (savedUrls.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.workspace_detail_saved_rootfs_urls),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    savedUrls.forEach { saved ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { url = saved },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = saved, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(
                                onClick = {
                                    val updated = savedUrls - saved
                                    savedUrls = updated
                                    saveRootfsUrls(context, updated)
                                },
                            ) {
                                Text(stringResource(R.string.workspace_detail_delete_saved_url))
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val candidate = url.trim()
                        if (candidate.isNotBlank()) {
                            val updated =
                                (listOf(candidate) + savedUrls).distinct().take(MAX_SAVED_ROOTFS_URLS)
                            savedUrls = updated
                            saveRootfsUrls(context, updated)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.workspace_detail_save_rootfs_url))
                }
                OutlinedButton(
                    onClick = { pickArchiveLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.workspace_detail_import_local_rootfs))
                }
                Text(
                    text = stringResource(R.string.workspace_detail_import_local_rootfs_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun WorkspaceFilesPage(
    state: WorkspaceDetailState,
    contentPadding: PaddingValues,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onGoUp: () -> Unit,
    onResolveImage: suspend (WorkspaceFileEntry, WorkspaceStorageArea) -> File?,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            WorkspaceAreaSelector(
                selected = state.area,
                onSelected = onSelectArea,
            )
        }

        item {
            WorkspacePathBar(
                path = state.path,
                canGoUp = state.path.isNotBlank(),
                onGoUp = onGoUp,
            )
        }

        state.error?.let { error ->
            item {
                ErrorCard(error)
            }
        }

        if (!state.loading && state.entries.isEmpty() && state.error == null) {
            item {
                EmptyDirectoryState()
            }
        }

        items(state.entries, key = { "${state.area.name}:${it.path}" }) { entry ->
            WorkspaceFileCard(
                entry = entry,
                area = state.area,
                onResolveImage = { onResolveImage(entry, state.area) },
                onOpen = { onOpen(entry) },
                onDelete = { onDelete(entry) },
                onExport = { onExport(entry) },
                onShare = { onShare(entry) },
            )
        }
    }
}

@Composable
private fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
) {
    val areas =
        listOf(
            WorkspaceStorageArea.FILES to stringResource(R.string.workspace_detail_area_files),
            WorkspaceStorageArea.LINUX to stringResource(R.string.workspace_detail_area_rootfs),
        )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        areas.forEachIndexed { index, (area, label) ->
            SegmentedButton(
                selected = selected == area,
                onClick = { onSelected(area) },
                shape = SegmentedButtonDefaults.itemShape(index, areas.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            enabled = canGoUp,
            onClick = onGoUp,
        ) {
            Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
        }
        Text(
            text = path.ifBlank { "/" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    area: WorkspaceStorageArea,
    onResolveImage: suspend () -> File?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isImage = !entry.isDirectory && entry.detectFileType() == WorkspaceFileType.IMAGE
    val imageFile by produceState<File?>(
        initialValue = null,
        key1 = if (isImage) area else null,
        key2 = if (isImage) entry.path else null,
        key3 = if (isImage) "${entry.updatedAt}:${entry.sizeBytes}" else null,
    ) {
        if (isImage) {
            value = try {
                onResolveImage()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isImage) {
                val context = LocalContext.current
                val imageRequest = remember(imageFile, entry.updatedAt, entry.sizeBytes) {
                    imageFile?.let {
                        ImageRequest.Builder(context)
                            .data(it)
                            .memoryCacheKey("workspace:${it.absolutePath}:${entry.updatedAt}:${entry.sizeBytes}")
                            .build()
                    }
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.File02,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    imageRequest?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (entry.isDirectory) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.isDirectory) entry.path else "${entry.path} · ${entry.sizeBytes.fileSizeToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(HugeIcons.MoreVertical, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (entry.isDirectory) R.string.workspace_detail_export_folder
                                    else R.string.common_export,
                                ),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.FileImport,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onExport()
                        },
                    )
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_share)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Share08,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onShare()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.common_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDirectoryState() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Folder01,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_detail_empty_directory),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
internal fun String.toShellStatusLabel(): String =
    when (this) {
        WorkspaceShellStatus.DISABLED.name -> stringResource(R.string.workspace_detail_shell_disabled)
        WorkspaceShellStatus.INSTALLING.name -> stringResource(R.string.workspace_detail_shell_installing)
        WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_detail_shell_ready)
        WorkspaceShellStatus.BROKEN.name -> stringResource(R.string.workspace_detail_shell_broken)
        else -> lowercase()
    }

private const val DEFAULT_ROOTFS_URL =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"

@Composable
private fun WorkspaceInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val MAX_SAVED_ROOTFS_URLS = 5
private const val ROOTFS_URL_HISTORY_KEY = "rootfs_url_history"

private fun loadSavedRootfsUrls(context: android.content.Context): List<String> =
    context
        .readStringPreference(ROOTFS_URL_HISTORY_KEY)
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.toList()
        .orEmpty()

private fun saveRootfsUrls(context: android.content.Context, urls: List<String>) {
    context.writeStringPreference(ROOTFS_URL_HISTORY_KEY, urls.joinToString("\n"))
}

private data class PresetRootfsUrl(
    val label: String,
    val url: String,
    /** true 表示该条来自 LXC 镜像站：地址含构建日期，点击时尝试解析最新目录，失败用 [url] 兜底。 */
    val lxcDebian: Boolean = false,
)

/** 运行设备是否为 arm64 —— 预置 rootfs 需按设备 ABI 选地址（rootfs 必须匹配设备 ABI）。 */
private val isArm64Device: Boolean
    get() =
        System.getProperty("os.arch").orEmpty().lowercase().let { it == "aarch64" || it == "arm64" }

/** 按设备 ABI 在 arm64 / amd64 两个下载地址间二选一。 */
private fun presetRootfs(label: String, arm64: String, amd64: String, lxcDebian: Boolean = false) =
    PresetRootfsUrl(label, if (isArm64Device) arm64 else amd64, lxcDebian)

/** LXC 镜像站使用的架构目录名。 */
private val lxcAbiDir: String get() = if (isArm64Device) "arm64" else "amd64"

/**
 * 解析 LXC 镜像站 Debian 的最新构建目录，返回 rootfs 地址；全部失败返回 null（调用方兜底）。
 *
 * 先试镜像站（只同步最新一天，取任一即可），再试官方（列多天，取最大日期）。
 * 地址形如 `<base>/trixie/<abi>/default/<yyyyMMdd_HH:mm>/rootfs.tar.xz`。
 */
private suspend fun resolveLxcDebianRootfs(): String? =
    withContext(Dispatchers.IO) {
        val bases =
            listOf(
                "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian",
                "https://images.linuxcontainers.org/images/debian",
            )
        val stamp = Regex("""href="(\d{8}_\d{2}(?:%3A|:)\d{2})/"""")
        for (base in bases) {
            val dir = "$base/trixie/$lxcAbiDir/default/"
            val html =
                runCatching {
                    java.net.URL(dir)
                        .openConnection()
                        .apply {
                            connectTimeout = 8000
                            readTimeout = 8000
                        }.getInputStream()
                        .bufferedReader()
                        .use { it.readText() }
                }.getOrNull() ?: continue
            val stamps = stamp.findAll(html).map { it.groupValues[1] }.distinct().sorted()
            val latest = stamps.lastOrNull() ?: continue
            return@withContext "$dir$latest/rootfs.tar.xz"
        }
        null
    }

/**
 * 预置 rootfs 源：均已在移动网络下实测可达（GitHub 系地址不可达，勿加入）。
 *
 * 地址按设备 ABI 选择。⚠ Debian 取自 LXC 镜像站，路径含构建日期：点击时会在线解析最新目录，
 * 此处只是解析失败时的离线兜底，日期滚动后如兜底失效需更新。
 */
private val PRESET_ROOTFS_URLS: List<PresetRootfsUrl>
    get() =
        listOf(
            presetRootfs(
                "Ubuntu 26.04.1 base",
                "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/ubuntu-base-26.04.1-base-arm64.tar.gz",
                "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/ubuntu-base-26.04.1-base-amd64.tar.gz",
            ),
            presetRootfs(
                "Ubuntu 24.04.5 base",
                "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
                "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-amd64.tar.gz",
            ),
            presetRootfs(
                "Debian 13 trixie base",
                "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/trixie/arm64/default/20260924_05:24/rootfs.tar.xz",
                "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/trixie/amd64/default/20260924_05:24/rootfs.tar.xz",
                lxcDebian = true,
            ),
            presetRootfs(
                "Alpine 3.24.2 minirootfs",
                "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz",
                "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-minirootfs-3.24.2-x86_64.tar.gz",
            ),
        )


/** Which package-manager mirror is being picked. */
private enum class MirrorPick { APK, APT, PIP, NPM }

private fun MirrorPick.presets(): List<WorkspaceMirrorPreset> =
    when (this) {
        MirrorPick.APK -> WorkspaceMirrorPresets.APK
        MirrorPick.APT -> WorkspaceMirrorPresets.APT
        MirrorPick.PIP -> WorkspaceMirrorPresets.PIP
        MirrorPick.NPM -> WorkspaceMirrorPresets.NPM
    }

@Composable
private fun CardGroupScope.MirrorRow(
    title: String,
    current: String,
    presets: List<WorkspaceMirrorPreset>,
    onClick: () -> Unit,
) {
    val label =
        presets.firstOrNull { it.url == current }?.label
            ?: current.takeIf { it.isNotBlank() }
            ?: presets.firstOrNull()?.label.orEmpty()
    item(
        onClick = onClick,
        headlineContent = { Text(title) },
        supportingContent = { Text(label) },
    )
}

@Composable
private fun MirrorPickerDialog(
    pick: MirrorPick?,
    mirrors: WorkspaceMirrors,
    speedResults: Map<String, MirrorSpeedResult>,
    speedTesting: Boolean,
    onDismiss: () -> Unit,
    onSpeedTest: () -> Unit,
    onSelect: (String) -> Unit,
) {
    if (pick == null) return
    val presets = pick.presets()
    val current =
        when (pick) {
            MirrorPick.APK -> mirrors.apk
            MirrorPick.APT -> mirrors.apt
            MirrorPick.PIP -> mirrors.pip
            MirrorPick.NPM -> mirrors.npm
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_mirrors)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val fastestMs = speedResults.values.mapNotNull { it.latencyMs }.minOrNull()
                presets.forEach { preset ->
                    val result = speedResults[preset.id]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = preset.url == current || (current.isBlank() && preset == presets.first()),
                            onClick = { onSelect(preset.url) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                preset.region,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            speedTesting && result == null ->
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )

                            result != null && result.latencyMs != null ->
                                Text(
                                    text = "${result.latencyMs} ms",
                                    style = MaterialTheme.typography.labelMedium,
                                    color =
                                        if (result.latencyMs == fastestMs) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )

                            result?.failed == true ->
                                Text(
                                    text = stringResource(R.string.workspace_detail_mirror_speed_failed),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onSpeedTest, enabled = !speedTesting) {
                if (speedTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.workspace_detail_mirror_speed_testing))
                } else {
                    Text(stringResource(R.string.workspace_detail_mirror_speed_test))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun TagEditDialog(
    initial: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var input by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        AppLog.i("WorkspaceTags", "dialog open: initial=$initial")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_tags)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    draft.fastForEach { tag ->
                        Surface(shape = MaterialTheme.shapes.small, tonalElevation = 2.dp) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(tag, style = MaterialTheme.typography.bodySmall)
                                Icon(
                                    imageVector = HugeIcons.Cancel01,
                                    contentDescription = null,
                                    modifier =
                                        Modifier
                                            .size(14.dp)
                                            .clickable { draft = draft.filter { it != tag } },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.workspace_detail_tags_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                val trimmed = input.trim()
                                if (trimmed.isNotEmpty() && trimmed !in draft) draft = draft + trimmed
                                input = ""
                            },
                        ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.filter { it.isNotBlank() }.distinct()) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
