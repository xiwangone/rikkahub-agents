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
     * 另有**去重（按内容 + 时间窗）**：同一「级别 + TAG + 内容」在 [DEDUP_WINDOW_MS] 内
     * 无论是否相邻，都只保留一条并累加计数（展示为 `(×N)`），且按其**首次出现**时间定位；
     * 跨窗口仍会生成新条目，因此时间线不丢（能看出「这个错误在 10 分钟前也出现过」）。
     */
    private const val MAX_VERBOSE_LOGS = 2000
    private const val MAX_IMPORTANT_LOGS = 800

    /**
     * 同内容合并窗口（毫秒）。取 3 秒是经验值：逐 token / 逐块刷屏的间隔远小于它（会被合并），
     * 而周期性任务这类「有意义的重复」间隔通常更大（不会被误并到一条里）。
     */
    private const val DEDUP_WINDOW_MS = 3000L

    /** 单条消息最大长度，防止超长消息撑爆内存 */
    private const val MAX_MESSAGE_LENGTH = 2000

    private const val PREFS_NAME = "rikkahub.preferences"
    private const val PREF_APP_LOG_ENABLED = "app_log_enabled"

    /** 一行日志：时间戳 / 级别 / tag / 消息（[repeat] 为去重合并计数） */
    data class Entry(
        val timestamp: Long,
        val level: Char,
        val tag: String,
        val message: String,
        /** 同一时间窗内的重复次数：重复只累加计数，不新增条目 */
        val repeat: Int = 1,
        /** 末次出现时间（窗口内重复时刷新）；排序以 [timestamp]（首现）为准 */
        val lastTimestamp: Long = timestamp,
    )

    /** 保护两个缓冲区的锁 */
    private val lock = Any()

    /**
     * 详细级（D）缓冲：逐 token 的 onEvent、渲染诊断等。
     * key = `级别|TAG|时间桶|内容` —— 同一时间桶内的相同日志合并计数。
     */
    private val verboseBuffer = LinkedHashMap<String, Entry>()

    /** 重要级（I/W/E）缓冲：审批、取消、错误等排查主线（key 规则同上） */
    private val importantBuffer = LinkedHashMap<String, Entry>()

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

    /** 当前缓存的应用层日志快照（按**首次出现**时间倒序，最新在前）。 */
    fun getLogs(): List<Entry> =
        synchronized(lock) {
            (importantBuffer.values + verboseBuffer.values)
                .sortedBy { it.timestamp }
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
        val buffer = if (important) importantBuffer else verboseBuffer
        val cap = if (important) MAX_IMPORTANT_LOGS else MAX_VERBOSE_LOGS
        val now = System.currentTimeMillis()
        // 时间桶参与 key：同一窗口内合并计数，跨窗口生成新条目（时间线不丢）
        val key = "$level|$safeTag|${now / DEDUP_WINDOW_MS}|$safeMessage"
        val isNewEntry =
            synchronized(lock) {
                val existing = buffer[key]
                if (existing != null) {
                    // 窗口内重复：只累加计数、刷新末次时间；不新增条目、不写文件
                    buffer[key] = existing.copy(repeat = existing.repeat + 1, lastTimestamp = now)
                    false
                } else {
                    buffer[key] =
                        Entry(
                            timestamp = now,
                            level = level,
                            tag = safeTag,
                            message = safeMessage,
                        )
                    // 容量淘汰：LinkedHashMap 迭代顺序 = 插入顺序，最旧的在最前
                    while (buffer.size > cap) {
                        val oldest = buffer.keys.firstOrNull() ?: break
                        buffer.remove(oldest)
                    }
                    true
                }
            }
        if (!isNewEntry) return
        // 详细级（D）只留内存与 logcat，**不落盘**：逐 token 的原始流、渲染详单这类内容
        // 写文件等于每块一次 IO，且会把 5×2MB / 7 天的文件日志刷满，真出问题时反而翻不到重点。
        // 重要级（I/W/E）照旧持久化。
        if (!important) return
        // 文件持久化（与内存开关一致；时间戳由 FileLogSink 统一加）
        FileLogSink.append(FileLogSink.KIND_APP, "$level $safeTag: $safeMessage")
    }

    private fun formatLine(entry: Entry): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(entry.timestamp)
        val repeatSuffix = if (entry.repeat > 1) " (×${entry.repeat})" else ""
        return "$time ${entry.level} ${entry.tag}: ${entry.message}$repeatSuffix"
    }
}
