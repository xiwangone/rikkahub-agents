package me.rerere.rikkahub.service.debug

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.vault.VaultProviderKeyRefs
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

/**
 * AI 调试接口管理器（实验性）。形状对齐 [me.rerere.rikkahub.browser.BrowserPreferences]：
 * 单一 DataStore 数据源（enabled / 自定义口令），Flow 驱动服务生命周期——
 * 开关与口令落盘后由 init 里的 collector 自动 start/stop/热重启，App 启动恢复免费获得。
 *
 * 安全模型见 [DebugApiServer]：默认关、仅 127.0.0.1、token 会话、全请求审计。
 */
class DebugApiManager(context: Context) {

    private val store = context.debugApiDataStore
    private val baseDir = File(context.filesDir, "debug-api")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = DebugApiServer(
        port = PORT,
        host = HOST_LOOPBACK,
        auditDir = baseDir,
        logsDir = File(context.filesDir, "logs"),
    )

    private val _running = MutableStateFlow(false)
    private val _port = MutableStateFlow(0)

    /** 服务是否在跑（供 UI 展示；enabled 是持久化意图，running 是运行事实） */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 实际监听端口（未运行为 0）；首选口被占并避让后以此为准，UI 按此展示 */
    val port: StateFlow<Int> = _port.asStateFlow()

    /** 已配置的口令（明文或 $引用）——UI 显示与保存反馈用 */
    val tokenFlow: Flow<String> = store.data.map { it[TOKEN].orEmpty() }

    init {
        // 单一驱动协程：enabled 与口令合并成一条「期望配置」流，改口令与开开关走同一条路径。
        // 原先是两个并发 collector（enabled 起服务 + 口令热更重启），冷启动两者都会 start()，而
        // start() 的 engine 判空与赋值不原子 → 两个引擎抢同一端口 → 引擎协程 BindException 崩进程
        // （2026-09-28 真机崩溃快照实证）。
        scope.launch {
            combine(
                store.data.map { it[ENABLED] ?: false },
                store.data.map { it[TOKEN].orEmpty() },
            ) { enabled, token -> enabled to token }
                .distinctUntilChanged()
                .onEach { (enabled, token) ->
                    if (!enabled) {
                        runCatching { server.stop() }
                        _running.value = false
                        _port.value = 0
                        return@onEach
                    }
                    // $$条目名引用在取值时解为真值；明文原样；空 → tokenProvider 返 null 自动生成
                    server.tokenProvider = { VaultProviderKeyRefs.resolveValue(token).ifBlank { null } }
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
         * 调试接口首选端口（19998 为既有调试口）。被占时由 [DebugApiServer.start] 先重试等待、
         * 再向后避让；避让区间须保持在临时端口段（真机实测 32768-60999）之下，
         * 否则会与出站连接的源端口相撞。
         */
        const val PORT = 19999
        const val HOST_LOOPBACK = "127.0.0.1"

        private val ENABLED = booleanPreferencesKey("enabled")
        private val TOKEN = stringPreferencesKey("token")
        private val LAST_PORT = intPreferencesKey("last_port")
    }
}
