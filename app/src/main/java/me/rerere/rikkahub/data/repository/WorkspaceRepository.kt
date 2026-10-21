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
import me.rerere.rikkahub.data.model.SshWorkspaceSettings
import me.rerere.rikkahub.data.vault.CredentialPurpose
import me.rerere.rikkahub.data.vault.CredentialResolution
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.VAULT_REF_PREFIX
import me.rerere.rikkahub.data.vault.ensureTrailingNewline
import me.rerere.rikkahub.data.vault.isVaultReference
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import java.io.File
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.BackgroundStatus
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceTreeEntry
import me.rerere.workspace.WorkspaceTreeResult
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceDistroInfo
import me.rerere.workspace.WorkspaceEnvironmentInfo
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStats
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceType
import me.rerere.workspace.collectWorkspaceStats
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshConnectionConfig
import me.rerere.workspace.ssh.SshConnectionPool
import me.rerere.workspace.ssh.SshProbeResult
import me.rerere.workspace.ssh.SshWorkspaceBackend
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val mountSwitch: WorkspaceMountSwitch,
    private val sshPool: SshConnectionPool,
    private val vaultRepository: CredentialVaultRepository,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    /** 当前沙箱镜像配置（全局配置，应用时写入所有已安装 rootfs）。 */
    fun mirrorsFlow(): Flow<WorkspaceMirrors> =
        settingsStore.settingsFlow.map { s ->
            WorkspaceMirrors(
                apk = s.workspaceApkMirror,
                apt = s.workspaceAptMirror,
                pip = s.workspacePipMirror,
                npm = s.workspaceNpmMirror,
            )
        }

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
    fun sdcardEnabledFlow(): Flow<Boolean> =
        settingsStore.settingsFlow
            .map { it.workspaceSdcardEnabled }
            .onEach { mountSwitch.sdcardEnabled = it }

    /** 开关手机存储挂载：写设置 + 同步内存桥（对后续 shell 启动/路径解析即时生效）。 */
    suspend fun setSdcardAccess(enabled: Boolean) {
        settingsStore.update { current -> current.copy(workspaceSdcardEnabled = enabled) }
        mountSwitch.sdcardEnabled = enabled
    }

    /** chroot 后端开关（实验性）：设置流，订阅时同步内存桥。 */
    fun chrootEnabledFlow(): Flow<Boolean> =
        settingsStore.settingsFlow
            .map { it.workspaceChrootEnabled }
            .onEach { mountSwitch.chrootEnabled = it }

    /** 开关 chroot 后端：写设置 + 同步内存桥（对后续 shell 启动即时生效）。 */
    suspend fun setChrootEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        if (enabled && !isSuAccessible()) {
            throw ChrootEnableException(
                ChrootEnableFailure.ROOT_REQUIRED,
                message = "chroot requires root: su not accessible on this device. " +
                    "Grant this app root access in your root manager (e.g. Magisk) and retry.",
            )
        }
        if (enabled) {
            val workspace = dao.getAll().firstOrNull()
                ?: throw ChrootEnableException(
                    ChrootEnableFailure.NO_WORKSPACE,
                    message = "chroot probe: no workspace",
                )
            manager.probeChroot(workspace.root)?.let { result ->
                if (result.fail > 0) {
                    throw ChrootEnableException(
                        ChrootEnableFailure.PROBE_FAILED,
                        detail = result.raw.take(400),
                        message = "chroot probe failed (${result.fail} checks)",
                    )
                }
            }
        }
        if (!enabled) {
            // 关开关时卸载常驻挂载（不卸会留着 → 删工作区会 EBUSY）
            dao.getAll().forEach { ws ->
                manager.unmountChroot(ws.root)?.let { r ->
                    if (r.exitCode != 0) {
                        AppLog.w("Workspace", "chroot unmount on disable failed: ${r.stderr}")
                    }
                }
            }
        }
        settingsStore.update { current -> current.copy(workspaceChrootEnabled = enabled) }
        mountSwitch.chrootEnabled = enabled
    }

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
                .onFailure { AppLog.w("WorkspaceStats", "collect failed for $id", it) }
        }
    }

    /** 强制重采（详情页手动刷新用），无视 TTL。 */
    fun refreshWorkspaceStats(id: String) {
        statsScope.launch {
            runCatching { workspaceStats(id) }
                .onSuccess { stats ->
                    _statsById.value = _statsById.value + (id to stats)
                    persistStats(id, stats)
                }
                .onFailure { AppLog.w("WorkspaceStats", "refresh failed for $id", it) }
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
                AppLog.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                AppLog.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    /** 所有工作区（按创建顺序）。授权写沙箱 token 时取第一个（主工作区）。 */
    suspend fun getAll(): List<WorkspaceEntity> = dao.getAll()

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

    /** 用一条临时连接测试 SSH 配置; [id] 不为空时, 表单里留空的凭证条目沿用该工作区已保存的 */
    suspend fun probeSsh(id: String?, draft: SshWorkspaceDraft): SshProbeResult {
        val previous = id?.let { dao.getById(it)?.sshSettings() }
        val config = withContext(Dispatchers.IO) { draft.toSettings(previous).toConnectionConfig(audit = false) }
        return runInterruptible(Dispatchers.IO) { sshPool.probe(config) }
    }

    /**
     * 新建 ([id] 为 null) 或更新 SSH 工作区。落库的只有连接参数与凭证库条目名,
     * 建连时才解析出明文, 用完即弃。
     */
    suspend fun saveSsh(id: String?, draft: SshWorkspaceDraft): WorkspaceEntity {
        val existing = id?.let { dao.getById(it) ?: error("Workspace not found: $id") }
        require(existing == null || existing.isSsh()) { "Not an SSH workspace: $id" }
        val finalName = draft.name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        // 测试连接时远程目录可以留空（用登录主目录兜底），落库时不行：
        // 空目录会退化成 "/" 把整台机器的文件系统当工作区
        require(draft.remoteDir.isNotBlank()) { "Remote directory is required" }
        val settings = draft.toSettings(existing?.sshSettings())
        // 保存前确认引用的凭证条目确实在库且能解密, 免得建好工作区才在首次连接时发现取不到
        verifyCredentials(settings)
        val now = System.currentTimeMillis()
        val config = JsonInstant.encodeToString(settings)
        val workspace = existing?.copy(
            name = finalName,
            sshConfig = config,
            updatedAt = now,
        ) ?: Uuid.random().toString().let { newId ->
            WorkspaceEntity(
                id = newId,
                name = finalName,
                root = newId,
                // 远程工作区不需要安装 Rootfs, 视为 shell 已就绪
                shellStatus = WorkspaceShellStatus.READY.name,
                createdAt = now,
                updatedAt = now,
                type = WorkspaceType.SSH.name,
                sshConfig = config,
            )
        }
        dao.upsert(workspace)
        // 配置变了, 池里的旧连接 (含已缓存的远程目录与 shell 探测结果) 立即作废
        sshPool.close(workspace.id)
        return workspace
    }

    private suspend fun verifyCredentials(settings: SshWorkspaceSettings) = withContext(Dispatchers.IO) {
        when (settings.authType) {
            SshAuthType.PASSWORD -> settings.passwordRef.requireCredential()
            SshAuthType.PRIVATE_KEY -> {
                settings.privateKeyRef.requireCredential()
                settings.passphraseRef?.requireCredential()
            }
        }
    }

    /** 表单转成持久化配置: 凭据栏留空表示沿用 [previous] 里已存的条目名 */
    private fun SshWorkspaceDraft.toSettings(previous: SshWorkspaceSettings?): SshWorkspaceSettings {
        val remoteDir = remoteDir.trim().trimEnd('/').ifEmpty { "/" }
        require(remoteDir.startsWith("/")) { "Remote directory must be an absolute path" }
        require(host.isNotBlank()) { "Host is required" }
        require(username.isNotBlank()) { "Username is required" }
        require(port in 1..65535) { "Invalid port: $port" }
        val passwordRef = when (authType) {
            SshAuthType.PASSWORD -> vaultRefInput(passwordRef) { previous?.passwordRef }
            SshAuthType.PRIVATE_KEY -> null
        }
        val privateKeyRef = when (authType) {
            SshAuthType.PRIVATE_KEY -> vaultRefInput(privateKeyRef) { previous?.privateKeyRef }
            SshAuthType.PASSWORD -> null
        }
        val passphraseRef = when (authType) {
            SshAuthType.PRIVATE_KEY -> vaultRefInput(passphraseRef) { previous?.passphraseRef }
            SshAuthType.PASSWORD -> null
        }
        // 必填的凭证位空着（新建、或编辑时被清空）= 连不上又不至于建出一个没有凭据的工作区
        when (authType) {
            SshAuthType.PASSWORD -> require(passwordRef != null) { "Password credential is required" }
            SshAuthType.PRIVATE_KEY -> require(privateKeyRef != null) { "Private key credential is required" }
        }
        return SshWorkspaceSettings(
            host = host.trim(),
            port = port,
            username = username.trim(),
            authType = authType,
            remoteDir = remoteDir,
            hostKey = hostKey,
            passwordRef = passwordRef,
            privateKeyRef = privateKeyRef,
            passphraseRef = passphraseRef,
        )
    }

    /**
     * 校验并归一表单里的凭证条目: 填了就必须是凭证库引用 (可省略 `$$` 前缀),
     * 这里只接受条目名, 明文密码/私钥一律拒绝落库。
     */
    private fun vaultRefInput(input: String, keep: () -> String?): String? {
        val text = input.trim()
        if (text.isEmpty()) return keep()
        val ref = if (isVaultReference(text)) text else "$VAULT_REF_PREFIX$text"
        val name = ref.removePrefix(VAULT_REF_PREFIX)
        require(CredentialVaultRepository.validateCredentialName(name)) {
            "Credential name must be uppercase letters, digits and underscores: $name"
        }
        return ref
    }

    /**
     * 解析凭证条目引用的用途: 只允许 `$$条目名` 形式, 取回明文供建连使用。
     * 失败原因只带条目名, 不带内容。
     */
    private suspend fun String?.requireCredential() {
        val text = this?.trim().orEmpty()
        if (text.isEmpty()) return
        val resolver = CredentialResolver(vaultRepository)
        val name = text.removePrefix(VAULT_REF_PREFIX)
        var resolution = resolver.resolve(name, CredentialPurpose.SSH_AUTH, caller = "ssh-workspace", audit = true)
        if (resolution is CredentialResolution.Missing) {
            // 条目名可能存的是归一前的形态 (空格/小写), 再按现名试一次
            val normalized = CredentialVaultRepository.normalizeName(name)
            if (normalized != name) {
                resolution = resolver.resolve(normalized, CredentialPurpose.SSH_AUTH, caller = "ssh-workspace")
            }
        }
        if (resolution !is CredentialResolution.Granted) {
            throw IOException("SSH credential is unavailable: ${resolution.message}")
        }
    }

    private suspend fun SshWorkspaceSettings.toConnectionConfig(audit: Boolean): SshConnectionConfig {
        val resolver = CredentialResolver(vaultRepository)
        suspend fun resolve(ref: String?): String? {
            val text = ref?.trim().orEmpty()
            if (text.isEmpty()) return null
            require(isVaultReference(text)) { "SSH credential must be a vault reference: $text" }
            val name = text.removePrefix(VAULT_REF_PREFIX)
            val resolution = resolver.resolve(name, CredentialPurpose.SSH_AUTH, caller = "ssh-workspace", audit = audit)
            return (resolution as? CredentialResolution.Granted)?.value
                ?: throw IOException("SSH credential is unavailable: ${resolution.message}")
        }
        return SshConnectionConfig(
            host = host,
            port = port,
            username = username,
            authType = authType,
            password = resolve(passwordRef),
            // OPENSSH 私钥缺末尾换行会 Auth fail, 与 ssh_hosts 那条路径保持同一个容错
            privateKey = resolve(privateKeyRef)?.ensureTrailingNewline(),
            passphrase = resolve(passphraseRef),
            remoteDir = remoteDir,
            hostKey = hostKey,
        )
    }

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

    /** 从本地归档文件安装 rootfs（离线/内网分发场景，无需下载）。 */
    suspend fun installRootfsFromFile(
        id: String,
        archivePath: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        requireLocalRootfs(workspace, "Rootfs install from file")
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.installFromFile(workspace.root, File(archivePath), onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
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
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            AppLog.e(TAG, "installRootfsFromFile failed: workspace=${workspace.id}, archive=$archivePath", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
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
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            AppLog.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
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
                        "文件过大, 无法预览 (${size} bytes)"
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

    // 远程主机上读 os-release: 路径按候选顺序试, 单个候选失败继续下一个
    private suspend fun readRemoteDistroInfo(workspace: WorkspaceEntity): WorkspaceDistroInfo? =
        OS_RELEASE_CANDIDATES.firstNotNullOfOrNull { path ->
            readEnvironmentFile(workspace, path, MAX_OS_RELEASE_BYTES)
                ?.let { WorkspaceManager.parseDistroInfo(it) }
        }

    /** 读环境内的文本文件（大小超限、不存在或不可读时返回 null），用于发行版与提示词相关文件 */
    private suspend fun readEnvironmentFile(
        workspace: WorkspaceEntity,
        path: String,
        maxBytes: Long,
    ): String? =
        readFromBackend(workspace) { backend ->
            try {
                val size = backend.absoluteFileSize(path)
                require(size <= maxBytes) { "$path exceeds $maxBytes bytes" }
                ByteArrayOutputStream().use { output ->
                    backend.exportAbsoluteFile(path, output)
                    output.toString(Charsets.UTF_8.name())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 取环境信息失败不影响主流程，但必须留下痕迹，否则连接异常时无从排查
                AppLog.w("WorkspaceRepository", "readEnvironment failed", e)
                null
            }
        }

    /**
     * 执行环境信息：实际使用的 shell 与发行版名，供提示词描述工作区。
     * 读不到时各项为 null（本机工作区无 rootfs、远程主机连不上都会这样）。
     *
     * 远程工作区每读一次都要连主机，而提示词每一步请求都要构建，所以按工作区缓存：
     * 读到后一直沿用（连接配置变了才重读）；连不上时 [REMOTE_ENV_RETRY_MS] 内不再重试，
     * 免得每一步请求都等一次连接超时。
     */
    suspend fun readEnvironmentInfo(id: String): WorkspaceEnvironmentInfo? {
        val workspace = dao.getById(id) ?: return null
        if (!workspace.isSsh()) {
            val shellPath = readShellPath(id)
            return WorkspaceEnvironmentInfo(
                shellPath = shellPath,
                distroName = shellPath?.let { readDistroInfo(id)?.prettyName },
            )
        }
        val cached = remoteEnvironments[id]
        if (cached != null && cached.configKey == workspace.sshConfig) {
            cached.info?.let { return it }
            if (System.currentTimeMillis() - cached.failedAt < REMOTE_ENV_RETRY_MS) return null
        }
        val shellPath = readShellPath(id)
        // shell 读不到说明主机没连上, 不再为发行版等第二次超时
        val info = WorkspaceEnvironmentInfo(
            shellPath = shellPath,
            distroName = shellPath?.let { readDistroInfo(id)?.prettyName },
        )
        remoteEnvironments[id] = RemoteEnvironment(
            configKey = workspace.sshConfig,
            info = info.takeIf { it.shellPath != null },
            failedAt = System.currentTimeMillis(),
        )
        return info
    }

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
        // env 只有本地 Rootfs 支持: 远程主机上没有应用侧的挂载可注入
        require(env.isEmpty() || workspace.capabilities().rootfs) {
            "Environment variables are only supported for local workspaces"
        }
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            if (workspace.isSsh()) {
                backendOf(workspace).executeCommand(command, cwd, timeoutMillis, stdin)
            } else {
                manager.executeCommand(
                    workspace.root, command, cwd, timeoutMillis, stdin,
                    env = env,
                    shellCompatibilityMode = workspace.shellCompatibilityMode,
                )
            }
        }
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
        requireLocalRootfs(workspace, "background tasks")
        return withContext(NonCancellable + Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.startBackground(workspace.root, command, cwd, env)
        }
    }

    suspend fun backgroundStatus(id: String, taskId: String): BackgroundStatus? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext null
        if (!workspace.capabilities().rootfs) return@withContext null
        manager.backgroundStatus(workspace.root, taskId)
    }

    suspend fun listBackground(id: String): List<BackgroundStatus> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        if (!workspace.capabilities().rootfs) return@withContext emptyList()
        manager.listBackground(workspace.root)
    }

    suspend fun killBackground(id: String, taskId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        if (!workspace.capabilities().rootfs) return@withContext false
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

    private suspend fun <T> readFromBackend(workspace: WorkspaceEntity, block: suspend (WorkspaceBackend) -> T): T {
        return try {
            runInterruptible(Dispatchers.IO) { block(backendOf(workspace)) }
        } catch (e: Exception) {
            // 被中断的读取会抛出 JSch 包装过的 IO 异常, 已取消时统一按取消处理, 免得被当成连接失败
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    private suspend fun <T> withBackend(workspace: WorkspaceEntity, block: suspend (WorkspaceBackend) -> T): T =
        withContext(Dispatchers.IO) { block(backendOf(workspace)) }

    private suspend fun backendOf(workspace: WorkspaceEntity): WorkspaceBackend =
        if (workspace.isSsh()) {
            val settings = workspace.sshSettings() ?: error("SSH workspace config is missing: ${workspace.id}")
            SshWorkspaceBackend(sshPool, workspace.id, settings.toConnectionConfig(audit = true))
        } else {
            manager.backend(workspace.root, workspace.shellCompatibilityMode)
        }

    private fun requireLocalRootfs(workspace: WorkspaceEntity, feature: String) {
        require(workspace.capabilities().rootfs) {
            "$feature is only available for local (Rootfs) workspaces: ${workspace.id}"
        }
    }

    /** 远程工作区执行环境的缓存条目；info 为 null 表示最近一次没连上主机 */
    private class RemoteEnvironment(val configKey: String?, val info: WorkspaceEnvironmentInfo?, val failedAt: Long)

    private val remoteEnvironments = ConcurrentHashMap<String, RemoteEnvironment>()

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024

        /** 发行版识别顺序: 远程主机上 /etc/os-release 可能不存在（如部分 Alpine 只有 /usr/lib） */
        private val OS_RELEASE_CANDIDATES = listOf("/etc/os-release", "/usr/lib/os-release")
        private const val MAX_OS_RELEASE_BYTES = 8L * 1024

        /** 远程主机连不上时，多久内不再为构建提示词重试 */
        private const val REMOTE_ENV_RETRY_MS = 60_000L

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
