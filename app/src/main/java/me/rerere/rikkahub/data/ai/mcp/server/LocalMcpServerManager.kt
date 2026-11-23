package me.rerere.rikkahub.data.ai.mcp.server

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.data.ai.mcp.LocalMcpProfile
import me.rerere.rikkahub.data.ai.tools.LocalToolCatalog
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.data.vault.CredentialPurpose
import me.rerere.rikkahub.data.vault.CredentialResolution
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.log.AppLog

data class LocalMcpServerState(
    val isRunning: Boolean = false,
    val toolCount: Int = 0,
    /** 当前实际暴露的工具选项（注册表上次同步的结果；能力编辑器读同一份状态展示）。 */
    val exposedTools: List<LocalToolOption> = emptyList(),
    val port: Int = LocalMcpServerManager.DEFAULT_PORT,
    val host: String = "127.0.0.1",
    val error: String? = null,
)

/**
 * 本地 MCP Server 生命周期管理（Koin single）。
 *
 * 启动时把 [LocalTools] 的设备工具注册为 MCP 工具，绑定 127.0.0.1 供 Backend serve
 * 经 `[[plugins]] type="http"` 挂载。开关见 [SettingsStore.localMcpServerEnabled]。
 * 暴露集恒钳制在 [LocalToolCatalog.mcpExposed] 内；运行中改 profile 时只改工具
 * 走热重同步，改端口/监听范围/令牌/白名单走重启。
 */
class LocalMcpServerManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val localTools: LocalTools,
    private val vaultRepository: CredentialVaultRepository,
    private val toolApprovalPreferences: ToolApprovalPreferences,
) {

    companion object {
        private const val TAG = "LocalMcpServerManager"
        const val DEFAULT_PORT = 8788

        /** 暴露集钳制：只保留安全子集内工具，顺序按入参。 */
        fun clampExposedTools(tools: List<LocalToolOption>): List<LocalToolOption> =
            tools.filter { it in LocalToolCatalog.mcpExposed }

        /**
         * 新旧 profile 是否需要重启服务才生效。
         *
         * 只有绑定参数变化才需重启（端口/监听范围/令牌引用/白名单）；仅工具集变化
         * 走热重同步。任一为 null（无 profile）视为绑定参数未知，一律重启。
         */
        fun requiresRestart(old: LocalMcpProfile?, new: LocalMcpProfile?): Boolean {
            if (old == null || new == null) return true
            return old.port != new.port ||
                !old.listenScope.equals(new.listenScope, ignoreCase = true) ||
                old.authTokenRef.trim() != new.authTokenRef.trim() ||
                old.allowedNetworks != new.allowedNetworks
        }
    }

    private val registry = LocalToolRegistry(localTools)
    // 入站审批与对话路径同一套决策（YOLO / 持久“总是允许”）；会话级 💬 没有
    // 会话上下文，不适用。见 LocalApprovalBridge。
    private val approvalBridge = LocalApprovalBridge(context) { toolName ->
        toolApprovalPreferences.currentYolo() ||
            toolApprovalPreferences.current().contains(toolName)
    }

    private val _state = MutableStateFlow(LocalMcpServerState())
    val state: StateFlow<LocalMcpServerState> = _state.asStateFlow()

    @Volatile
    private var server: LocalMcpServer? = null

    @Volatile
    private var currentProfile: LocalMcpProfile? = null

    fun start(
        port: Int = DEFAULT_PORT,
        tools: List<LocalToolOption> = LocalToolCatalog.mcpExposed,
        host: String = "127.0.0.1",
        authTokenProvider: (suspend () -> String?)? = null,
        allowedNetworks: String = "",
    ) {
        if (server != null) {
            AppLog.w(TAG, "MCP server already running")
            return
        }
        val exposed = clampExposedTools(tools)
        registry.sync(exposed)
        val toolCount = registry.size()
        val candidate =
            LocalMcpServer(
                port = port,
                registry = registry,
                approvalBridge = approvalBridge,
                host = host,
                authTokenProvider = authTokenProvider,
                allowedNetworks = allowedNetworks,
            )
        runCatching {
            candidate.start()
        }.onFailure { e ->
            AppLog.e(TAG, "Failed to start MCP server", e)
            _state.value = LocalMcpServerState(
                isRunning = false,
                toolCount = toolCount,
                exposedTools = registry.syncedOptions,
                port = port,
                host = host,
                error = e.message,
            )
            return
        }
        server = candidate
        _state.value = LocalMcpServerState(
            isRunning = true,
            toolCount = toolCount,
            exposedTools = registry.syncedOptions,
            port = port,
            host = host,
            error = null,
        )
        AppLog.i(TAG, "MCP server started on $host:$port with $toolCount tools")
    }

    fun start(profile: LocalMcpProfile) {
        currentProfile = profile
        // Fail safe: only an explicit "lan"/"any" opens the socket to all interfaces —
        // an unknown/corrupt scope value must not silently become network-exposed.
        val host =
            if (profile.listenScope.equals("lan", ignoreCase = true) ||
                profile.listenScope.equals("any", ignoreCase = true)
            ) {
                "0.0.0.0"
            } else {
                "127.0.0.1"
            }
        val tokenRef = profile.authTokenRef.trim()
        val tokenProvider: (suspend () -> String?)? =
            if (tokenRef.isEmpty()) {
                null
            } else {
                {
                    runCatching {
                        val r = CredentialResolver(vaultRepository)
                            .resolve(tokenRef, CredentialPurpose.MCP_AUTH, caller = "mcp")
                        (r as? CredentialResolution.Granted)?.value
                    }.getOrNull()
                }
            }
        start(
            port = profile.port,
            tools = profile.allowedTools,
            host = host,
            authTokenProvider = tokenProvider,
            allowedNetworks = profile.allowedNetworks,
        )
    }

    fun stop() {
        server?.stop()
        server = null
        _state.value = LocalMcpServerState()
        AppLog.i(TAG, "MCP server stopped")
    }

    /**
     * 运行中应用新 profile：仅工具集变化时热重同步（不重启服务），
     * 绑定参数变化或无 profile 时重启。服务未运行时只记下 profile，
     * 下次启动仍以设置里的活动 profile 为准。
     */
    fun applyProfile(profile: LocalMcpProfile?) {
        val prev = currentProfile
        currentProfile = profile
        if (server == null) return
        if (profile == null) {
            restart()
            return
        }
        if (requiresRestart(prev, profile)) {
            restart(profile)
        } else {
            resyncTools(profile.allowedTools)
        }
    }

    /**
     * 热重同步工具集：注册表按新工具集重建，运行中的 server 下一次
     * tools/list 与 tools/call 即见新集。端口/监听地址/令牌/白名单不变。
     */
    fun resyncTools(tools: List<LocalToolOption>) {
        if (server == null) return
        val exposed = clampExposedTools(tools)
        registry.sync(exposed)
        _state.value = _state.value.copy(toolCount = registry.size(), exposedTools = registry.syncedOptions)
        AppLog.i(TAG, "MCP tools resynced: ${registry.size()} tools")
    }

    fun restart(profile: LocalMcpProfile? = currentProfile) {
        stop()
        if (profile != null) {
            start(profile)
        } else {
            start()
        }
    }
}
