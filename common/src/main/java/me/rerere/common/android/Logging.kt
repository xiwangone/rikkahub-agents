package me.rerere.common.android

import android.content.Context
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 文本日志与请求日志各自独立的内存上限：两桶互不挤占（此前共用 100 条，
 * 一次长会话的请求数就能把文本日志挤掉）。上限是硬顶，不无限增长。
 */
private const val MAX_RECENT_TEXT_LOGS = 500
private const val MAX_RECENT_REQUEST_LOGS = 500

@Serializable
sealed class LogEntry {
    abstract val id: Uuid
    abstract val timestamp: Long
    abstract val tag: String

    @Serializable
    data class TextLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val message: String
    ) : LogEntry()

    @Serializable
    data class RequestLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val url: String,
        val method: String,
        val requestHeaders: Map<String, String> = emptyMap(),
        val requestBody: String? = null,
        val responseCode: Int? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        val durationMs: Long? = null,
        val error: String? = null
    ) : LogEntry()
}

object Logging {
    private const val PREFS_NAME = "rikkahub.preferences"
    private const val PREF_REQUEST_LOGGING_ENABLED = "request_logging_enabled"

    /** 文本日志环形缓冲（最新在前，上限 [MAX_RECENT_TEXT_LOGS]）。 */
    private val recentTextLogs = arrayListOf<LogEntry.TextLog>()

    /** 请求日志环形缓冲（最新在前，上限 [MAX_RECENT_REQUEST_LOGS]）。 */
    private val recentRequestLogs = arrayListOf<LogEntry.RequestLog>()

    /**
     * 请求日志开关：默认关（请求体含全量 prompt，默认不开），但开关持久化——
     * 用户显式开过就一直开，重启不复位。值只在启动时读一次、缓存在内存，
     * 记日志时只读这个 volatile 变量，不再碰 SharedPreferences。
     */
    @Volatile
    private var requestLoggingEnabled = false

    /**
     * 文件持久化回调：由 :app 层在 Application 启动时注入（FileLogSink），
     * 把 entry 同步写入 filesDir/logs/ 下的日志文件。common 层不持有 Context，保持 null 安全。
     */
    @Volatile
    var persistSink: ((LogEntry) -> Unit)? = null

    // Modules below :app (e.g. :ai) have no BuildConfig of their own to gate verbose
    // android.util.Log calls with, the way :app already gates HttpLoggingInterceptor
    // behind BuildConfig.DEBUG. RikkaHubApp.onCreate() sets this once from
    // BuildConfig.DEBUG so provider code can gate full request/response body logging
    // (which otherwise carries prompts and tool results into logcat on every release
    // build too) without a per-module build.gradle change.
    @Volatile
    private var debugLoggingEnabled = false

    fun log(tag: String, message: String) {
        addLog(LogEntry.TextLog(tag = tag, message = message))
    }

    /**
     * @param force 为 true 时无视开关强制记一条（拦截器用：非 200 响应与传输失败
     *   必须留痕，即使请求日志开关是关的）。强制条目只含元数据、不含请求体。
     */
    fun logRequest(entry: LogEntry.RequestLog, force: Boolean = false) {
        if (!requestLoggingEnabled && !force) return
        addLog(entry)
    }

    fun isRequestLoggingEnabled(): Boolean = requestLoggingEnabled

    /**
     * 只改内存（不持久化）：测试与程序化调用用。UI 开关用带 Context 的重载，
     * 否则重启后开关会复位。
     */
    fun setRequestLoggingEnabled(enabled: Boolean) {
        requestLoggingEnabled = enabled
    }

    /** 改内存并持久化（供日志页开关调用；重启后保持上次的选择）。 */
    fun setRequestLoggingEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_REQUEST_LOGGING_ENABLED, enabled)
            .apply()
        requestLoggingEnabled = enabled
    }

    /** 应用启动时调用：把持久化的请求日志开关读一次、同步到内存。 */
    fun startIfEnabled(context: Context) {
        requestLoggingEnabled =
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_REQUEST_LOGGING_ENABLED, false)
    }

    fun isDebugLoggingEnabled(): Boolean = debugLoggingEnabled

    fun setDebugLoggingEnabled(enabled: Boolean) {
        debugLoggingEnabled = enabled
    }

    private fun addLog(entry: LogEntry) {
        // 分桶只多一次类型分支：各写各的环形缓冲，不多一次拷贝或遍历。
        when (entry) {
            is LogEntry.TextLog -> {
                synchronized(recentTextLogs) {
                    recentTextLogs.add(0, entry)
                    if (recentTextLogs.size > MAX_RECENT_TEXT_LOGS) {
                        recentTextLogs.removeLastOrNull()
                    }
                }
            }
            is LogEntry.RequestLog -> {
                synchronized(recentRequestLogs) {
                    recentRequestLogs.add(0, entry)
                    if (recentRequestLogs.size > MAX_RECENT_REQUEST_LOGS) {
                        recentRequestLogs.removeLastOrNull()
                    }
                }
            }
        }
        persistSink?.invoke(entry)
    }

    /** 两桶按时间倒序合并（最新在前）；读路径才做合并，写路径不受影响。 */
    fun getRecentLogs(): List<LogEntry> {
        val texts: List<LogEntry.TextLog>
        val requests: List<LogEntry.RequestLog>
        synchronized(recentTextLogs) {
            texts = recentTextLogs.toList()
        }
        synchronized(recentRequestLogs) {
            requests = recentRequestLogs.toList()
        }
        return (texts + requests).sortedByDescending { it.timestamp }
    }

    fun getTextLogs(): List<LogEntry.TextLog> {
        synchronized(recentTextLogs) {
            return recentTextLogs.toList()
        }
    }

    fun getRequestLogs(): List<LogEntry.RequestLog> {
        synchronized(recentRequestLogs) {
            return recentRequestLogs.toList()
        }
    }

    fun clear() {
        synchronized(recentTextLogs) {
            recentTextLogs.clear()
        }
        synchronized(recentRequestLogs) {
            recentRequestLogs.clear()
        }
    }
}
