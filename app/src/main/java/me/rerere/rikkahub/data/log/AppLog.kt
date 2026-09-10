package me.rerere.rikkahub.data.log

import android.content.Context
import android.util.Log
import me.rerere.rikkahub.utils.LogRedactor
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 应用内自记录日志工具：替代 logcat 读取方案，将所有 AppLog.d/w/e/i 调用同步写入
 * App 内内存 buffer，供「应用层日志」页面直接读取、搜索、导出。release 版无需
 * READ_LOGS 权限即可查看 ChatService 等核心日志。
 *
 * 每个方法在调用 android.util.Log 的同时（保留原有 logcat 输出），同步将日志条目
 * 写入内存 buffer（上限 500 条，超过丢弃最旧）。开关持久化在 SharedPreferences
 * （key = "rikkahub.preferences" / "app_log_enabled"）。
 */
object AppLog {
    /** 内存缓存上限：超过后丢弃最旧的日志 */
    private const val MAX_APP_LOGS = 500

    /** 单条消息最大长度，防止超长消息撑爆内存 */
    private const val MAX_MESSAGE_LENGTH = 2000

    private const val PREFS_NAME = "rikkahub.preferences"
    private const val PREF_APP_LOG_ENABLED = "app_log_enabled"

    /** 一行日志：时间戳 / 级别 / tag / 消息 */
    data class Entry(
        val timestamp: Long,
        val level: Char,
        val tag: String,
        val message: String,
    )

    private val buffer = ArrayDeque<Entry>()

    @Volatile
    private var enabled = false

    // ---- 公开开关 API ----

    fun isEnabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_APP_LOG_ENABLED, false)

    fun setEnabled(
        context: Context,
        value: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_APP_LOG_ENABLED, value)
            .apply()
        enabled = value
    }

    /** 应用启动时调用：读取持久化开关并同步到内存 */
    fun startIfEnabled(context: Context) {
        enabled = isEnabled(context)
    }

    // ---- 日志写入方法（调用 android.util.Log + 同步写 buffer） ----

    fun d(
        tag: String,
        message: String,
    ) {
        Log.d(tag, message)
        append('D', tag, message)
    }

    fun w(
        tag: String,
        message: String,
    ) {
        Log.w(tag, message)
        append('W', tag, message)
    }

    fun w(
        tag: String,
        message: String,
        tr: Throwable,
    ) {
        Log.w(tag, message, tr)
        append('W', tag, "$message\n${Log.getStackTraceString(tr)}".take(MAX_MESSAGE_LENGTH))
    }

    fun e(
        tag: String,
        message: String,
    ) {
        Log.e(tag, message)
        append('E', tag, message)
    }

    fun e(
        tag: String,
        message: String,
        tr: Throwable,
    ) {
        Log.e(tag, message, tr)
        append('E', tag, "$message\n${Log.getStackTraceString(tr)}".take(MAX_MESSAGE_LENGTH))
    }

    fun i(
        tag: String,
        message: String,
    ) {
        Log.i(tag, message)
        append('I', tag, message)
    }

    // ---- Buffer 读取 / 清理 / 导出 ----

    /** 当前缓存的应用层日志快照（最新在前）。 */
    fun getLogs(): List<Entry> =
        synchronized(buffer) {
            buffer.reversed()
        }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
        }
    }

    /**
     * 按关键字过滤并导出为纯文本。
     *
     * 默认**脱敏**（`redact = true`）：导出/分享/复制意味着日志离开 App（外部编辑器、
     * 剪贴板、第三方聊天工具），必须过 [LogRedactor.maskText]，防止崩溃堆栈/错误消息里
     * 的 API key、连接串随日志外泄。屏幕内查看（[getLogs]）保持原文，不影响本机排查。
     *
     * @param keyword 关键字（null / 空白表示不过滤），大小写不敏感
     * @param redact 是否脱敏（默认 true）；仅在明确需要原文且不外发时传 false
     * @return 过滤后的日志文本（每行含时间、级别、TAG、消息）
     */
    fun exportText(
        keyword: String? = null,
        redact: Boolean = true,
    ): String {
        val filter = keyword?.trim().orEmpty().lowercase(Locale.getDefault())
        val raw =
            buildString {
                getLogs().forEach { entry ->
                    if (filter.isEmpty() ||
                        entry.tag.lowercase(Locale.getDefault()).contains(filter) ||
                        entry.message.lowercase(Locale.getDefault()).contains(filter)
                    ) {
                        append(formatLine(entry))
                        append('\n')
                    }
                }
            }
        return if (redact) LogRedactor.maskText(raw) else raw
    }

    // ---- 内部 ----

    private fun append(
        level: Char,
        tag: String,
        message: String,
    ) {
        if (!enabled) return
        val entry =
            Entry(
                timestamp = System.currentTimeMillis(),
                level = level,
                tag = tag.take(64),
                message = message.take(MAX_MESSAGE_LENGTH),
            )
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > MAX_APP_LOGS) {
                buffer.removeFirst()
            }
        }
        // 文件持久化（与内存开关一致；时间戳由 FileLogSink 统一加）
        FileLogSink.append(FileLogSink.KIND_APP, "${entry.level} ${entry.tag}: ${entry.message}")
    }

    private fun formatLine(entry: Entry): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(entry.timestamp)
        return "$time ${entry.level} ${entry.tag}: ${entry.message}"
    }
}
