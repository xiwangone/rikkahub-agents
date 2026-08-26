package me.rerere.rikkahub.data.ai.mcp.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.data.ai.mcp.LocalMcpProfile
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.datastore.SettingsStore

data class LocalMcpServerState(
    val isRunning: Boolean = false,
    val toolCount: Int = 0,
    val port: Int = LocalMcpServerManager.DEFAULT_PORT,
    val error: String? = null,
)

/**
 * 本地 MCP Server 生命周期管理（Koin single）。
 *
 * 启动时把 [LocalTools] 的设备工具注册为 MCP 工具，绑定 127.0.0.1 供 Backend serve
 * 经 `[[plugins]] type="http"` 挂载。开关见 [SettingsStore.localMcpServerEnabled]。
 */
class LocalMcpServerManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val localTools: LocalTools,
) {

    companion object {
        private const val TAG = "LocalMcpServerManager"
        const val DEFAULT_PORT = 8788

        /**
         * 暴露给 Backend 的设备工具子集：覆盖设备操作、文件、媒体、通信与自动化基础。
         *
         * 有意排除需要交互界面/会话归属/高风险的组（ask_user、javascript_engine、
         * telegram_bot、cron_jobs、workflows、sub_agents、vault_tools、mcp_control、
         * shizuku、js_skills 等）——它们要么在无 UI 的 MCP 上下文无意义，要么与
         * 现有对话/后台会话冲突。所有 needsApproval 工具仍会走审批桥兜底。
         */
        val MCP_EXPOSED_TOOLS = listOf(
            LocalToolOption.TimeInfo,
            LocalToolOption.Clipboard,
            LocalToolOption.Battery,
            LocalToolOption.AudioInfo,
            LocalToolOption.TelephonyInfo,
            LocalToolOption.WifiInfo,
            LocalToolOption.Sensors,
            LocalToolOption.StorageInfo,
            LocalToolOption.Toast,
            LocalToolOption.Notification,
            LocalToolOption.Share,
            LocalToolOption.Torch,
            LocalToolOption.Vibrate,
            LocalToolOption.Brightness,
            LocalToolOption.Volume,
            LocalToolOption.MediaPlayer,
            LocalToolOption.MediaScanner,
            LocalToolOption.Download,
            LocalToolOption.Location,
            LocalToolOption.Contacts,
            LocalToolOption.CallLog,
            LocalToolOption.SmsInbox,
            LocalToolOption.CameraPhoto,
            LocalToolOption.MicRecorder,
            LocalToolOption.SpeechToText,
            LocalToolOption.Fingerprint,
            LocalToolOption.Ssh,
            LocalToolOption.ScreenAutomation,
            LocalToolOption.NotificationListener,
            LocalToolOption.Files,
            LocalToolOption.WebFetch,
            LocalToolOption.Browser,
            LocalToolOption.ExternalStorage,
            LocalToolOption.Archive,
            LocalToolOption.Nfc,
            LocalToolOption.AppLauncher,
            LocalToolOption.SystemIntents,
            LocalToolOption.Termux,
            LocalToolOption.SmsSend,
            LocalToolOption.Wallpaper,
            LocalToolOption.Keystore,
            LocalToolOption.KeyboardControl,
        )
    }

    private val registry = LocalToolRegistry(localTools)
    private val approvalBridge = LocalApprovalBridge(context)

    private val _state = MutableStateFlow(LocalMcpServerState())
    val state: StateFlow<LocalMcpServerState> = _state.asStateFlow()

    @Volatile
    private var server: LocalMcpServer? = null

    fun start(port: Int = DEFAULT_PORT, tools: List<LocalToolOption> = MCP_EXPOSED_TOOLS) {
        if (server != null) {
            Log.w(TAG, "MCP server already running")
            return
        }
        registry.sync(tools)
        val toolCount = registry.size()
        val candidate = LocalMcpServer(port, registry, approvalBridge)
        runCatching {
            candidate.start()
        }.onFailure { e ->
            Log.e(TAG, "Failed to start MCP server", e)
            _state.value = LocalMcpServerState(isRunning = false, toolCount = toolCount, port = port, error = e.message)
            return
        }
        server = candidate
        _state.value = LocalMcpServerState(isRunning = true, toolCount = toolCount, port = port, error = null)
        Log.i(TAG, "MCP server started on 127.0.0.1:$port with $toolCount tools")
    }

    fun start(profile: LocalMcpProfile) {
        start(profile.port, profile.allowedTools)
    }

    fun stop() {
        server?.stop()
        server = null
        _state.value = LocalMcpServerState()
        Log.i(TAG, "MCP server stopped")
    }

    fun restart(port: Int = _state.value.port) {
        stop()
        start(port)
    }
}