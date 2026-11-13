package me.rerere.rikkahub.data.repository

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.local.HostAuthResolution
import me.rerere.rikkahub.data.ai.tools.local.RemotePlatform
import me.rerere.rikkahub.data.ai.tools.local.RemotePlatformStore
import me.rerere.rikkahub.data.ai.tools.local.SshHostProbeStore
import me.rerere.rikkahub.data.ai.tools.local.probePreferredWindows
import me.rerere.rikkahub.data.ai.tools.local.resolveHostAuthDetailed
import me.rerere.rikkahub.data.datastore.SshHostProbeMode
import me.rerere.rikkahub.data.datastore.SshPlatform
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
import me.rerere.rikkahub.data.vault.message
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceDistroInfo
import me.rerere.workspace.WorkspaceEnvironmentInfo
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceType
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshConnectionConfig
import me.rerere.workspace.ssh.SshHostProbe
import me.rerere.workspace.ssh.SshJumpHostConfig
import me.rerere.workspace.ssh.SshProbeResult
import me.rerere.workspace.ssh.SshWorkspaceBackend
import me.rerere.workspace.ssh.probeBudgetMs
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.rikkahub.data.log.AppLog

/**
 * 仓库层取会话授权检查：从 Koin 取应用 Context，构造按名过滤的检查函数。
 * 拿不到 Context 时返回 null → [CredentialResolver] 拒绝（fail-closed），
 * 避免 df5ad9c71 之后“无检查可用就放行”的旧语义（本函数是仓库层唯一能拿到
 * Context 的地方——仓库函数本身没有 Context 参数）。
 */
internal fun vaultSessionAuthCheck(): (suspend (String) -> Boolean)? =
    runCatching { getKoin().get<Context>() }.getOrNull()
        ?.let { ctx ->
            { name: String -> me.rerere.rikkahub.data.vault.VaultSessionManager(ctx).hasActiveAuthorization(name) }
        }

/** chroot 开启失败的原因：UI 按此取本地化文案（异常 message 保留英文供日志排障）。 */

    /** 用一条临时连接测试 SSH 配置; [id] 不为空时, 表单里留空的凭证条目沿用该工作区已保存的 */
internal suspend fun WorkspaceRepository.probeSshImpl(id: String?, draft: SshWorkspaceDraft): SshProbeResult {
        val previous = id?.let { dao.getById(it)?.sshSettings() }
        val config = withContext(Dispatchers.IO) { toConnectionConfig(draft.toSettings(previous), audit = false) }
        // 设置为关闭时不跑探针（hostProbe 保持 null，不写缓存）；其余两档都走显式入口跑一次。
        val includeHostProbe = settingsStore.settingsFlow.value.sshHostProbe != SshHostProbeMode.OFF
        val result = runInterruptible(Dispatchers.IO) { sshPool.probe(config, includeHostProbe) }
        // 测试连接每次重跑探针（手动刷新入口）：探针结果写回探针缓存（app SSH 与
        // 工作区共用同一份），平台结论同时写回平台缓存（探针优先于 banner）。
        // 探针失败只记 null，不影响本次测试连接结果；写缓存失败同样不影响。
        val hostProbe = result.hostProbe
        if (hostProbe != null) {
            val context = runCatching { getKoin().get<Context>() }.getOrNull()
            if (context != null) {
                try {
                    SshHostProbeStore(context).put(config.host, config.port, hostProbe, config.username)
                    RemotePlatformStore(context).put(config.host, config.port, hostProbe.isWindows)
                } catch (_: Throwable) {
                    // 写缓存失败不影响本次测试连接结果
                }
            }
        }
        return result
    }

    /**
     * 新建 ([id] 为 null) 或更新 SSH 工作区。落库的只有连接参数与凭证库条目名,
     * 建连时才解析出明文, 用完即弃。
     */
internal suspend fun WorkspaceRepository.saveSshImpl(id: String?, draft: SshWorkspaceDraft): WorkspaceEntity {
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
        // 跳板机名写错或凭证不可用时也在这里拦下, 否则工作区建好也连不上
        resolveJumpHostConfig(settings)
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

internal suspend fun WorkspaceRepository.verifyCredentials(settings: SshWorkspaceSettings) = withContext(Dispatchers.IO) {
        when (settings.authType) {
            SshAuthType.PASSWORD -> requireCredential(settings.passwordRef)
            SshAuthType.PRIVATE_KEY -> {
                requireCredential(settings.privateKeyRef)
                requireCredential(settings.passphraseRef)
            }
        }
    }

    /** 表单转成持久化配置: 凭据栏留空表示沿用 [previous] 里已存的条目名 */
internal fun SshWorkspaceDraft.toSettings(previous: SshWorkspaceSettings?): SshWorkspaceSettings {
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
            platform = platform,
            hostKey = hostKey,
            passwordRef = passwordRef,
            privateKeyRef = privateKeyRef,
            passphraseRef = passphraseRef,
            jumpHost = jumpHost.trim().ifEmpty { null },
            jumpHostKey = jumpHostKey,
        )
    }

    /**
     * 校验并归一表单里的凭证条目: 填了就必须是凭证库引用 (可省略 `$$` 前缀),
     * 这里只接受条目名, 明文密码/私钥一律拒绝落库。
     */
internal fun vaultRefInput(input: String, keep: () -> String?): String? {
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
internal suspend fun WorkspaceRepository.requireCredential(ref: String?) {
        val text = ref?.trim().orEmpty()
        if (text.isEmpty()) return
        // 工作区保存/探针时的凭证可用性检查也要过会话授权门（df5ad9c71 起 SSH_AUTH
        // 必须授权）：无 Context 可用时拒绝（fail-closed），不走“无检查就放行”的旧语义。
        val resolver = CredentialResolver(vaultRepository, vaultSessionAuthCheck())
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

internal suspend fun WorkspaceRepository.toConnectionConfig(settings: SshWorkspaceSettings, audit: Boolean): SshConnectionConfig {
        // 建连时的凭证解析也要过会话授权门（同 requireCredential）。
        val resolver = CredentialResolver(vaultRepository, vaultSessionAuthCheck())
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
            host = settings.host,
            port = settings.port,
            username = settings.username,
            authType = settings.authType,
            password = resolve(settings.passwordRef),
            privateKey = resolve(settings.privateKeyRef)?.ensureTrailingNewline(),
            passphrase = resolve(settings.passphraseRef),
            remoteDir = settings.remoteDir,
            hostKey = settings.hostKey,
            platform = resolveSshPlatform(settings.host, settings.port, settings.platform),
            jumpHost = resolveJumpHostConfig(settings),
        )
    }

    /**
     * 跳板机解析：工作区设置里的跳板主机名 → 已保存主机实体 + 凭证。
     * 单跳：跳板主机自身的 jumpHost 不再递归（与 SshHostsTool.resolveJump 同口径）。
     * 找不到主机或凭证不可用时抛 IOException，探测与保存都能直接看到原因。
     */
    internal suspend fun WorkspaceRepository.resolveJumpHostConfig(settings: SshWorkspaceSettings): SshJumpHostConfig? {
        val name = settings.jumpHost?.trim().orEmpty()
        if (name.isEmpty()) return null
        val repo = runCatching { getKoin().get<SshHostRepository>() }.getOrNull()
            ?: throw IOException("Jump host '$name' cannot be resolved: saved hosts are unavailable")
        val entity = repo.getByName(name)
            ?: throw IOException("Jump host '$name' not found (check saved host names)")
        // 跳板机凭证同样走会话授权门（df5ad9c71 起 SSH_AUTH 必须授权）；Koin 拿不到
        // Context 时传 null → 引用解析拒绝（fail-closed）。
        val jumpContext = runCatching { getKoin().get<Context>() }.getOrNull()
        val auth = when (val r = resolveHostAuthDetailed(entity, vaultRepository, jumpContext)) {
            is HostAuthResolution.Ready -> r.auth
            is HostAuthResolution.Unusable -> throw IOException("Jump host '$name' credential is unavailable: ${r.reason}")
        }
        return SshJumpHostConfig(
            host = entity.host,
            port = entity.port,
            username = entity.user,
            authType = if (!auth.privateKey.isNullOrBlank()) SshAuthType.PRIVATE_KEY else SshAuthType.PASSWORD,
            password = auth.password,
            privateKey = auth.privateKey?.ensureTrailingNewline(),
            passphrase = auth.passphrase,
            hostKey = settings.jumpHostKey,
        )
    }

    /**
     * 工作区连接的远端平台决议：工作区显式 > 已保存主机显式 > 探针缓存（优先于 banner 缓存，
     * 真实执行更可靠）> 全局默认，全部落空时为 AUTO。
     *
     * SshHostRepository 与 Context 不在 [WorkspaceRepository] 的构造参数里，
     * 沿用工具侧经 Koin 取依赖的做法，取不到时按“无该项输入”回退到下一级，不抛异常；
     * 全局默认直接读 [settingsStore]。AUTO 不是一个明确的平台选择，同样视为无显式值。
     */
    internal suspend fun WorkspaceRepository.resolveSshPlatform(
        host: String,
        port: Int,
        workspacePlatform: SshPlatform? = null,
        user: String? = null,
    ): String {
        val savedHost = runCatching { getKoin().get<SshHostRepository>() }
            .getOrNull()?.getByHost(host)
        val savedHostExplicit = savedHost?.let { SshPlatform.fromPreference(it.platform) }
            ?.takeUnless { it == SshPlatform.AUTO }
        val explicit = workspacePlatform?.takeUnless { it == SshPlatform.AUTO } ?: savedHostExplicit
        // 探针缓存的键含用户名：调用方传了用调用方的，否则用已保存主机的登录用户。
        val probeUser = user ?: savedHost?.user
        val cached = runCatching { getKoin().get<Context>() }
            .getOrNull()?.let { probePreferredWindows(it, host, port, probeUser) }
        val globalDefault = settingsStore.settingsFlow.value.defaultSshPlatform
        return when (RemotePlatform.resolveWindows(explicit, cached, globalDefault)) {
            true -> "WINDOWS"
            false -> "LINUX"
            null -> "AUTO"
        }
    }


    // 远程主机上读 os-release: 路径按候选顺序试, 单个候选失败继续下一个
internal suspend fun WorkspaceRepository.readRemoteDistroInfo(workspace: WorkspaceEntity): WorkspaceDistroInfo? =
        WorkspaceRepository.OS_RELEASE_CANDIDATES.firstNotNullOfOrNull { path ->
            readEnvironmentFile(workspace, path, WorkspaceRepository.MAX_OS_RELEASE_BYTES)
                ?.let { WorkspaceManager.parseDistroInfo(it) }
        }

    /** 读环境内的文本文件（大小超限、不存在或不可读时返回 null），用于发行版与提示词相关文件 */
internal suspend fun WorkspaceRepository.readEnvironmentFile(
        workspace: WorkspaceEntity,
        path: String,
        maxBytes: Long,
    ): String? =
        readFromBackend(workspace) { backend ->
            runCatching {
                val size = backend.absoluteFileSize(path)
                require(size <= maxBytes) { "$path exceeds $maxBytes bytes" }
                ByteArrayOutputStream().use { output ->
                    backend.exportAbsoluteFile(path, output)
                    output.toString(Charsets.UTF_8.name())
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                // 取环境信息失败不影响主流程，但必须留下痕迹，否则连接异常时无从排查
                AppLog.w("WorkspaceRepository", "readEnvironment failed", e)
            }.getOrNull()
        }

    /**
     * 执行环境信息：实际使用的 shell 与发行版名，供提示词描述工作区。
     * 读不到时各项为 null（本机工作区无 rootfs、远程主机连不上都会这样）。
     *
     * 远程工作区每读一次都要连主机，而提示词每一步请求都要构建，所以按工作区缓存：
     * 读到后一直沿用（连接配置变了才重读）；连不上时 [WorkspaceRepository.REMOTE_ENV_RETRY_MS] 内不再重试，
     * 免得每一步请求都等一次连接超时。
     */
internal suspend fun WorkspaceRepository.readEnvironmentInfoImpl(id: String): WorkspaceEnvironmentInfo? {
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
            if (System.currentTimeMillis() - cached.failedAt < WorkspaceRepository.REMOTE_ENV_RETRY_MS) return null
        }
        val shellPath = readShellPath(id)
        // shell 读不到说明主机没连上, 不再为发行版等第二次超时
        val info = WorkspaceEnvironmentInfo(
            shellPath = shellPath,
            distroName = shellPath?.let { readDistroInfo(id)?.prettyName },
            hostSummary = shellPath?.let { readHostProbeSummary(id) },
        )
        remoteEnvironments[id] = RemoteEnvironment(
            configKey = workspace.sshConfig,
            info = info.takeIf { it.shellPath != null },
            failedAt = System.currentTimeMillis(),
        )
        return info
    }

/**
 * AUTO 模式后台补探针的执行作用域（仓库级后台任务同款做法：进程内常驻，
 * 与 WorkspaceRepository.statsScope 一致；补探针是一次性任务，跑完即结束）。
 */
private val hostProbeSupplementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * AUTO 模式后台补探针的启动钩子（默认在 [hostProbeSupplementScope] 上跑；
 * 单测替换它可断言“启动即返回、不等探测完成”，而不必建真连接）。
 */
internal var launchHostProbeSupplement: (suspend () -> Unit) -> Unit =
    { block -> hostProbeSupplementScope.launch { block() } }

    /**
     * 主机探针摘要（供提示词）：缓存命中直接用；未命中时按设置项 `ssh_host_probe` 决定：
     * - AUTO：启动后台补一次（独立协程 + 自有会话 + 预算，不占调用方超时；
     *   见 [supplementHostProbeInBackground]），本次返回 null；
     * - MANUAL_ONLY / OFF：不跑探针，返回 null（只走显式入口：测试连接、工作区页按钮）。
     * 探针失败只写否定缓存（24h 内不再自动重探），不阻塞环境信息读取。
     */
    private suspend fun WorkspaceRepository.readHostProbeSummary(id: String): String? {
        val workspace = dao.getById(id) ?: return null
        val settings = workspace.sshSettings() ?: return null
        val context = runCatching { getKoin().get<Context>() }.getOrNull() ?: return null
        val store = SshHostProbeStore(context)
        store.get(settings.host, settings.port, settings.username)?.let { return it.summary() }
        if (settingsStore.settingsFlow.value.sshHostProbe != SshHostProbeMode.AUTO) return null
        if (store.isNegativeCached(settings.host, settings.port, settings.username)) return null
        supplementHostProbeInBackground(id, workspace)
        return null
    }

    /**
     * AUTO 模式的进工作区自动补一次：独立协程 + 自有会话（readFromBackend 每次建连），
     * 预算 min(15s, 剩余时间-1s)（无调用方 deadline 时 15s）；跑完写回探针缓存与平台缓存，
     * 成功后刷新环境信息缓存条目的 hostSummary（下次提示词构建即带上）。
     * 失败写否定缓存（24h 内不再自动重探），任何失败都不影响调用方。
     */
    private fun WorkspaceRepository.supplementHostProbeInBackground(id: String, workspace: WorkspaceEntity) {
        val settings = workspace.sshSettings() ?: return
        launchHostProbeSupplement {
            val context = runCatching { getKoin().get<Context>() }.getOrNull()
                ?: return@launchHostProbeSupplement
            val store = SshHostProbeStore(context)
            // 协程启动后重新查一次：测试连接等显式入口可能已在期间写好缓存。
            if (store.get(settings.host, settings.port, settings.username) != null) {
                return@launchHostProbeSupplement
            }
            if (store.isNegativeCached(settings.host, settings.port, settings.username)) {
                return@launchHostProbeSupplement
            }
            val platform = resolveSshPlatform(settings.host, settings.port, user = settings.username)
            val budget = probeBudgetMs(deadlineMs = null)
                ?: SshHostProbe.PROBE_TIMEOUT_MS
            val probe = try {
                readFromBackend(workspace) { backend ->
                    SshHostProbe.runProbe(
                        exec = { script, timeout -> backend.executeCommand(script, "", timeout, null, emptyMap()) },
                        platform = platform,
                        execWrapsWindows = platform == "WINDOWS",
                        timeoutMs = budget,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Throwable) {
                null
            }
            if (probe == null) {
                runCatching { store.putFailure(settings.host, settings.port, settings.username) }
                return@launchHostProbeSupplement
            }
            runCatching {
                store.put(settings.host, settings.port, probe, settings.username)
                RemotePlatformStore(context).put(settings.host, settings.port, probe.isWindows)
            }
            // 成功后刷新环境信息缓存条目的 hostSummary（下次提示词构建即带上）。
            val entry = remoteEnvironments[id]
            val info = entry?.info
            if (entry != null && info != null && info.hostSummary == null) {
                remoteEnvironments[id] = RemoteEnvironment(
                    entry.configKey,
                    info.copy(hostSummary = probe.summary()),
                    entry.failedAt,
                )
            }
        }
    }


internal suspend fun <T> WorkspaceRepository.readFromBackend(workspace: WorkspaceEntity, block: (WorkspaceBackend) -> T): T {
        // backendOf 是 suspend（可能需要解析 SSH 凭证），必须在协程体内取；
        // 后端方法本身都是阻塞调用，所以 block 不需要 suspend —— runInterruptible 的 lambda 是非 suspend 的。
        val backend = backendOf(workspace)
        return runCatching {
            runInterruptible(Dispatchers.IO) { block(backend) }
        }.getOrElse { e ->
            // 被中断的读取会抛出 JSch 包装过的 IO 异常, 已取消时统一按取消处理, 免得被当成连接失败
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

internal suspend fun <T> WorkspaceRepository.withBackend(workspace: WorkspaceEntity, block: suspend (WorkspaceBackend) -> T): T =
        withContext(Dispatchers.IO) { block(backendOf(workspace)) }

internal suspend fun WorkspaceRepository.backendOf(workspace: WorkspaceEntity): WorkspaceBackend =
        if (workspace.isSsh()) {
            val settings = workspace.sshSettings() ?: error("SSH workspace config is missing: ${workspace.id}")
            SshWorkspaceBackend(sshPool, workspace.id, toConnectionConfig(settings, audit = true))
        } else {
            manager.backend(workspace.root, workspace.shellCompatibilityMode)
        }

internal fun WorkspaceRepository.requireLocalRootfs(workspace: WorkspaceEntity, feature: String) {
        require(workspace.capabilities().rootfs) {
            "$feature is only available for local (Rootfs) workspaces: ${workspace.id}"
        }
    }

/** 后台任务的落点是本地进程表；远程工作区没有这个落点，跨区误调时直接给出 POSIX 脱离运行指引 */
internal fun WorkspaceRepository.requireBackgroundShell(workspace: WorkspaceEntity, feature: String) {
        require(workspace.capabilities().backgroundShell) {
            "$feature is only available for local workspaces: ${workspace.id} (${workspace.name} is a remote SSH workspace with no background-task tools). " +
                "Use workspace_shell with workspace=\"${workspace.name}\" and run detached in a single command: " +
                "cd <dir> && setsid nohup <cmd> >/tmp/task.log 2>&1 </dev/null & sleep 2; echo \"pid=\$!\"; tail -n 5 /tmp/task.log"
        }
    }

    /** 远程工作区执行环境的缓存条目；info 为 null 表示最近一次没连上主机 */
internal class RemoteEnvironment(val configKey: String?, val info: WorkspaceEnvironmentInfo?, val failedAt: Long)


internal suspend fun WorkspaceRepository.cleanupAssistantReferences(workspaceId: String) {
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

internal suspend fun WorkspaceRepository.restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

internal suspend fun WorkspaceRepository.updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

internal suspend fun WorkspaceRepository.updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

