package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import me.rerere.workspace.WorkspaceMirrors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.SshWorkspaceDraft
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.message
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import java.io.File
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.BackgroundStatus
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceTreeResult
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceDistroInfo
import me.rerere.workspace.WorkspaceEnvironmentInfo
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceListResult
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellBackend
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStats
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.collectWorkspaceStats
import me.rerere.workspace.ssh.SshConnectionPool
import me.rerere.workspace.ssh.SshProbeResult
import me.rerere.workspace.ssh.SshShell
import me.rerere.workspace.ssh.SshWorkspaceBackend
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.workspace.WorkspaceMountSwitch

/** chroot 开启失败的原因：UI 按此取本地化文案（异常 message 保留英文供日志排障）。 */
enum class ChrootEnableFailure { ROOT_REQUIRED, NO_WORKSPACE, PROBE_FAILED }

/** chroot 开启失败。[detail] 是可展示的技术细节（探测输出片段等）。 */
class ChrootEnableException(
    val reason: ChrootEnableFailure,
    val detail: String = "",
    message: String,
) : IllegalStateException(message)

/**
 * 设置失败的可展示形态：已知原因带 [reason]（UI 取本地化文案并附 [detail]），
 * 未知异常兜底展示 [raw] 原文（不翻译，便于排障）。
 */
data class SettingsError(val reason: ChrootEnableFailure?, val detail: String, val raw: String) {
    internal val remoteEnvironments = ConcurrentHashMap<String, RemoteEnvironment>()

    companion object {
        fun of(e: Throwable): SettingsError = if (e is ChrootEnableException) {
            SettingsError(reason = e.reason, detail = e.detail, raw = e.message.orEmpty())
        } else {
            SettingsError(reason = null, detail = "", raw = e.message.orEmpty())
        }
    }
}

@Suppress("LongParameterList", "LargeClass", "TooGenericExceptionCaught")
// 工作区仓库聚合本地/SFTP 两类后端与挂载、Rootfs、凭据等协作者；拆成多个类会把这些
// 协作者分散到多处而割裂同一职责（上传/列举/命令/导出都依赖同一组依赖）。
// 网络/IO 失败面广，统一兜住后按可用性降级（拿不到环境信息返回 null 等）。
class WorkspaceRepository(
    internal val dao: WorkspaceDAO,
    internal val manager: WorkspaceManager,
    internal val rootfsInstaller: RootfsInstaller,
    internal val settingsStore: SettingsStore,
    internal val mountSwitch: WorkspaceMountSwitch,
    internal val sshPool: SshConnectionPool,
    internal val vaultRepository: CredentialVaultRepository,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    private fun me.rerere.rikkahub.data.datastore.Settings.toWorkspaceMirrors(): WorkspaceMirrors =
        WorkspaceMirrors(
            apk = workspaceApkMirror,
            apt = workspaceAptMirror,
            pip = workspacePipMirror,
            npm = workspaceNpmMirror,
        )

    /** 当前沙箱镜像配置（全局配置，应用时写入所有已安装 rootfs）。 */
    fun mirrorsNow(): WorkspaceMirrors = settingsStore.settingsFlow.value.toWorkspaceMirrors()

    fun mirrorsFlow(): Flow<WorkspaceMirrors> =
        settingsStore.settingsFlow.map { s -> s.toWorkspaceMirrors() }

    /** 保存镜像配置并应用到所有已安装的 rootfs；返回实际写入成功的沙箱数量。 */
    suspend fun setMirrors(mirrors: WorkspaceMirrors): Int = withContext(Dispatchers.IO) {
        settingsStore.update { current ->
            current.copy(
                workspaceApkMirror = mirrors.apk,
                workspaceAptMirror = mirrors.apt,
                workspacePipMirror = mirrors.pip,
                workspaceNpmMirror = mirrors.npm,
            )
        }
        var applied = 0
        for (workspace in dao.getAll()) {
            if (!manager.hasRootfs(workspace.root)) continue
            val linuxDir = manager.linuxDir(workspace.root)
            if (me.rerere.workspace.applyWorkspaceMirrors(linuxDir, mirrors).isSuccess) {
                applied++
            }
            // 换源后 https 可用性依赖 CA bundle：缺失时顺带从系统 CA 补齐（不覆盖已有）
            me.rerere.workspace.installCaCerts(linuxDir, force = false)
        }
        applied
    }

    /**
     * 把 Android 系统 CA 合并写入所有已安装 rootfs（修复最小 rootfs 缺 CA bundle 导致 https 不可用）。
     * 返回写入成功的沙箱数量。
     */
    suspend fun repairCaCerts(): Int = withContext(Dispatchers.IO) {
        var repaired = 0
        for (workspace in dao.getAll()) {
            if (!manager.hasRootfs(workspace.root)) continue
            if (me.rerere.workspace.installCaCerts(manager.linuxDir(workspace.root)).isSuccess) {
                repaired++
            }
        }
        repaired
    }

    /** 手机存储挂载开关（/sdcard，可选挂载）：设置流，订阅时同步内存桥。 */
    fun sdcardEnabledNow(): Boolean = settingsStore.settingsFlow.value.workspaceSdcardEnabled

    fun sdcardEnabledFlow(): Flow<Boolean> =
        settingsStore.settingsFlow
            .map { it.workspaceSdcardEnabled }
            .onEach { mountSwitch.sdcardEnabled = it }

    /** 容器专用 DNS 配置同步流：订阅时自动同步到内存桥 [mountSwitch]。 */
    fun shellDnsFlow(): Flow<Pair<String, List<String>>> =
        settingsStore.settingsFlow
            .map { it.shellDnsMode to it.shellDnsServers }
            .onEach { (mode, servers) -> mountSwitch.updateShellDns(mode, servers) }

    /** 开关手机存储挂载：写设置 + 同步内存桥（对后续 shell 启动/路径解析即时生效）。 */
    suspend fun setSdcardAccess(enabled: Boolean) {
        settingsStore.update { current -> current.copy(workspaceSdcardEnabled = enabled) }
        mountSwitch.sdcardEnabled = enabled
    }

    private val rootChrootAuditLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 本地工作区 Shell 后端（三态：PROOT / ROOT_CHROOT / DISABLED）。 */
    fun shellBackendNow(): WorkspaceShellBackend =
        WorkspaceShellBackend.fromPreference(
            rawBackend = settingsStore.settingsFlow.value.workspaceShellBackend.name,
            legacyChrootEnabled = settingsStore.settingsFlow.value.workspaceChrootEnabled,
        )

    fun shellBackendFlow(): Flow<WorkspaceShellBackend> =
        settingsStore.settingsFlow
            .map {
                WorkspaceShellBackend.fromPreference(
                    rawBackend = it.workspaceShellBackend.name,
                    legacyChrootEnabled = it.workspaceChrootEnabled,
                )
            }
            .onEach { mountSwitch.backend = it }

    fun effectiveShellBackend(root: String? = null): WorkspaceShellBackend =
        manager.effectiveBackend(root ?: "default")

    fun shellBackendFallbackReason(): String? = manager.lastFallbackReason

    /** chroot 后端开关（兼容派生属性）：设置流，订阅时同步内存桥。 */
    fun chrootEnabledNow(): Boolean = shellBackendNow() == WorkspaceShellBackend.ROOT_CHROOT

    fun chrootEnabledFlow(): Flow<Boolean> =
        shellBackendFlow().map { it == WorkspaceShellBackend.ROOT_CHROOT }

    /**
     * 切换本地 Shell 后端（PROOT / ROOT_CHROOT / DISABLED）：
     * - 切离 ROOT_CHROOT 前执行兜底卸载（umount -R + 清理 .chroot-* 状态文件），不改数据目录；
     * - 切到 ROOT_CHROOT 时若非 root / su 被拒 / probe 失败，自动回退 PROOT 并记录回退原因供 UI 顶栏明示；
     * - 首次成功启用写审计日志（时间 + 后端 + S1 判定分支，不含路径与设备标识）。
     */
    suspend fun setShellBackend(target: WorkspaceShellBackend) = withContext(Dispatchers.IO) {
        if (target != WorkspaceShellBackend.ROOT_CHROOT) {
            dao.getAll().forEach { ws ->
                manager.unmountChroot(ws.root)?.let { r ->
                    if (r.exitCode != 0) {
                        AppLog.w("Workspace", "chroot unmount on switch failed: ${r.stderr}")
                    }
                }
                manager.fixChrootOwner(ws.root)?.let { r ->
                    if (r.exitCode != 0) {
                        AppLog.w("Workspace", "chroot fixowner on switch failed: ${r.stderr}")
                    }
                }
            }
            manager.resetBackendState()
            settingsStore.update { current ->
                current.copy(
                    workspaceShellBackend = target,
                    workspaceChrootEnabled = false,
                )
            }
            mountSwitch.backend = target
            return@withContext
        }

        if (!manager.chrootRunnerReady() || !isSuAccessible()) {
            val reason = if (!manager.chrootRunnerReady()) "helper_scripts_missing" else "su_not_accessible"
            manager.recordFallback(reason)
            settingsStore.update { current ->
                current.copy(
                    workspaceShellBackend = WorkspaceShellBackend.PROOT,
                    workspaceChrootEnabled = false,
                )
            }
            mountSwitch.backend = WorkspaceShellBackend.PROOT
            AppLog.w("WorkspaceAudit", "ts=${System.currentTimeMillis()} backend=PROOT fallback_from=ROOT_CHROOT branch=C reason=$reason")
            throw ChrootEnableException(
                ChrootEnableFailure.ROOT_REQUIRED,
                message = "chroot requires root: su not accessible on this device. " +
                    "Grant this app root access in your root manager (e.g. Magisk) and retry.",
            )
        }

        val workspace = dao.getAll().firstOrNull()
            ?: throw ChrootEnableException(
                ChrootEnableFailure.NO_WORKSPACE,
                message = "chroot probe: no workspace",
            )
        val probeResult = manager.probeChroot(workspace.root)
        if (probeResult != null && !probeResult.supported) {
            val reason = "probe_failed(branch=${probeResult.branch}, fail=${probeResult.fail})"
            manager.recordFallback(reason)
            settingsStore.update { current ->
                current.copy(
                    workspaceShellBackend = WorkspaceShellBackend.PROOT,
                    workspaceChrootEnabled = false,
                )
            }
            mountSwitch.backend = WorkspaceShellBackend.PROOT
            AppLog.w(
                "WorkspaceAudit",
                "ts=${System.currentTimeMillis()} backend=PROOT fallback_from=ROOT_CHROOT branch=${probeResult.branch} fail=${probeResult.fail}",
            )
            throw ChrootEnableException(
                ChrootEnableFailure.PROBE_FAILED,
                detail = probeResult.raw.take(400),
                message = "chroot probe failed (${probeResult.fail} checks)",
            )
        }

        if (rootChrootAuditLogged.compareAndSet(false, true)) {
            val branch = probeResult?.branch ?: "A"
            val pass = probeResult?.pass ?: 0
            val warn = probeResult?.warn ?: 0
            AppLog.i(
                "WorkspaceAudit",
                "ts=${System.currentTimeMillis()} backend=ROOT_CHROOT branch=$branch pass=$pass fail=0 warn=$warn",
            )
        }
        manager.resetBackendState(workspace.root)
        settingsStore.update { current ->
            current.copy(
                workspaceShellBackend = WorkspaceShellBackend.ROOT_CHROOT,
                workspaceChrootEnabled = true,
            )
        }
        mountSwitch.backend = WorkspaceShellBackend.ROOT_CHROOT
    }

    /** 开关 chroot 后端（兼容入口）：委托给 [setShellBackend]。 */
    suspend fun setChrootEnabled(enabled: Boolean) =
        setShellBackend(if (enabled) WorkspaceShellBackend.ROOT_CHROOT else WorkspaceShellBackend.PROOT)

    /** chroot 后端是否就绪（脚本缺失时为 false → 开关开着实际也走 proot；供 UI 显性提示）。 */
    fun chrootBackendReady(): Boolean = manager.chrootRunnerReady()

    /** chroot 前置预检：su 可执行且 3s 内响应（无 root 设备抛 IOException / 未授权则超时） */
    private fun isSuAccessible(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").start()
        val ok = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
        p.destroy()
        ok
    }.getOrDefault(false)

    /** chroot 能力探测透传（root 层探测结果；null = 非实验 runner） */
    fun probeChroot(root: String) = manager.probeChroot(root)

    /** 保存工作区画像标签（JSON 数组落库；阶段 3 任务路由的匹配键）。 */
    suspend fun setTags(id: String, tags: List<String>) {
        dao.getById(id) ?: error("Workspace not found: $id")
        dao.setTags(id, JsonInstant.encodeToString(tags), System.currentTimeMillis())
    }

    /** 资源面板采集（内部）：查实体 → 采集。失败向上抛，由调用方兜底。 */
    private suspend fun workspaceStats(id: String): WorkspaceStats = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // 远程工作区没有本地目录可扫, 采集结果全是 0, 不如不采（调用方 runCatching 兜住后详情页显示 "-"）
        requireLocalRootfs(workspace, "workspace stats collection")
        collectWorkspaceStats(manager.workspaceDir(workspace.root), manager.linuxDir(workspace.root))
    }

    // ---------- 资源面板：采集与页面生命周期解耦 + 进程内缓存 ----------
    // 大区（数万文件）遍历可达分钟级；挂在页面订阅生命周期上（WhileSubscribed）会随页面
    // 退出被取消 → 大工作区永远采不完（反复进出始终 "-"）。
    // 改为独立 scope 后台采集，结果驻留进程内缓存；持久化（跨进程）列为后续。

    private val statsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _statsById = MutableStateFlow<Map<String, WorkspaceStats>>(emptyMap())

    /** 启动恢复门闩：requestWorkspaceStats 先等它完成，避免恢复未落时误判空缓存而重采。 */
    private var restoreJob: Job? = null

    init {
        // 跨进程持久化：进程启动即恢复上次采集结果，详情页秒显；随后按 TTL 静默刷新
        restoreJob = statsScope.launch {
            val json = settingsStore.settingsFlow.first().workspaceStatsCache
            val restored = parseCache(json).mapValues { (_, c) ->
                WorkspaceStats(c.rootBytes, c.packageCount, c.kernel)
            }
            if (restored.isNotEmpty()) _statsById.value = restored
        }
    }

    /** key = workspace id。采集成功即持久化（跨进程），失败不落缓存。 */
    val statsById: StateFlow<Map<String, WorkspaceStats>> = _statsById.asStateFlow()

    /**
     * 最近一次手动刷新资源画像的失败原因（null = 无失败/已消费）。
     * 采集本身是后台任务，失败只记日志用户看不到；经此状态交给详情页弹 Toast。
     * 自动采集（[requestWorkspaceStats]）的失败不进这里，避免后台静默采集也弹提示。
     */
    private val _statsRefreshFailure = MutableStateFlow<String?>(null)
    val statsRefreshFailure: StateFlow<String?> = _statsRefreshFailure.asStateFlow()

    /** 详情页消费掉刷新失败提示后清零，避免重组/重进重复弹。 */
    fun clearStatsRefreshFailure() {
        _statsRefreshFailure.value = null
    }

    /** 请求采集 [id] 的资源画像：缓存未过期（24h）跳过，否则后台采集并持久化（不阻塞、不随页面退出中断）。 */
    fun requestWorkspaceStats(id: String) {
        statsScope.launch {
            restoreJob?.join()
            val cachedAt = parseCache(settingsStore.settingsFlow.first().workspaceStatsCache)[id]?.at ?: 0L
            if (_statsById.value.containsKey(id) && System.currentTimeMillis() - cachedAt < STATS_TTL_MS) {
                return@launch
            }
            runCatching { workspaceStats(id) }
                .onSuccess { stats ->
                    AppLog.i(
                        "WorkspaceStats",
                        "cached id=$id: rootBytes=${stats.rootBytes}, packages=${stats.packageCount}, kernel=${stats.kernel}",
                    )
                    _statsById.value = _statsById.value + (id to stats)
                    persistStats(id, stats)
                }
                .onFailure { AppLog.e("WorkspaceStats", "collect failed for $id", it) }
        }
    }

    /** 强制重采（详情页手动刷新用），无视 TTL。失败原因进 [statsRefreshFailure] 供 UI 提示。 */
    fun refreshWorkspaceStats(id: String) {
        statsScope.launch {
            runCatching { workspaceStats(id) }
                .onSuccess { stats ->
                    _statsById.value = _statsById.value + (id to stats)
                    persistStats(id, stats)
                }
                .onFailure {
                    AppLog.e("WorkspaceStats", "refresh failed for $id", it)
                    _statsRefreshFailure.value = it.message ?: it::class.simpleName ?: "?"
                }
        }
    }

    private suspend fun persistStats(id: String, stats: WorkspaceStats) {
        runCatching {
            settingsStore.update { current ->
                val map = parseCache(current.workspaceStatsCache).toMutableMap()
                map[id] = CachedStats(stats.rootBytes, stats.packageCount, stats.kernel, System.currentTimeMillis())
                // 上限保护：只留最近 50 个工作区的画像
                val trimmed = map.entries.sortedByDescending { it.value.at }.take(50).associate { it.toPair() }
                current.copy(workspaceStatsCache = JsonInstant.encodeToString(trimmed))
            }
        }
    }

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            // 文件不在本机的工作区 (远程) 没有本地目录和 Rootfs, 无从校验
            if (!workspace.capabilities().localFiles) continue
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                AppLog.i(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                AppLog.i(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    /** 所有工作区（按创建顺序）。授权写沙箱 token 时取第一个（主工作区）。 */
    suspend fun getAll(): List<WorkspaceEntity> = dao.getAll()

    /**
     * 把「工作区 id 或名字」解析成工作区 id；解析不了返回 null。
     *
     * 模型在 `subagent_dispatch(workspace=…)` / `workspace_shell(workspace=…)` 里经常把
     * `workspace_list` 返回的 **name**（乃至发行版名）当 id 传。只认 id 的老逻辑会让这种
     * 调用直接失败：子代理路径上 uuid 解析再把名字吞成 null ⇒ 一个工作区工具都注入不了，
     * 模型按「无法完成就只回一段说明」收尾 —— 用户看到的就是「零产物 + 一句话」。
     *
     * 顺序：id 精确命中 → 名字大小写不敏感唯一命中。名字不唯一时返回 null，由调用方
     * 报错并列候选（[describeWorkspaces]）—— UI 创建时名字唯一（[create] 有
     * isNameTaken 校验），重名只可能是外部导入的数据，防御性不猜。
     */
    suspend fun resolveWorkspaceId(requested: String): String? {
        val query = requested.trim()
        if (query.isEmpty()) return null
        getById(query)?.let { return it.id }
        return getAll()
            .filter { it.name.equals(query, ignoreCase = true) }
            .singleOrNull()
            ?.id
    }

    /** `name (id)` 清单，供「未知工作区」报错时给模型/用户列候选。 */
    suspend fun describeWorkspaces(): String =
        getAll().joinToString(", ") { "${it.name} (${it.id})" }

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    suspend fun probeSsh(id: String?, draft: SshWorkspaceDraft): SshProbeResult = probeSshImpl(id, draft)

    suspend fun saveSsh(id: String?, draft: SshWorkspaceDraft): WorkspaceEntity = saveSshImpl(id, draft)

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean) {
        dao.setShellCompatibilityMode(id, enabled, System.currentTimeMillis())
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    private fun postInstallSetup(root: String) {
        val linuxDir = manager.linuxDir(root)
        me.rerere.workspace.applyWorkspaceMirrors(linuxDir, mirrorsNow())
            .onFailure { AppLog.w("Workspace", "post-install mirror setup failed for $root", it) }
        me.rerere.workspace.installCaCerts(linuxDir, force = false)
            .onFailure { AppLog.w("Workspace", "post-install CA setup failed for $root", it) }
    }

    private suspend fun executeRootfsMutation(
        id: String,
        featureName: String?,
        restoreStateOnFailure: Boolean,
        logTag: String,
        block: (WorkspaceEntity) -> Unit,
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        if (featureName != null) {
            requireLocalRootfs(workspace, featureName)
        }
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            runInterruptible(Dispatchers.IO) {
                block(workspace)
                postInstallSetup(workspace.root)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            refreshWorkspaceStats(id)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs operation cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            AppLog.e(TAG, "$logTag failed: workspace=${workspace.id}, root=${workspace.root}", e)
            if (restoreStateOnFailure) {
                restoreShellState(workspace)
            } else {
                updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            }
            throw e
        }
    }

    /** 从本地归档文件安装 rootfs（离线/内网分发场景，无需下载）。 */
    suspend fun installRootfsFromFile(
        id: String,
        archivePath: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean =
        executeRootfsMutation(
            id = id,
            featureName = "Rootfs install from file",
            restoreStateOnFailure = false,
            logTag = "installRootfsFromFile($archivePath)",
        ) { workspace ->
            rootfsInstaller.installFromFile(workspace.root, File(archivePath), onProgress)
        }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean =
        executeRootfsMutation(
            id = id,
            featureName = null,
            restoreStateOnFailure = false,
            logTag = "installRootfs($url)",
        ) { workspace ->
            rootfsInstaller.install(workspace.root, url, onProgress)
        }

    /** 无损覆盖更新发行版 rootfs（保留用户数据目录与已有镜像/证书配置）。 */
    suspend fun updateRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean =
        executeRootfsMutation(
            id = id,
            featureName = "Rootfs update",
            restoreStateOnFailure = true,
            logTag = "updateRootfs($url)",
        ) { workspace ->
            rootfsInstaller.update(workspace.root, url, onProgress)
        }

    /** 从本地归档无损覆盖更新发行版 rootfs。 */
    suspend fun updateRootfsFromFile(
        id: String,
        archivePath: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean =
        executeRootfsMutation(
            id = id,
            featureName = "Rootfs update from file",
            restoreStateOnFailure = true,
            logTag = "updateRootfsFromFile($archivePath)",
        ) { workspace ->
            rootfsInstaller.updateFromFile(workspace.root, File(archivePath), onProgress)
        }

    /** 在现有发行版内通过系统包管理器（apk/apt/dnf/pacman）原位升级已安装软件包。 */
    suspend fun upgradeDistroPackages(id: String): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        requireLocalRootfs(workspace, "Distro package upgrade")
        val linuxDir = manager.linuxDir(workspace.root)
        withContext(Dispatchers.IO) {
            runCatching { me.rerere.workspace.applyWorkspaceMirrors(linuxDir, mirrorsNow()) }
            runCatching { me.rerere.workspace.installCaCerts(linuxDir, force = false) }
        }
        val upgradeCmd =
            "if command -v apk >/dev/null 2>&1; then " +
                "apk update && apk upgrade --available; " +
                "elif command -v apt-get >/dev/null 2>&1; then " +
                "export DEBIAN_FRONTEND=noninteractive && apt-get update && " +
                "apt-get dist-upgrade -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold; " +
                "elif command -v dnf >/dev/null 2>&1; then " +
                "dnf upgrade -y; " +
                "elif command -v pacman >/dev/null 2>&1; then " +
                "pacman -Syu --noconfirm; " +
                "else echo \"Unsupported package manager\" >&2; exit 127; fi"
        val result = executeCommand(id = id, command = upgradeCmd, timeoutMillis = 600_000L)
        refreshWorkspaceStats(id)
        return result
    }

    /** 工作区在执行环境内的目录约定, 工作区不存在时返回 null; 不解密凭据也不建立连接 */
    suspend fun layout(id: String): WorkspaceLayout? = dao.getById(id)?.let { workspace ->
        if (workspace.isSsh()) {
            workspace.sshSettings()?.let { SshWorkspaceBackend.layoutOf(it.remoteDir) }
        } else {
            manager.layout
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        limit: Int? = null,
    ): List<WorkspaceFileEntry> {
        val workspace = dao.getById(id) ?: return emptyList()
        return readFromBackend(workspace) { it.listFiles(area, path, limit) }
    }

    /** 与 [listFiles] 相同, 但额外返回是否因上限截断以及截断前的总数。 */
    suspend fun listFilesDetailed(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        limit: Int? = null,
    ): WorkspaceListResult {
        val workspace = dao.getById(id) ?: return WorkspaceListResult(emptyList(), false, 0)
        return readFromBackend(workspace) { it.listFilesDetailed(area, path, limit) }
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return readFromBackend(workspace) { it.readText(path) }
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return withBackend(workspace) { it.writeText(path, text, overwrite) }
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceBackend.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return readFromBackend(workspace) { backend ->
            when (area) {
                WorkspaceStorageArea.FILES -> backend.readText(path)
                WorkspaceStorageArea.LINUX -> {
                    val size = backend.fileSize(area, path)
                    require(size <= MAX_PREVIEW_BYTES) {
                        "File too large, cannot preview (${size} bytes)"
                    }
                    ByteArrayOutputStream().use { out ->
                        backend.exportFile(area, path, out)
                        out.toString(Charsets.UTF_8.name())
                    }
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return withBackend(workspace) { it.importFile(area, destinationPath, fileName, inputStream) }
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return readFromBackend(workspace) { it.fileSize(area, path) }
    }

    /** 宿主机上可直接访问的文件; 没有本地落点的工作区返回 null */
    suspend fun resolveFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): File? {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return withBackend(workspace) { it.localFile(area, path) }
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        withBackend(workspace) { it.exportFile(area, path, outputStream) }
    }

    /** 按环境内绝对路径读取文件大小, 本地工作区支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun absoluteFileSize(
        id: String,
        path: String,
    ): Long {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return readFromBackend(workspace) { it.absoluteFileSize(path) }
    }

    /** 按环境内绝对路径导出文件内容, 本地工作区支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportAbsoluteFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        readFromBackend(workspace) { it.exportAbsoluteFile(path, outputStream) }
    }

    /**
     * 按环境内绝对路径写入 UTF-8 文本; 本地工作区在 Rootfs 内用 shell 写, 因此和命令一样支持取消。
     * [mode] 是已校验的八进制权限, 为空表示不改权限。
     */
    suspend fun writeAbsoluteText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
        mode: String? = null,
    ): WorkspaceFileEntry {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return readFromBackend(workspace) { it.writeAbsoluteText(path, text, overwrite, mode) }
    }

    /** 按环境内绝对路径创建目录, 已是目录时返回该条目 */
    suspend fun createAbsoluteFolder(
        id: String,
        path: String,
    ): WorkspaceFileEntry {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return withBackend(workspace) { it.createAbsoluteFolder(path) }
    }

    /** 按环境内绝对路径取宿主机上的文件; 不存在、不是文件或没有本地落点时返回 null */
    suspend fun resolveAbsoluteFile(
        id: String,
        path: String,
    ): File? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext null
        runCatching { backendOf(workspace).localAbsoluteFile(path) }
            .getOrNull()
            ?.takeIf { it.isFile }
    }

    /** 执行命令实际使用的 shell 路径; 远程工作区要先连接, 没有 Rootfs 的本地工作区返回 null */
    suspend fun readShellPath(id: String): String? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext null
        if (!workspace.isSsh() && !manager.hasRootfs(workspace.root)) return@withContext null
        runCatching { backendOf(workspace).shell() }.getOrNull()
    }

    /**
     * 读取执行环境的发行版信息：本机工作区读 Rootfs 目录，远程工作区连上主机读 `/etc/os-release`。
     * 读不到（无 rootfs、主机不可达、文件缺失）时返回 null。
     */
    suspend fun readDistroInfo(id: String): WorkspaceDistroInfo? {
        val workspace = dao.getById(id) ?: return null
        if (workspace.isSsh()) return readRemoteDistroInfo(workspace)
        if (!workspace.capabilities().rootfs) return null
        return withContext(Dispatchers.IO) {
            runCatching { manager.readDistroInfo(workspace.root) }.getOrNull()
        }
    }

    suspend fun readEnvironmentInfo(id: String): WorkspaceEnvironmentInfo? = readEnvironmentInfoImpl(id)

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        return withBackend(workspace) { it.deleteFile(area, path, recursive) }
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): WorkspaceFileEntry {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return withBackend(workspace) { it.moveFile(area, source, target, overwrite) }
    }

    suspend fun copyFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): WorkspaceFileEntry {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return withBackend(workspace) { it.copyFile(area, source, target, overwrite) }
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        targetId: String? = null,
        env: Map<String, String> = emptyMap(),
    ): WorkspaceCommandResult {
        // 多工作区：targetId 指定时在目标工作区执行（默认当前工作区）
        val executeId = targetId ?: id
        // shell 触碰手机存储的留痕（文件工具已由路径解析层覆盖；此处覆盖命令串）
        if (command.contains("/sdcard") || cwd.startsWith("/sdcard")) {
            AppLog.i("WorkspaceSdcard", "shell: cwd=$cwd cmd=$command")
        }
        val workspace = dao.getById(executeId) ?: error("Workspace not found: $executeId")
        // env 注入两条路：本地经 Rootfs 的 env -i 参数传入，远程经 exec 通道的
        // 外层 env 命令参数传入（见 SshWorkspaceBackend.executeCommand）；
        // 远程的值会出现在远端进程命令行里，调用方不要把高敏感凭据只当作普通 env 传。
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        // ⚠ backendOf 是 suspend（可能要解析 SSH 凭证），必须在 runInterruptible 之外先取好；
        //   后端方法本身都是阻塞调用，所以 lambda 里只放非 suspend 的调用。
        val sshBackend = if (workspace.isSsh()) backendOf(workspace) else null
        return runInterruptible(Dispatchers.IO) {
            if (sshBackend != null) {
                sshBackend.executeCommand(command, cwd, timeoutMillis, stdin, env)
            } else {
                manager.executeCommand(
                    workspace.root, command, cwd, timeoutMillis, stdin,
                    env = env,
                    shellCompatibilityMode = workspace.shellCompatibilityMode,
                )
            }
        }
    }

    /**
     * 打开 SSH 工作区的交互式 shell, 阻塞到连接和 shell 都建立好, 不能在主线程调用。
     * suspend：建连要解析凭证库（见 backendOf）。
     */
    suspend fun openSshShell(workspace: WorkspaceEntity, columns: Int, rows: Int): SshShell {
        val backend = backendOf(workspace) as? SshWorkspaceBackend
            ?: error("Not an SSH workspace: ${workspace.id}")
        return backend.openShell(columns, rows)
    }

    suspend fun readFolderTree(
        id: String,
        path: String,
        maxDepth: Int = 10,
    ): WorkspaceTreeResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        if (workspace.capabilities().localFiles) {
            return withContext(Dispatchers.IO) {
                manager.ensureWorkspace(workspace.root)
                manager.rootfsTree(workspace.root, path, maxDepth)
            }
        }
        // 远程工作区: 没有 Rootfs 路径映射, 在一次连接里逐层 ls 拼出树
        return readFromBackend(workspace) { backend ->
            (backend as? SshWorkspaceBackend)?.folderTree(path, maxDepth)
                ?: error("Folder tree is not supported for workspace ${workspace.id}")
        }
    }

    /**
     * 启动后台任务。
     *
     * 用 NonCancellable 而非 runInterruptible（与 executeCommand 相反）：这里的进程要在工具
     * 调用结束后继续跑，取消（例如回合预算到期）绝不能打断启动或丢弃刚拿到的 id——否则进程
     * 已经起来（端口已占），调用方却永远拿不到 id 去查询或停止它。
     */
    suspend fun startBackground(
        id: String,
        command: String,
        cwd: String = "",
        env: Map<String, String> = emptyMap(),
    ): BackgroundStatus {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        requireBackgroundShell(workspace, "background tasks")
        return withContext(NonCancellable + Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.startBackground(workspace.root, command, cwd, env)
        }
    }

    suspend fun backgroundStatus(id: String, taskId: String): BackgroundStatus? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext null
        requireBackgroundShell(workspace, "background tasks")
        manager.backgroundStatus(workspace.root, taskId)
    }

    suspend fun listBackground(id: String): List<BackgroundStatus> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        requireBackgroundShell(workspace, "background tasks")
        manager.listBackground(workspace.root)
    }

    suspend fun killBackground(id: String, taskId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        requireBackgroundShell(workspace, "background tasks")
        manager.killBackground(workspace.root, taskId)
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        sshPool.close(id)
        // 远程工作区在本机没有文件可删; 有本地落点的先删文件再删 DB，
        // 文件删除失败则不删 DB（UI 可重试），避免孤儿目录残留
        if (workspace.capabilities().localFiles) {
            val fileDeleted = withContext(Dispatchers.IO) {
                manager.deleteWorkspace(workspace.root)
            }
            if (!fileDeleted) return false
        }
        dao.deleteById(id)
        cleanupAssistantReferences(id)
        return true
    }

    internal val remoteEnvironments = ConcurrentHashMap<String, RemoteEnvironment>()

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024

        /** 发行版识别顺序: 远程主机上 /etc/os-release 可能不存在（如部分 Alpine 只有 /usr/lib） */
        internal val OS_RELEASE_CANDIDATES = listOf("/etc/os-release", "/usr/lib/os-release")
        internal const val MAX_OS_RELEASE_BYTES = 8L * 1024

        /** 远程主机连不上时，多久内不再为构建提示词重试 */
        internal const val REMOTE_ENV_RETRY_MS = 60_000L

        /** 缓存 TTL：超过则详情页进入时后台重采（结果落地后覆盖）。 */
        private const val STATS_TTL_MS = 24 * 60 * 60 * 1000L

        private data class CachedStats(
            val rootBytes: Long? = null,
            val packageCount: Int? = null,
            val kernel: String? = null,
            val at: Long = 0,
        )

        private fun parseCache(json: String): Map<String, CachedStats> = runCatching {
            JsonInstant.decodeFromString<Map<String, CachedStats>>(json)
        }.getOrDefault(emptyMap())
    }
}
