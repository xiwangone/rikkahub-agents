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
 * 写入内存 buffer。缓冲按级别分档（I/W/E 与 D 分开，见 [MAX_IMPORTANT_LOGS] /
 * [MAX_VERBOSE_LOGS]），并对相邻重复条目做合并计数，避免逐 token 的详细日志把关键
 * 事件冲掉。开关持久化在 SharedPreferences
 * （key = "rikkahub.preferences" / "app_log_enabled"）。
 */
object AppLog {
    /**
     * 内存缓存上限：按级别分两档，避免逐 token 的详细日志把关键事件挤掉。
     *
     * - 重要级（I/W/E）：保底 [MAX_IMPORTANT_LOGS] 条。审批、取消、错误等排查主线都在这里；
     *   过去与详细日志共用 2000 条时，长会话/多工具调用下会被迅速冲掉（实测一份 04:44 的
     *   审批时间线几分钟后已无法回溯，诊断窗口全部落在「抓不到」上）。
     * - 详细级（D）：provider 逐 token 的 `onEvent`、渲染/审批诊断等噪音大户，单列
     *   [MAX_VERBOSE_LOGS] 条，互不挤占。
     *
     * 另有「相邻去重」：同一队列尾部完全相同的条目只累加计数（展示为 `(×N)`），
     * 刷屏被压掉但信息量不丢。
     */
    private const val MAX_VERBOSE_LOGS = 2000
    private const val MAX_IMPORTANT_LOGS = 800

    /** 单条消息最大长度，防止超长消息撑爆内存 */
    private const val MAX_MESSAGE_LENGTH = 2000

    private const val PREFS_NAME = "rikkahub.preferences"
    private const val PREF_APP_LOG_ENABLED = "app_log_enabled"

    /** 一行日志：时间戳 / 级别 / tag / 消息（[repeat] 为相邻重复条目的合并计数） */
    data class Entry(
        val timestamp: Long,
        val level: Char,
        val tag: String,
        val message: String,
        /** 相邻重复次数：同一条连续重复时只累加计数，不新增条目 */
        val repeat: Int = 1,
        /** 末次出现时间（相邻去重时刷新）；排序与展示以它为准 */
        val lastTimestamp: Long = timestamp,
    )

    /** 保护两个缓冲区的锁 */
    private val lock = Any()

    /** 详细级（D）缓冲：逐 token 的 onEvent、渲染诊断等 */
    private val verboseBuffer = ArrayDeque<Entry>()

    /** 重要级（I/W/E）缓冲：审批、取消、错误等排查主线 */
    private val importantBuffer = ArrayDeque<Entry>()

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

    fun d(
        tag: String,
        message: String,
        tr: Throwable,
    ) {
        Log.d(tag, message, tr)
        append('D', tag, "$message\n${Log.getStackTraceString(tr)}".take(MAX_MESSAGE_LENGTH))
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

    /** 当前缓存的应用层日志快照（按末次出现时间倒序，最新在前）。 */
    fun getLogs(): List<Entry> =
        synchronized(lock) {
            (importantBuffer + verboseBuffer)
                .sortedBy { it.lastTimestamp }
                .reversed()
        }

    fun clear() {
        synchronized(lock) {
            importantBuffer.clear()
            verboseBuffer.clear()
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
        val safeTag = tag.take(64)
        val safeMessage = message.take(MAX_MESSAGE_LENGTH)
        val important = level != 'D'
        val queue = if (important) importantBuffer else verboseBuffer
        val now = System.currentTimeMillis()
        val isNewEntry =
            synchronized(lock) {
                // 相邻去重：与同队列尾部完全相同的条目只累加计数（不新增条目、不写文件），
                // 压掉刷屏同时保留「重复了多少次」这一信息。
                val last = queue.lastOrNull()
                if (last != null && last.level == level && last.tag == safeTag && last.message == safeMessage) {
                    queue.removeLast()
                    queue.addLast(last.copy(repeat = last.repeat + 1, lastTimestamp = now))
                    false
                } else {
                    queue.addLast(
                        Entry(
                            timestamp = now,
                            level = level,
                            tag = safeTag,
                            message = safeMessage,
                        ),
                    )
                    val cap = if (important) MAX_IMPORTANT_LOGS else MAX_VERBOSE_LOGS
                    while (queue.size > cap) {
                        queue.removeFirst()
                    }
                    true
                }
            }
        if (!isNewEntry) return
        // 文件持久化（与内存开关一致；时间戳由 FileLogSink 统一加）
        FileLogSink.append(FileLogSink.KIND_APP, "$level $safeTag: $safeMessage")
    }

    private fun formatLine(entry: Entry): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(entry.timestamp)
        val repeatSuffix = if (entry.repeat > 1) " (×${entry.repeat})" else ""
        return "$time ${entry.level} ${entry.tag}: ${entry.message}$repeatSuffix"
    }
}
