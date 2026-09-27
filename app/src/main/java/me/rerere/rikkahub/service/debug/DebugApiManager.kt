package me.rerere.rikkahub.service.debug

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    /** 服务是否在跑（供 UI 展示；enabled 是持久化意图，running 是运行事实） */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 已配置的口令（明文或 $引用）——UI 显示与保存反馈用 */
    val tokenFlow: Flow<String> = store.data.map { it[TOKEN].orEmpty() }

    init {
        // 开关驱动生命周期：enabled=true → 起服务；false → 停。进程重启后 DataStore
        // 重放 enabled 即完成恢复，无需显式 restore。
        scope.launch {
            store.data
                .map { it[ENABLED] ?: false }
                .distinctUntilChanged()
                .onEach { enabled ->
                    if (enabled) {
                        val token = store.data.first()[TOKEN].orEmpty()
                        // $$条目名引用在取值时解为真值；明文原样；空 → tokenProvider 返 null 自动生成
                        server.tokenProvider = { VaultProviderKeyRefs.resolveValue(token).ifBlank { null } }
                        // 启动失败（如端口被上次进程残留占用）→ 自动复位开关：防重启后反复 bind 失败循环
                        runCatching { server.start() }.onFailure { e ->
                            AppLog.e(TAG, "debug api start failed, auto-disabling", e)
                            store.edit { it[ENABLED] = false }
                            _running.value = false
                            return@onEach
                        }
                        _running.value = true
                    } else {
                        server.stop()
                        _running.value = false
                    }
                }
                .collect {}
        }
        // 口令热更：开启状态下改口令 → 重启服务使新口令立即生效
        scope.launch {
            store.data
                .map { it[TOKEN].orEmpty() }
                .distinctUntilChanged()
                .onEach { token ->
                    if (store.data.first()[ENABLED] ?: false) {
                        server.stop()
                        server.tokenProvider = { VaultProviderKeyRefs.resolveValue(token).ifBlank { null } }
                        server.start()
                        _running.value = true
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

        /** 调试接口端口（实验版固定；19998 为既有调试口） */
        const val PORT = 19999
        const val HOST_LOOPBACK = "127.0.0.1"

        private val ENABLED = booleanPreferencesKey("enabled")
        private val TOKEN = stringPreferencesKey("token")
    }
}
