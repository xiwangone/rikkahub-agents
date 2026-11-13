package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.SettingsError
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.RootfsShellNotFoundException
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceDistroInfo
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellBackend
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceStats
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.log.AppLog

/** 名称非法字符集：`/` 与 `\` 是路径分隔符，其余为 Windows 保留字符；页内预检与 VM 校验共用一份。 */
internal val workspaceIllegalNameChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

@Suppress("TooManyFunctions", "LargeClass")
// 详情页单一 VM 固有职责（信息/镜像/CA/挂载/标签/导出/文件操作等），方法数与体量随功能自然增长；
// 转发型逻辑已尽量下沉 Repository，此处为局部豁免而非放宽全局阈值。
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

    private val _settingsError = MutableStateFlow<SettingsError?>(null)
    val settingsError = _settingsError.asStateFlow()

    private val _caRepairCount = MutableStateFlow<Int?>(null)

    /** 最近一次「修复 CA 证书」写入的沙箱数量（null = 无待提示结果）。 */
    val caRepairCount = _caRepairCount.asStateFlow()

    private val _upgradingPackages = MutableStateFlow(false)
    val upgradingPackages = _upgradingPackages.asStateFlow()

    private val _packageUpgradeResult = MutableStateFlow<PackageUpgradeOutcome?>(null)
    val packageUpgradeResult = _packageUpgradeResult.asStateFlow()

    fun dismissPackageUpgradeResult() {
        _packageUpgradeResult.value = null
    }

    /** 手动刷新资源画像的失败原因（null = 无待提示失败），详情页弹 Toast 后清零。 */
    val statsRefreshFailure = repository.statsRefreshFailure

    fun dismissStatsRefreshFailure() {
        repository.clearStatsRefreshFailure()
    }

    /** 资源面板（磁盘/包数/内核）：仓库层进程内缓存 + 后台采集（退出页面不中断，重进即有）。 */
    val stats: kotlinx.coroutines.flow.StateFlow<WorkspaceStats?> =
        repository.statsById
            .map { it[id] }
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
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                repository.mirrorsNow(),
            )

    /** 保存镜像配置并应用到已安装的 rootfs。 */
    fun applyMirrors(mirrors: me.rerere.workspace.WorkspaceMirrors) {
        viewModelScope.launch {
            runCatching { repository.setMirrors(mirrors) }
                .onFailure { _settingsError.value = SettingsError.of(it) }
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
                .onFailure { _settingsError.value = SettingsError.of(it) }
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
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                repository.sdcardEnabledNow(),
            )

    fun setSdcardAccess(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setSdcardAccess(enabled) }
                .onFailure { _settingsError.value = SettingsError.of(it) }
        }
    }

    /** 本地工作区 Shell 后端（三态：PROOT / ROOT_CHROOT / DISABLED）。 */
    val shellBackend: kotlinx.coroutines.flow.StateFlow<WorkspaceShellBackend> =
        repository.shellBackendFlow()
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                repository.shellBackendNow(),
            )

    private val _backendFallbackReason = MutableStateFlow(repository.shellBackendFallbackReason())
    val backendFallbackReason: kotlinx.coroutines.flow.StateFlow<String?> = _backendFallbackReason.asStateFlow()

    /** chroot 后端开关（兼容派生流）。 */
    val chrootEnabled: kotlinx.coroutines.flow.StateFlow<Boolean> =
        repository.chrootEnabledFlow()
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                repository.chrootEnabledNow(),
            )

    /** chroot 后端是否就绪（脚本缺失时为 false）；开关开着但未就绪 = 实际在跑 proot。 */
    val chrootBackendReady: Boolean = repository.chrootBackendReady()

    fun effectiveShellBackend(): WorkspaceShellBackend =
        repository.effectiveShellBackend(state.value.workspace?.root)

    fun setShellBackend(target: WorkspaceShellBackend) {
        viewModelScope.launch {
            runCatching {
                repository.setShellBackend(target)
                _backendFallbackReason.value = repository.shellBackendFallbackReason()
            }.onFailure {
                _backendFallbackReason.value = repository.shellBackendFallbackReason() ?: it.message
                _settingsError.value = SettingsError.of(it)
            }
        }
    }

    fun setChrootEnabled(enabled: Boolean) {
        setShellBackend(if (enabled) WorkspaceShellBackend.ROOT_CHROOT else WorkspaceShellBackend.PROOT)
    }

    /** 保存工作区画像标签（JSON 数组落库；阶段 3 任务路由的匹配键）。 */
    fun setTags(tags: List<String>) {
        viewModelScope.launch {
            runCatching { repository.setTags(id, tags) }
                .onSuccess {
                    val workspace = repository.getById(id)
                    AppLog.i("WorkspaceTags", "saved=$tags readback.tags=${workspace?.tags}")
                    _state.update { it.copy(workspace = workspace) }
                }
                .onFailure {
                    AppLog.w("WorkspaceTags", "save failed", it)
                    _settingsError.value = SettingsError.of(it)
                }
        }
    }

    init {
        loadWorkspace()
        refresh()
        repository.requestWorkspaceStats(id)
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                truncated = false,
                totalCount = 0,
                error = null,
                // 跨存储区粘贴没有意义（files 与 rootfs 是两棵树），切区即丢弃待复制条目
                clipboard = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), truncated = false, totalCount = 0, error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                truncated = false,
                totalCount = 0,
                error = null,
            )
        }
        refresh()
    }

    /** 仅强刷资源画像（详情页刷新按钮；文件列表刷新走 refresh()，避免导航路径全树重采）。 */
    fun refreshStats() {
        repository.refreshWorkspaceStats(id)
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.listFilesDetailed(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        entries = result.entries,
                        truncated = result.truncated,
                        totalCount = result.totalCount,
                        loading = false,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        truncated = false,
                        totalCount = 0,
                        loading = false,
                        error = error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_load),
                    )
                }
            }
        }
    }

    /** 在当前目录新建空文件；非法名/同名在 VM 层拦截并给出本地化文案。 */
    fun createFile(name: String) {
        viewModelScope.launch {
            createEntryAt(state.value.path, name, isDirectory = false)
                .onSuccess { refresh() }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_load)) }
                }
        }
    }

    /** 在当前目录新建文件夹；非法名/同名在 VM 层拦截并给出本地化文案。 */
    fun createFolder(name: String) {
        viewModelScope.launch {
            createEntryAt(state.value.path, name, isDirectory = true)
                .onSuccess { refresh() }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_load)) }
                }
        }
    }

    /**
     * 在 [parentPath] 下新建文件/文件夹，成功返回新建条目的相对路径。
     * 经绝对路径 API 落盘，FILES 与 LINUX 两个存储区都走同一条路；同名先按当前目录预检，
     * 数据层（已存在即拒）再兜底一次。
     */
    suspend fun createEntryAt(
        parentPath: String,
        rawName: String,
        isDirectory: Boolean,
    ): Result<String> {
        val name = rawName.trim()
        validateEntryName(name)?.let { return Result.failure(IllegalArgumentException(it)) }
        // 同名预检：建文件夹的后端是幂等的（已是目录直接返回），不拦会在此静默“成功”；
        // 只对当前目录预检，移动选择器里跨目录的新建由对话框按所列子目录预检。
        if (parentPath == state.value.path && state.value.entries.any { it.name == name }) {
            return Result.failure(
                IllegalArgumentException(
                    context.getString(me.rerere.rikkahub.R.string.workspace_detail_rename_exists, name),
                ),
            )
        }
        val target = childPath(parentPath, name)
        val layout = repository.layout(id)
            ?: return Result.failure(IllegalStateException(context.getString(me.rerere.rikkahub.R.string.workspace_err_load)))
        val absolutePath = layout.absolutePath(state.value.area, target)
        return runCatching {
            if (isDirectory) {
                repository.createAbsoluteFolder(id, absolutePath)
            } else {
                repository.writeAbsoluteText(id, absolutePath, "", overwrite = false)
            }
            target
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        if (rejectLinuxWrite(me.rerere.rikkahub.R.string.common_delete)) return
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

    /**
     * 重命名 = 同目录内的 move（数据层无独立 rename 原语）。
     *
     * 非法字符/空名在此拦截并给出本地化文案；重名由数据层的「Target already exists」兜底
     * （UI 侧对话框已做一次预检，走到这里说明是并发或预检漏掉的场景）。
     */
    fun rename(
        entry: WorkspaceFileEntry,
        rawName: String,
    ) {
        if (rejectLinuxWrite(me.rerere.rikkahub.R.string.common_rename)) return
        val newName = rawName.trim()
        val invalid = validateEntryName(newName)
        if (invalid != null) {
            _state.update { it.copy(error = invalid) }
            return
        }
        if (newName == entry.name) return
        val parent = entry.path.substringBeforeLast('/', missingDelimiterValue = "")
        val target = childPath(parent, newName)
        viewModelScope.launch {
            runCatching {
                repository.moveFile(
                    id = id,
                    source = entry.path,
                    target = target,
                    overwrite = false,
                    area = state.value.area,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_rename)) }
            }
        }
    }

    /** 把 [entry] 移到 [targetDir]（保持原名）；目标目录由页面上的目录选择器给出。 */
    fun moveEntry(
        entry: WorkspaceFileEntry,
        targetDir: String,
    ) {
        if (rejectLinuxWrite(me.rerere.rikkahub.R.string.common_move)) return
        val target = childPath(targetDir, entry.name)
        if (target == entry.path || (entry.isDirectory && target.startsWith("${entry.path}/"))) {
            _state.update { it.copy(error = context.getString(me.rerere.rikkahub.R.string.workspace_detail_move_same_dir)) }
            return
        }
        viewModelScope.launch {
            // 选择器只知道目录，看不到目标目录里已有什么 —— 先列一次，把「目标已有同名项」
            // 变成中文提示（数据层同样会拒，但那是英文原文）
            val clash = runCatching {
                repository.listFiles(id = id, area = state.value.area, path = targetDir)
            }.getOrDefault(emptyList()).any { it.name == entry.name }
            if (clash) {
                _state.update {
                    it.copy(error = context.getString(me.rerere.rikkahub.R.string.workspace_detail_rename_exists, entry.name))
                }
                return@launch
            }
            runCatching {
                repository.moveFile(
                    id = id,
                    source = entry.path,
                    target = target,
                    overwrite = false,
                    area = state.value.area,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_move)) }
            }
        }
    }

    /** 记入「待复制条目」；粘贴目标是当时的当前目录，跨存储区不允许。 */
    fun copyToClipboard(entry: WorkspaceFileEntry) {
        _state.update {
            it.copy(
                clipboard = WorkspaceClipboard(
                    path = entry.path,
                    name = entry.name,
                    isDirectory = entry.isDirectory,
                    area = it.area,
                ),
            )
        }
    }

    fun clearClipboard() {
        _state.update { it.copy(clipboard = null) }
    }

    /**
     * 把剪贴板条目复制到当前目录（[overwrite] = false 时同名直接失败，由 UI 先弹覆盖确认）。
     * 成功后清空剪贴板，避免重复粘贴。
     */
    fun paste(overwrite: Boolean) {
        val clip = state.value.clipboard ?: return
        if (rejectLinuxWrite(me.rerere.rikkahub.R.string.workspace_detail_paste)) return
        if (clip.area != state.value.area) {
            _state.update { it.copy(error = context.getString(me.rerere.rikkahub.R.string.workspace_detail_paste_diff_area)) }
            return
        }
        val target = childPath(state.value.path, clip.name)
        if (target == clip.path || (clip.isDirectory && target.startsWith("${clip.path}/"))) {
            _state.update { it.copy(error = context.getString(me.rerere.rikkahub.R.string.workspace_detail_move_same_dir)) }
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.copyFile(
                    id = id,
                    source = clip.path,
                    target = target,
                    overwrite = overwrite,
                    area = clip.area,
                )
            }.onSuccess {
                _state.update { it.copy(clipboard = null) }
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_copy)) }
            }
        }
    }

    /** 目录选择器用：列 [path] 下的子目录（失败回空列表，由对话框显示空态）。 */
    suspend fun listSubDirectories(path: String): List<WorkspaceFileEntry> =
        runCatching {
            repository.listFiles(id = id, area = state.value.area, path = path)
        }.getOrDefault(emptyList()).filter { it.isDirectory }

    /**
     * LINUX（rootfs）区只读拦截：文件页的删除/改名/移动/粘贴统一在此拦下并给中文提示，
     * 与编辑页的只读保持一致。返回 true 表示已拦截、调用方直接返回。
     * rootfs 本身的权限不动，只是不让界面操作动系统文件。
     */
    private fun rejectLinuxWrite(actionRes: Int): Boolean {
        if (state.value.area != WorkspaceStorageArea.LINUX) return false
        _state.update {
            it.copy(
                error = context.getString(me.rerere.rikkahub.R.string.workspace_file_readonly_cannot_action,
                    context.getString(actionRes),
                ),
            )
        }
        return true
    }

    /** Windows 保留字符一并拒：工作区文件常被导出到桌面端，允许了会在对端炸。 */
    private fun validateEntryName(name: String): String? = when {
        name.isBlank() || name == "." || name == ".." ->
            context.getString(me.rerere.rikkahub.R.string.workspace_detail_rename_empty)
        workspaceIllegalNameChars.any { name.contains(it) } ->
            context.getString(me.rerere.rikkahub.R.string.workspace_detail_rename_invalid, workspaceIllegalNameChars.joinToString(" "))
        else -> null
    }

    private fun childPath(
        dir: String,
        name: String,
    ): String = if (dir.isBlank()) name else "${dir.trimEnd('/')}/$name"

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
        uri: Uri,
        resolver: ContentResolver,
    ) {
        viewModelScope.launch {
            runCatching {
                val outputStream = withContext(Dispatchers.IO) { resolver.openOutputStream(uri) }
                    ?: error("Cannot open target output stream: $uri")
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
    ): File? = repository.resolveFile(id, area, entry.path)

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(
        entry: WorkspaceFileEntry,
        cacheDir: File,
        onFail: (() -> Unit)? = null,
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
                onFail?.invoke()
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
        folderExportJob = viewModelScope.launch {
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
                                // createDirectory 在同名目录已存在时返回 null：此时复用已存在的
                                // 同名目录继续往里导（合并导出），而不是计一次失败、整棵子树被跳过。
                                // 同名文件不算目录，仍计失败。
                                val created = parent.createDirectory(item.name)
                                    ?: parent.findFile(item.name)?.takeIf { it.isDirectory }
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
                    folderExportJob = null
                }
            onResult(outcome)
        }
    }

    /** 正在进行的文件夹导出任务（null = 没有在导出的）；进度弹窗的「取消」按钮调这个。 */
    private var folderExportJob: Job? = null

    /** 取消正在进行的文件夹导出：循环里的 ensureActive 会抛 CancellationException 中断导出。 */
    fun cancelFolderExport() {
        folderExportJob?.cancel()
    }

    /** Export selected files and directory subtrees as a single ZIP archive. */
    fun exportEntriesZip(
        entries: List<WorkspaceFileEntry>,
        outputStream: OutputStream,
        onCancelled: () -> Unit = {},
        onResult: (Result<Int>) -> Unit,
    ) {
        val selected = entries.distinctBy { it.path }.toList()
        if (selected.isEmpty()) {
            runCatching { outputStream.close() }
            onResult(Result.failure(IllegalArgumentException("No workspace entries selected")))
            return
        }
        viewModelScope.launch {
            val area = state.value.area
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    java.util.zip.ZipOutputStream(outputStream).use { zip ->
                        var written = 0
                        for (entry in selected) {
                            currentCoroutineContext().ensureActive()
                            if (!entry.isDirectory) {
                                val zipName = safeWorkspaceZipEntryName(entry.name, "payload", false)
                                    ?.removeSuffix("/payload") ?: error("Unsafe path in workspace export: ${entry.path}")
                                zip.putNextEntry(java.util.zip.ZipEntry(zipName))
                                repository.exportFile(id = id, area = area, path = entry.path, outputStream = zip)
                                zip.closeEntry()
                                written++
                                continue
                            }
                            val listing = mutableMapOf<String, List<WorkspaceFileEntry>>()
                            suspend fun collect(path: String) {
                                currentCoroutineContext().ensureActive()
                                val children = repository.listFiles(id = id, area = area, path = path, limit = Int.MAX_VALUE)
                                listing[path] = children
                                children.filter { it.isDirectory }.forEach { collect(it.path) }
                            }
                            collect(entry.path)
                            val plan = planWorkspaceFolderExport(entry.path, listing)
                            // Include an empty directory explicitly; each root gets its own top-level ZIP folder.
                            val rootName = safeWorkspaceZipEntryName(entry.name, "marker", true)
                                ?.removeSuffix("marker/") ?: error("Unsafe path in workspace export: ${entry.path}")
                            zip.putNextEntry(java.util.zip.ZipEntry(rootName))
                            zip.closeEntry()
                            for (item in plan) {
                                currentCoroutineContext().ensureActive()
                                val relative = item.sourcePath.removePrefix(entry.path.trimEnd('/') + "/")
                                val zipName = safeWorkspaceZipEntryName(entry.name, relative, item.isDirectory)
                                    ?: error("Unsafe path in workspace export: ${item.sourcePath}")
                                zip.putNextEntry(java.util.zip.ZipEntry(zipName))
                                if (!item.isDirectory) {
                                    repository.exportFile(id = id, area = area, path = item.sourcePath, outputStream = zip)
                                    written++
                                }
                                zip.closeEntry()
                            }
                        }
                        zip.finish()
                        written
                    }
                }
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) { runCatching(onCancelled); throw it } }
            if (result.isFailure) _state.update { it.copy(error = result.exceptionOrNull()?.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_export)) }
            onResult(result)
        }
    }

    fun exportFolderZip(entry: WorkspaceFileEntry, outputStream: OutputStream, onResult: (Result<Int>) -> Unit) =
        exportEntriesZip(listOf(entry), outputStream, onResult = onResult)
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
                _settingsError.value = SettingsError.of(error)
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

    private fun launchRootfsTask(
        initialStage: RootfsInstallStage,
        action: suspend (WorkspaceEntity, (RootfsInstallProgress) -> Unit) -> Unit,
    ) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = initialStage)
            try {
                terminalSessionManager.closeWorkspace(workspace.root)
                action(workspace) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: RootfsShellNotFoundException) {
                _installError.value =
                    context.getString(me.rerere.rikkahub.R.string.workspace_err_rootfs_shell_missing)
            } catch (error: Throwable) {
                _installError.value =
                    error.message ?: context.getString(me.rerere.rikkahub.R.string.workspace_err_rootfs_install)
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun installRootfs(url: String) =
        launchRootfsTask(RootfsInstallStage.DOWNLOADING) { workspace, onProgress ->
            repository.installRootfs(workspace.id, url, onProgress)
        }

    /** 从本地归档文件安装 rootfs（离线导入，无需下载）。 */
    fun installRootfsFromFile(archivePath: String) =
        launchRootfsTask(RootfsInstallStage.EXTRACTING) { workspace, onProgress ->
            repository.installRootfsFromFile(workspace.id, archivePath, onProgress)
        }

    /** 无损覆盖更新发行版 rootfs（保留用户数据目录与已有镜像/证书配置）。 */
    fun updateRootfs(url: String) =
        launchRootfsTask(RootfsInstallStage.DOWNLOADING) { workspace, onProgress ->
            repository.updateRootfs(workspace.id, url, onProgress)
        }

    /** 从本地归档文件无损覆盖更新发行版 rootfs。 */
    fun updateRootfsFromFile(archivePath: String) =
        launchRootfsTask(RootfsInstallStage.EXTRACTING) { workspace, onProgress ->
            repository.updateRootfsFromFile(workspace.id, archivePath, onProgress)
        }

    /** 原位升级当前发行版已安装软件包（apt / apk / dnf / pacman）。 */
    fun upgradeDistroPackages() {
        if (_upgradingPackages.value) return
        viewModelScope.launch {
            _upgradingPackages.value = true
            try {
                val result = repository.upgradeDistroPackages(id)
                loadWorkspace()
                if (result.exitCode == 0) {
                    _packageUpgradeResult.value = PackageUpgradeOutcome(success = true)
                } else {
                    val detail = result.stderr.ifBlank { result.stdout }.take(200)
                    _packageUpgradeResult.value =
                        PackageUpgradeOutcome(
                            success = false,
                            detail = detail.ifBlank { "exit ${result.exitCode}" },
                        )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _packageUpgradeResult.value =
                    PackageUpgradeOutcome(
                        success = false,
                        detail = error.message.orEmpty(),
                    )
            } finally {
                _upgradingPackages.value = false
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
    /** 当前目录是否因 500 条上限被截断（由 refresh 经 listFilesDetailed 回填）。 */
    val truncated: Boolean = false,
    /** 当前目录截断前的条目总数（未截断时与 entries.size 一致）。 */
    val totalCount: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val clipboard: WorkspaceClipboard? = null,
)

/** 页内「复制 → 进目标目录 → 粘贴」用的待复制条目（仅存路径，不搬数据）。 */
data class WorkspaceClipboard(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val area: WorkspaceStorageArea,
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

/** 发行版软件包原位升级结果。 */
data class PackageUpgradeOutcome(
    val success: Boolean,
    val detail: String = "",
)
