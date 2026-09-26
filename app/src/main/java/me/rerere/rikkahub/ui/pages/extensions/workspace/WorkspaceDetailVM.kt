package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceDistroInfo
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceStats
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.log.AppLog

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val context: android.app.Application,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    private val _folderExportProgress = MutableStateFlow<FolderExportProgress?>(null)
    val folderExportProgress = _folderExportProgress.asStateFlow()

    private val _settingsError = MutableStateFlow<String?>(null)
    val settingsError = _settingsError.asStateFlow()

    private val _caRepairCount = MutableStateFlow<Int?>(null)

    /** 最近一次「修复 CA 证书」写入的沙箱数量（null = 无待提示结果）。 */
    val caRepairCount = _caRepairCount.asStateFlow()

    /** 资源面板（磁盘/包数/内核）；采集失败为 null。 */
    val stats: kotlinx.coroutines.flow.StateFlow<WorkspaceStats?> =
        repository.statsFlow(id)
            .catch { emit(null) }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
                null,
            )

    /** 沙箱镜像配置（全局）。 */
    val mirrors: kotlinx.coroutines.flow.StateFlow<me.rerere.workspace.WorkspaceMirrors> =
        repository.mirrorsFlow()
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
                me.rerere.workspace.WorkspaceMirrors(),
            )

    /** 保存镜像配置并应用到已安装的 rootfs。 */
    fun applyMirrors(mirrors: me.rerere.workspace.WorkspaceMirrors) {
        viewModelScope.launch {
            runCatching { repository.setMirrors(mirrors) }
                .onFailure { _settingsError.value = it.message }
        }
    }

    fun dismissSettingsError() {
        _settingsError.value = null
    }

    /** 把系统 CA 合并写入已安装 rootfs（修复缺 CA bundle 导致 https 不可用）。 */
    fun repairCaCerts() {
        viewModelScope.launch {
            runCatching { repository.repairCaCerts() }
                .onSuccess { count -> _caRepairCount.value = count }
                .onFailure { _settingsError.value = it.message }
        }
    }

    fun dismissCaRepairHint() {
        _caRepairCount.value = null
    }

    /** 手机存储挂载开关（全局，默认关）。 */
    val sdcardEnabled: kotlinx.coroutines.flow.StateFlow<Boolean> =
        repository.sdcardEnabledFlow()
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
                false,
            )

    fun setSdcardAccess(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setSdcardAccess(enabled) }
                .onFailure { _settingsError.value = it.message }
        }
    }

    init {
        loadWorkspace()
        refresh()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.listFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
            }.onSuccess { entries ->
                _state.update { it.copy(entries = entries, loading = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_load),
                    )
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_delete)) }
            }
        }
    }

    fun importFile(
        inputStream: InputStream,
        fileName: String,
    ) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_import)) }
            }
        }
    }

    fun exportFile(
        entry: WorkspaceFileEntry,
        outputStream: OutputStream,
    ) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_export)) }
            }
        }
    }

    suspend fun resolveImageFile(
        entry: WorkspaceFileEntry,
        area: WorkspaceStorageArea,
    ): File = repository.resolveFile(id, area, entry.path)

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(
        entry: WorkspaceFileEntry,
        cacheDir: File,
        onReady: (File) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_export)) }
            }
        }
    }

    /**
     * 把 [entry]（目录）整棵子树导出到 SAF 目标目录 [destinationTree]。
     *
     * 列目录用 `Int.MAX_VALUE`：默认列表上限（500）会把大目录截断成"只导出前 500 项"。
     * 单个文件失败只计入 [FolderExportOutcome.failures]，不中断整单导出。
     */
    // 目录导出是「尽力而为」的批量操作：单项失败仅计数，不中断整体，故此处兜底捕获并记录。
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun exportFolder(
        entry: WorkspaceFileEntry,
        destinationTree: DocumentFile,
        onResult: (FolderExportOutcome) -> Unit,
    ) {
        viewModelScope.launch {
            val area = state.value.area
            val outcome =
                try {
                    withContext(Dispatchers.IO) {
                        val listing = mutableMapOf<String, List<WorkspaceFileEntry>>()
                        var failures = 0

                        suspend fun collect(path: String) {
                            currentCoroutineContext().ensureActive()
                            val children =
                                try {
                                    repository.listFiles(id = id, area = area, path = path, limit = Int.MAX_VALUE)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    failures++
                                    AppLog.w("WorkspaceExport", "list files failed: $path: ${t.message}")
                                    emptyList()
                                }
                            listing[path] = children
                            children.filter { it.isDirectory }.forEach { collect(it.path) }
                        }

                        val rootListing =
                            try {
                                repository.listFiles(id = id, area = area, path = entry.path, limit = Int.MAX_VALUE)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                failures++
                                AppLog.w("WorkspaceExport", "list files failed: ${entry.path}: ${t.message}")
                                emptyList()
                            }
                        listing[entry.path] = rootListing
                        rootListing.filter { it.isDirectory }.forEach { collect(it.path) }

                        val plan = planWorkspaceFolderExport(entry.path, listing)
                        val dirDocs = mutableMapOf(entry.path to destinationTree)
                        val totalFiles = plan.count { !it.isDirectory }
                        var fileCount = 0
                        _folderExportProgress.value = FolderExportProgress(entry.name, 0, totalFiles)
                        for (item in plan) {
                            currentCoroutineContext().ensureActive()
                            val parent = dirDocs[item.parentPath]
                            if (parent == null) {
                                failures++
                                continue
                            }
                            if (item.isDirectory) {
                                val created = parent.createDirectory(item.name)
                                if (created == null) {
                                    failures++
                                } else {
                                    dirDocs[item.sourcePath] = created
                                }
                            } else {
                                var createdDoc: DocumentFile? = null
                                try {
                                    val fileDoc = parent.createFile("application/octet-stream", item.name)
                                        ?: error("create failed: ${item.name}")
                                    createdDoc = fileDoc
                                    val out = context.contentResolver.openOutputStream(fileDoc.uri)
                                        ?: error("openOutputStream failed: ${item.name}")
                                    out.use { stream ->
                                        repository.exportFile(
                                            id = id,
                                            area = area,
                                            path = item.sourcePath,
                                            outputStream = stream,
                                        )
                                    }
                                    fileCount++
                                    _folderExportProgress.value =
                                        FolderExportProgress(entry.name, fileCount, totalFiles)
                                } catch (e: CancellationException) {
                                    // 取消时删掉刚建出的空文件，避免留下 0 字节残留（“假成功”）
                                    runCatching { createdDoc?.delete() }
                                    throw e
                                } catch (t: Throwable) {
                                    failures++
                                    AppLog.w(
                                        "WorkspaceExport",
                                        "folder export failed: ${item.sourcePath}: ${t.message}",
                                    )
                                }
                            }
                        }
                        FolderExportOutcome(folderName = entry.name, fileCount = fileCount, failures = failures)
                    }
                } finally {
                    _folderExportProgress.value = null
                }
            onResult(outcome)
        }
    }

    /** 文件夹导出进度（仅导出进行中非 null）。 */
    data class FolderExportProgress(
        val folderName: String,
        val done: Int,
        val total: Int,
    )

    fun setShellCompatibilityMode(enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setShellCompatibilityMode(id, enabled)
                val workspace = repository.getById(id)
                _state.update { it.copy(workspace = workspace) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _settingsError.value = error.message.orEmpty()
            }
        }
    }

    fun setToolApproval(
        toolName: String,
        needsApproval: Boolean,
    ) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                terminalSessionManager.closeWorkspace(workspace.root)
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_rootfs_install)
            } finally {
                _installProgress.value = null
            }
        }
    }

    /** 从本地归档文件安装 rootfs（离线导入，无需下载）。 */
    fun installRootfsFromFile(archivePath: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.EXTRACTING)
            try {
                terminalSessionManager.closeWorkspace(workspace.root)
                repository.installRootfsFromFile(workspace.id, archivePath) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value =
                    error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_rootfs_install)
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous =
            _terminalState.getAndUpdate { state ->
                if (state.running) {
                    state
                } else {
                    state.copy(
                        running = true,
                        input = "",
                        history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                    )
                }
            }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_cmd_exec_failed)),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            _state.update { it.copy(workspace = workspace) }
            val distro = repository.readDistroInfo(id)
            _state.update { it.copy(distro = distro) }
        }
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val distro: WorkspaceDistroInfo? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(
        val command: String,
    ) : WorkspaceTerminalEntry

    data class Result(
        val result: WorkspaceCommandResult,
    ) : WorkspaceTerminalEntry

    data class Error(
        val message: String,
    ) : WorkspaceTerminalEntry
}

/** 文件夹导出结果：导出的文件数与失败项数。 */
data class FolderExportOutcome(
    val folderName: String,
    val fileCount: Int,
    val failures: Int,
)
