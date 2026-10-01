package me.rerere.rikkahub.service.debug

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.vault.VaultProviderKeyRefs
import me.rerere.rikkahub.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

private val Context.debugApiDataStore by preferencesDataStore(name = "debug_api_prefs")

/** 调试接口启动受阻的原因：UI 按此取本地化文案（日志留英文原文供排障）。 */
enum class DebugApiStartFailure {
    /** 口令引用了解析不到的凭证条目（名字写错或条目已删）：拒绝启动，防“开关开着却永远连不上” */
    TOKEN_REF_UNRESOLVED,

    /** 首选端口与整个避让区间都被占 */
    PORT_UNAVAILABLE,
}

/**
 * 监听档位 → 绑定地址与来源白名单。档位语义（谁能连）：
 * loopback 仅本机 / lan 局域网私网段预设 / custom 自定义 CIDR / all 全部（仅 token 门禁）。
 * "::" 为双栈绑定：IPv6 + IPv4-mapped（Linux 默认 bindv6only=0）。
 */
public data class ListenConfig(val mode: String, val host: String, val cidrs: String) {
    companion object {
        const val MODE_LOOPBACK = "loopback"
        const val MODE_LAN = "lan"
        const val MODE_CUSTOM = "custom"
        const val MODE_ALL = "all"

        private const val LAN_PRESET =
            "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fe80::/10,fc00::/7"

        fun resolve(mode: String, custom: String): ListenConfig = when (mode) {
            MODE_LAN -> ListenConfig(MODE_LAN, "::", LAN_PRESET)
            MODE_CUSTOM -> ListenConfig(MODE_CUSTOM, "::", custom.trim())
            MODE_ALL -> ListenConfig(MODE_ALL, "::", "")
            else -> ListenConfig(MODE_LOOPBACK, DebugApiManager.HOST_LOOPBACK, "")
        }
    }
}

/**
 * 调试接口管理器：Flow 驱动 start/stop（enabled/口令/监听档位变更即热重启），App 启动恢复免费获得。
 */
class DebugApiManager(context: Context) {

    private val store = context.debugApiDataStore
    private val baseDir = File(context.filesDir, "debug-api")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = DebugApiServer(
        port = PORT,
        auditDir = baseDir,
        logsDir = File(context.filesDir, "logs"),
    )

    private val _running = MutableStateFlow(false)
    private val _port = MutableStateFlow(0)

    /** 服务是否在跑（供 UI 展示；enabled 是持久化意图，running 是运行事实） */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 实际监听端口（未运行为 0）；首选口被占并避让后以此为准，UI 按此展示 */
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _startError = MutableStateFlow<DebugApiStartFailure?>(null)

    /** 启动受阻原因（null = 正常）；UI 据此解释“为什么开关是开的却连不上” */
    val startError: StateFlow<DebugApiStartFailure?> = _startError.asStateFlow()

    /** 已配置的口令（明文或 $引用）——UI 显示与保存反馈用 */
    val tokenFlow: Flow<String> = store.data.map { it[TOKEN].orEmpty() }

    /** 当前监听档位与白名单（UI 展示/写入用） */
    val listenConfig: Flow<ListenConfig> = store.data.map {
        ListenConfig.resolve(it[LISTEN_MODE] ?: ListenConfig.MODE_LOOPBACK, it[CUSTOM_CIDRS].orEmpty())
    }

    suspend fun setListenMode(mode: String) {
        store.edit { it[LISTEN_MODE] = mode }
    }

    /** 自定义网段（CIDR，逗号分隔；仅自定义档生效） */
    suspend fun setCustomCidrs(cidrs: String) {
        store.edit { it[CUSTOM_CIDRS] = cidrs.trim() }
    }

    init {
        // exec 端点：经 Shizuku 以 shell uid 运行（root 由调用方显式 su -c）；权限随 Shizuku 启动方式
        server.commandRunner = { command, timeoutMs ->
            kotlinx.coroutines.runBlocking { ShizukuManager.exec(context, command, timeoutMs) }
        }
        // 单一驱动协程：enabled/口令/监听档位合并成一条「期望配置」流，任一变更走同一条路径热重启。
        // 不用两个并发 collector：冷启动两者都会 start()，而 start() 的 engine 判空与赋值不原子，
        // 两个引擎会抢同一端口（引擎协程 BindException 崩进程）。
        scope.launch {
            combine(
                store.data.map { it[ENABLED] ?: false },
                store.data.map { it[TOKEN].orEmpty() },
                store.data.map {
                    ListenConfig.resolve(
                        it[LISTEN_MODE] ?: ListenConfig.MODE_LOOPBACK,
                        it[CUSTOM_CIDRS].orEmpty(),
                    )
                },
            ) { enabled, token, listen -> Triple(enabled, token, listen) }
                .distinctUntilChanged()
                .onEach { (enabled, token, listen) ->
                    if (!enabled) {
                        runCatching { server.stop() }
                        _running.value = false
                        _port.value = 0
                        _startError.value = null
                        return@onEach
                    }
                    // 口令引用解析不到时拒绝启动，而不是拿 "$$名字" 字面量当口令——
                    // 后者会永久连不上且无从发现。
                    if (token.startsWith(VaultProviderKeyRefs.PREFIX) &&
                        VaultProviderKeyRefs.resolveOrNull(token) == null
                    ) {
                        AppLog.w(TAG, "debug api token ref unresolved, refusing to start")
                        runCatching { server.stop() }
                        _running.value = false
                        _port.value = 0
                        _startError.value = DebugApiStartFailure.TOKEN_REF_UNRESOLVED
                        return@onEach
                    }
                    _startError.value = null
                    // $$条目名在取值时解为真值；明文原样；空 → tokenProvider 返 null 自动生成
                    server.tokenProvider = { VaultProviderKeyRefs.resolveOrNull(token)?.ifBlank { null } }
                    // 监听档位与白名单：loopback 仅本机；其余档 "::" 双栈（IPv6 + IPv4-mapped）
                    server.listenHost = listen.host
                    server.allowedNetworks = listen.cidrs
                    val preferred = store.data.first()[LAST_PORT] ?: PORT
                    runCatching { server.stop() }
                    runCatching { server.start(preferredPort = preferred) }
                        .onSuccess { bound ->
                            _running.value = true
                            _port.value = bound
                            // 避让过就记住，下次优先回到同一个口，保持外部连接预期稳定
                            if (bound != preferred) store.edit { it[LAST_PORT] = bound }
                        }
                        .onFailure { e ->
                            // 首选口与避让区间全被占 → 复位开关：防重启后反复 bind 失败循环
                            AppLog.e(TAG, "debug api start failed, auto-disabling", e)
                            runCatching { server.stop() }
                            _running.value = false
                            _port.value = 0
                            _startError.value = DebugApiStartFailure.PORT_UNAVAILABLE
                            store.edit { it[ENABLED] = false }
                        }
                }
                .collect {}
        }
    }

    /** 当前会话 token（未运行返回 null）；供 UI 展示 */
    fun currentToken(): String? = server.currentToken()

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    /** 空 = 每次启动自动生成（仅内存，重启换新） */
    suspend fun setToken(token: String) {
        store.edit { it[TOKEN] = token.trim() }
    }

    companion object {
        private const val TAG = "DebugApiManager"

        /**
         * 调试接口首选端口（避让间隔需保持在临时端口段之下）。被占时由 [DebugApiServer.start] 先重试等待、
         * 再向后避让；避让区间须保持在临时端口段（32768-60999）之下，
         * 否则会与出站连接的源端口相撞。
         */
        const val PORT = 19999
        const val HOST_LOOPBACK = "127.0.0.1"

        private val ENABLED = booleanPreferencesKey("enabled")
        private val TOKEN = stringPreferencesKey("token")
        private val LAST_PORT = intPreferencesKey("last_port")
        private val LISTEN_MODE = stringPreferencesKey("listen_mode")
        private val CUSTOM_CIDRS = stringPreferencesKey("custom_cidrs")
    }
}
