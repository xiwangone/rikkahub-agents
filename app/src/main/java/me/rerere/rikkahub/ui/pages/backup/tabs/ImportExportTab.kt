package me.rerere.rikkahub.ui.pages.backup.tabs

import me.rerere.rikkahub.ui.context.rememberSafeLauncherForActivityResult
import android.content.res.Resources
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.BackupNeedsPasswordException
import me.rerere.rikkahub.data.sync.MigrationManifestVersionException
import me.rerere.rikkahub.data.sync.NotMigrationPackageException
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.StickyHeader
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.backup.WebDavBackupItemsSelector
import me.rerere.rikkahub.ui.pages.backup.components.PasswordPromptDialog
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private data class PendingPasswordRequest(
    val mode: String, // "local", "migration", "cloud_migration"
    val file: File?,
    val cloudItem: WebDavBackupItem? = null,
)

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit,
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 资源取值走 LocalResources（configuration-aware）：直接 LocalContext.current.getString 会被 lint 的
    // LocalContextGetResourceValueCall 判为「配置变化后可能返回过期值」（本项目其余页面同此写法）。
    val resources = LocalResources.current
    val selectedBackupItems by vm.localBackupItems.collectAsStateWithLifecycle()
    var isUploading by remember { mutableStateOf(false) }
    var showCloudConfirm by remember { mutableStateOf<WebDavBackupItem?>(null) }
    val migrationCloudItems by vm.migrationCloudItems.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadMigrationCloudItems() }
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var pendingPasswordReq by remember { mutableStateOf<PendingPasswordRequest?>(null) }
    var pendingPasswordError by remember { mutableStateOf<String?>(null) }

    // 导入类型：local 为本地备份，chatbox 为 Chatbox 导入，cherry 为 Cherry Studio 导入，migration 为迁移包
    var importType by remember { mutableStateOf("local") }

    // 导出类型：local 为本地备份，migration 为迁移包（跨设备）
    var exportType by remember { mutableStateOf("local") }

    // 创建文件保存的launcher
    val createDocumentLauncher = rememberSafeLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                runCatching {
                    // 导出文件（迁移包与常规包共用「写到用户选定位置」这一步）
                    val migrationResult = if (exportType == "migration") vm.exportMigrationPackage() else null
                    val localResult = if (migrationResult == null) vm.exportToFile() else null
                    val exportFile = migrationResult?.file ?: localResult!!.file

                    // 复制到用户选择的位置（大文件流式拷贝必须在 IO 调度器执行并确保临时文件清理）
                    try {
                        withContext(Dispatchers.IO) {
                            val outputStream = context.contentResolver.openOutputStream(targetUri)
                                ?: error("Unable to open target output stream")
                            outputStream.use { out ->
                                FileInputStream(exportFile).use { inputStream ->
                                    inputStream.copyTo(out, bufferSize = 1 shl 16)
                                }
                            }
                        }
                    } finally {
                        withContext(Dispatchers.IO) {
                            if (exportFile.exists()) exportFile.delete()
                        }
                    }

                    val undecryptable = localResult?.stats?.undecryptableCredentials ?: 0
                    toaster.show(
                        when {
                            migrationResult != null -> resources.getString(
                                R.string.backup_page_migration_export_success,
                                migrationResult.credentialCount,
                            )

                            undecryptable > 0 -> resources.getString(
                                R.string.backup_page_backup_success_partial_credentials,
                                undecryptable,
                            )

                            else -> resources.getString(R.string.backup_page_backup_success)
                        },
                        type = if (undecryptable > 0) ToastType.Warning else ToastType.Success,
                    )
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        if (exportType == "migration" && e is IllegalStateException) {
                            // 含明文密钥的包必须加密后才能导出
                            resources.getString(R.string.backup_page_migration_need_encryption)
                        } else {
                            resources.getString(R.string.backup_page_export_failed, e.message ?: "")
                        },
                        type = ToastType.Error,
                    )
                }
                isExporting = false
            }
        }
    }

    // 创建文件选择的launcher
    val openDocumentLauncher = rememberSafeLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { sourceUri ->
            scope.launch {
                isRestoring = true
                runCatching {
                    when (importType) {
                        "local" -> {
                            val tempFile =
                                File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}.zip")
                            var keepForPassword = false
                            try {
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                        FileOutputStream(tempFile).use { outputStream ->
                                            inputStream.copyTo(outputStream, bufferSize = 1 shl 16)
                                        }
                                    }
                                }
                                vm.restoreFromLocalFile(tempFile)
                            } catch (e: BackupNeedsPasswordException) {
                                keepForPassword = true
                                pendingPasswordReq = PendingPasswordRequest(mode = "local", file = e.encFile ?: tempFile)
                                pendingPasswordError = null
                                throw e
                            } finally {
                                if (!keepForPassword && tempFile.exists()) {
                                    withContext(Dispatchers.IO) { tempFile.delete() }
                                }
                            }
                        }

                        "chatbox" -> {
                            val tempFile =
                                File(context.cacheDir, "temp_chatbox_${System.currentTimeMillis()}.zip")
                            try {
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                        FileOutputStream(tempFile).use { outputStream ->
                                            inputStream.copyTo(outputStream, bufferSize = 1 shl 16)
                                        }
                                    }
                                }
                                vm.restoreFromChatBox(tempFile)
                            } finally {
                                withContext(Dispatchers.IO) { tempFile.delete() }
                            }
                        }

                        "cherry" -> {
                            val tempFile =
                                File(context.cacheDir, "temp_cherry_${System.currentTimeMillis()}.zip")
                            try {
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                        FileOutputStream(tempFile).use { outputStream ->
                                            inputStream.copyTo(outputStream, bufferSize = 1 shl 16)
                                        }
                                    }
                                }
                                vm.restoreFromCherryStudio(tempFile)
                            } finally {
                                withContext(Dispatchers.IO) { tempFile.delete() }
                            }
                        }

                        "migration" -> {
                            val tempFile =
                                File(context.cacheDir, "temp_migration_${System.currentTimeMillis()}.zip")
                            var keepForPassword = false
                            try {
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                        FileOutputStream(tempFile).use { outputStream ->
                                            inputStream.copyTo(outputStream, bufferSize = 1 shl 16)
                                        }
                                    }
                                }
                                val result = vm.importMigrationPackage(tempFile)
                                toaster.show(
                                    resources.getString(
                                        R.string.backup_page_migration_import_success,
                                        result.credentialsImported,
                                        result.credentialsSkipped,
                                    ),
                                    type = ToastType.Success,
                                )
                                toaster.show(
                                    resources.getString(R.string.backup_page_migration_rootfs_hint),
                                    type = ToastType.Info,
                                )
                            } catch (e: BackupNeedsPasswordException) {
                                keepForPassword = true
                                pendingPasswordReq = PendingPasswordRequest(mode = "migration", file = e.encFile ?: tempFile)
                                pendingPasswordError = null
                                throw e
                            } finally {
                                if (!keepForPassword && tempFile.exists()) {
                                    withContext(Dispatchers.IO) { tempFile.delete() }
                                }
                            }
                        }
                    }

                    // 迁移包分支已给出更详细的提示（含覆盖条数），不再重复
                    if (importType != "migration") {
                        toaster.show(
                            resources.getString(R.string.backup_page_restore_success),
                            type = ToastType.Success,
                        )
                    }
                    onShowRestartDialog()
                }.onFailure { e ->
                    if (e !is BackupNeedsPasswordException) {
                        e.printStackTrace()
                        toaster.show(
                            resources.getString(R.string.backup_page_restore_failed, restoreFailureMessage(resources, e)),
                            type = ToastType.Error,
                        )
                    }
                }
                isRestoring = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_local_backup_export))
            }
        }

        item {
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_backup_items)) },
                    supportingContent = {
                        WebDavBackupItemsSelector(
                            allItems = WebDavConfig.BackupItem.entries.toList(),
                            selectedItems = selectedBackupItems,
                            onChange = { vm.updateLocalBackupItems(it) },
                        )
                    },
                )
                item(
                    onClick = if (!isExporting) {
                        {
                            exportType = "local"
                            val timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDocumentLauncher.launch("rikkahub_backup_$timestamp.zip")
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                    supportingContent = {
                        Text(
                            if (isExporting) {
                                stringResource(R.string.backup_page_exporting)
                            } else {
                                stringResource(R.string.backup_page_export_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "local"
                            showImportConfirmDialog = true
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                    supportingContent = {
                        Text(
                            if (isRestoring) {
                                stringResource(R.string.backup_page_importing)
                            } else {
                                stringResource(R.string.backup_page_import_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isRestoring) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }

        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_migration_section))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isExporting) {
                        {
                            exportType = "migration"
                            val timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDocumentLauncher.launch("rikkahub_migration_$timestamp.zip")
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_migration_export)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_migration_export_desc)) },
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )
                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "migration"
                            showImportConfirmDialog = true
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_migration_import)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_migration_import_desc)) },
                    leadingContent = {
                        if (isRestoring) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
                item(
                    onClick = if (!isUploading && !isRestoring) {
                        {
                            isUploading = true
                            scope.launch {
                                runCatching { vm.exportMigrationToWebDav() }
                                    .onSuccess { result ->
                                        toaster.show(
                                            resources.getString(
                                                R.string.backup_page_migration_cloud_uploaded,
                                                result.credentialCount,
                                            ),
                                            type = ToastType.Success,
                                        )
                                        vm.loadMigrationCloudItems()
                                    }
                                    .onFailure { e ->
                                        toaster.show(
                                            resources.getString(R.string.backup_page_restore_failed, restoreFailureMessage(resources, e)),
                                            type = ToastType.Error,
                                        )
                                    }
                                isUploading = false
                            }
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_migration_cloud_upload)) },
                    leadingContent = {
                        if (isUploading) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )
            }
        }

        // 云端迁移包（migration_ 前缀，跨机可解形态；上传后云端只保留最新一份）
        if (migrationCloudItems is UiState.Error) {
            item {
                CardGroup {
                    item {
                        Text(
                            (migrationCloudItems as UiState.Error).error.message ?: "error",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        if (migrationCloudItems is UiState.Success) {
            val cloudList = (migrationCloudItems as UiState.Success<List<WebDavBackupItem>>).data
            if (cloudList.isNotEmpty()) {
                item {
                    CardGroup {
                        cloudList.forEach { cloudItem ->
                            item(
                                onClick = if (!isRestoring) {
                                    { showCloudConfirm = cloudItem }
                                } else null,
                                headlineContent = { Text(cloudItem.displayName) },
                                supportingContent = {
                                    Text("${cloudItem.size.fileSizeToString()} · ${cloudItem.lastModified.toString().take(16)}")
                                },
                            )
                        }
                    }
                }
            }
        }

        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_import_from_other_app))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "chatbox"
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_chatbox)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "chatbox") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "cherry"
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_cherry_studio)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_studio_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "cherry") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }
    }

    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showImportConfirmDialog = false },
            title = {
                Text(
                    stringResource(
                        if (importType == "migration") {
                            R.string.backup_page_migration_import
                        } else {
                            R.string.backup_page_local_backup_import
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (importType == "migration") {
                            R.string.backup_page_migration_import_confirm
                        } else {
                            R.string.backup_page_import_overwrite_confirm
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        // 迁移包保留自己的分支；其余入口维持既有行为（local）
                        importType = if (importType == "migration") "migration" else "local"
                        // 兼容 .zip 与 .zip.enc / .enc 加密容器选择
                        openDocumentLauncher.launch(
                            arrayOf("application/zip", "application/octet-stream", "*/*"),
                        )
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    showCloudConfirm?.let { cloudItem ->
        AlertDialog(
            onDismissRequest = { showCloudConfirm = null },
            title = { Text(stringResource(R.string.backup_page_migration_import)) },
            text = { Text(stringResource(R.string.backup_page_migration_import_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showCloudConfirm = null
                    isRestoring = true
                    scope.launch {
                        runCatching { vm.importMigrationFromWebDav(cloudItem) }
                            .onSuccess { result ->
                                toaster.show(
                                    resources.getString(
                                        R.string.backup_page_migration_import_success,
                                        result.credentialsImported,
                                        result.credentialsSkipped,
                                    ),
                                    type = ToastType.Success,
                                )
                                // 工作区文件不随包：导入后提醒重装 rootfs
                                toaster.show(
                                    resources.getString(R.string.backup_page_migration_rootfs_hint),
                                    type = ToastType.Info,
                                )
                                onShowRestartDialog()
                            }
                            .onFailure { e ->
                                if (e is BackupNeedsPasswordException) {
                                    pendingPasswordReq =
                                        PendingPasswordRequest(
                                            mode = "cloud_migration",
                                            file = e.encFile,
                                            cloudItem = cloudItem,
                                        )
                                    pendingPasswordError = null
                                } else {
                                    toaster.show(
                                        resources.getString(R.string.backup_page_restore_failed, restoreFailureMessage(resources, e)),
                                        type = ToastType.Error,
                                    )
                                }
                            }
                        isRestoring = false
                    }
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloudConfirm = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // 换机/新设备未记住口令或口令不一致时，弹出输口令对话框完成导入
    pendingPasswordReq?.let { req ->
        PasswordPromptDialog(
            onDismiss = {
                val f = req.file
                pendingPasswordReq = null
                pendingPasswordError = null
                scope.launch(Dispatchers.IO) {
                    if (f != null && f.exists()) f.delete()
                }
            },
            onConfirm = { password ->
                pendingPasswordError = null
                isRestoring = true
                scope.launch {
                    runCatching {
                        when (req.mode) {
                            "local" -> {
                                val targetFile = requireNotNull(req.file) { "Missing local backup file" }
                                vm.restoreFromLocalFile(targetFile, password = password)
                                withContext(Dispatchers.IO) { if (targetFile.exists()) targetFile.delete() }
                                toaster.show(
                                    resources.getString(R.string.backup_page_restore_success),
                                    type = ToastType.Success,
                                )
                            }

                            "migration" -> {
                                val targetFile = requireNotNull(req.file) { "Missing migration file" }
                                val result = vm.importMigrationPackage(targetFile, password = password)
                                withContext(Dispatchers.IO) { if (targetFile.exists()) targetFile.delete() }
                                toaster.show(
                                    resources.getString(
                                        R.string.backup_page_migration_import_success,
                                        result.credentialsImported,
                                        result.credentialsSkipped,
                                    ),
                                    type = ToastType.Success,
                                )
                                toaster.show(
                                    resources.getString(R.string.backup_page_migration_rootfs_hint),
                                    type = ToastType.Info,
                                )
                            }

                            "cloud_migration" -> {
                                val item = requireNotNull(req.cloudItem) { "Missing cloud migration item" }
                                val result =
                                    vm.importMigrationFromWebDav(
                                        item = item,
                                        password = password,
                                        cachedEncFile = req.file,
                                    )
                                toaster.show(
                                    resources.getString(
                                        R.string.backup_page_migration_import_success,
                                        result.credentialsImported,
                                        result.credentialsSkipped,
                                    ),
                                    type = ToastType.Success,
                                )
                                toaster.show(
                                    resources.getString(R.string.backup_page_migration_rootfs_hint),
                                    type = ToastType.Info,
                                )
                            }
                        }
                    }.onSuccess {
                        pendingPasswordReq = null
                        pendingPasswordError = null
                        onShowRestartDialog()
                    }.onFailure { e ->
                        if (e is BackupNeedsPasswordException || e is IllegalArgumentException) {
                            pendingPasswordError =
                                resources.getString(R.string.backup_page_encryption_wrong_password)
                        } else {
                            val f = req.file
                            pendingPasswordReq = null
                            pendingPasswordError = null
                            withContext(Dispatchers.IO) { if (f != null && f.exists()) f.delete() }
                            toaster.show(
                                resources.getString(R.string.backup_page_restore_failed, restoreFailureMessage(resources, e)),
                                type = ToastType.Error,
                            )
                        }
                    }
                    isRestoring = false
                }
            },
            errorMessage = pendingPasswordError,
        )
    }
}

/**
 * 恢复/导入失败文案：迁移包的两类错误用字符串资源本地化
 * （版本不兼容时报出包版本与本机支持范围），其余错误沿用原始异常信息。
 */
private fun restoreFailureMessage(resources: Resources, e: Throwable): String =
    when (e) {
        is MigrationManifestVersionException -> resources.getString(
            R.string.backup_page_migration_version_mismatch,
            e.packageVersion,
            e.minSupportedVersion,
            e.maxSupportedVersion,
        )

        is NotMigrationPackageException -> resources.getString(R.string.backup_page_migration_not_package)
        else -> e.message ?: ""
    }
