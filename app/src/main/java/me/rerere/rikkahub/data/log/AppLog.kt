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
 *
 * 默认策略：开关默认开，只记 I/W/E。D 级诊断默认不进内存 buffer 与落盘
 * （仍进 logcat，见 [DEFAULT_MIN_LEVEL]），避免逐 token 的详细日志把缓冲刷满；
 * 需要 D 级进 buffer 时把 [minLevel] 降到 'D'（目前无 UI 入口，由调用方设置）。
 */
object AppLog {
    /**
     * 内存缓存上限：按级别分两档，避免逐 token 的详细日志把关键事件挤掉。
     *
     * - 重要级（I/W/E）：保底 [MAX_IMPORTANT_LOGS] 条。审批、取消、错误等排查主线都在这里；
     *   过去与详细日志共用 2000 条时，长会话/多工具调用下会被迅速冲掉
     *   （一份审批时间线几分钟后就已无法回溯，诊断窗口全部落在“抓不到”上）。
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

    /**
     * 去重归一化用的正则（预编译：append 是热路径，每次调用不重新编译）。
     * 剥掉重试计数，使同一错误的不同 attempt 在去重窗内合并为一条。
     */
    private val dedupRetryFractionPattern = Regex("\\(\\d+/\\d+\\)")
    private val dedupAttemptPattern = Regex("attempt \\d+", RegexOption.IGNORE_CASE)
    private val dedupTryPattern = Regex("try \\d+", RegexOption.IGNORE_CASE)
    private val dedupNumberedParenPattern = Regex("\\(\\d+\\)")

    /** 单条消息最大长度，防止超长消息撑爆内存 */
    private const val MAX_MESSAGE_LENGTH = 2000

    private const val PREFS_NAME = "rikkahub.preferences"
    private const val PREF_APP_LOG_ENABLED = "app_log_enabled"

    /** 默认进 buffer 的最低级别：'I'，即 D 级诊断默认只进 logcat、不进 buffer/落盘。 */
    private const val DEFAULT_MIN_LEVEL = 'I'

    /** 最低级别的持久化 key（与开关同一份 SharedPreferences）。 */
    private const val PREF_MIN_LEVEL = "app_log_min_level"

    /** 详细级（D）原文是否落盘（默认关；排查 provider/思考流时临时开）。 */
    private const val PREF_VERBOSE_FILE = "app_log_verbose_file"

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

    /** 总开关：默认开（用户显式关过才会持久化为关）。 */
    @Volatile
    private var enabled = true

    /** 进 buffer 的最低级别（默认 'I'）；低于它的条目只进 logcat。 */
    @Volatile
    private var minLevel: Char = DEFAULT_MIN_LEVEL

    /** 详细级原文落盘开关（与 [enabled] 独立：主日志可开、原文单独控制）。 */
    @Volatile
    private var verboseFileEnabled = false

    // ---- 公开开关 API ----

    fun isEnabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_APP_LOG_ENABLED, true)

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

    /** 应用启动时调用：读取持久化开关与最低级别并同步到内存 */
    fun startIfEnabled(context: Context) {
        enabled = isEnabled(context)
        verboseFileEnabled = isVerboseFileEnabled(context)
        minLevel = getMinLevel(context)
    }

    /** 持久化的最低级别（默认 'I'）；非法值回落到默认。 */
    fun getMinLevel(context: Context): Char =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_MIN_LEVEL, DEFAULT_MIN_LEVEL.toString())
            ?.firstOrNull()
            ?.takeIf { it in "DIWE" }
            ?: DEFAULT_MIN_LEVEL

    /** 设置最低级别并持久化（非法值回落到默认 'I'）。 */
    fun setMinLevel(
        context: Context,
        level: Char,
    ) {
        val normalized = if (level in "DIWE") level else DEFAULT_MIN_LEVEL
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_MIN_LEVEL, normalized.toString())
            .apply()
        minLevel = normalized
    }

    fun isVerboseFileEnabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_VERBOSE_FILE, false)

    fun setVerboseFileEnabled(
        context: Context,
        value: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_VERBOSE_FILE, value)
            .apply()
        verboseFileEnabled = value
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
        if (!shouldBuffer('D')) return
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
        if (!shouldBuffer('W')) return
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
        if (!shouldBuffer('E')) return
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

    /**
     * 级别门控：该级别是否进内存 buffer 与落盘。logcat 不受此门控，各公开方法
     * 总是先调 `Log.*` 再判断，所以 D 级被过滤时仍能在 logcat 里看到。
     */
    private fun shouldBuffer(level: Char): Boolean =
        enabled && levelRank(level) >= levelRank(minLevel)

    /** 级别高低：D < I < W < E（注意 Char 本身的字典序不是这个顺序）。 */
    private fun levelRank(level: Char): Int =
        when (level) {
            'D' -> 0
            'I' -> 1
            'W' -> 2
            'E' -> 3
            else -> 1
        }

    private fun append(
        level: Char,
        tag: String,
        message: String,
    ) {
        // 级别门控放最前面：低于最低级别的条目在任何格式化、对象分配、buffer 操作、
        // 落盘之前丢弃。D 级高频调用被过滤时只有两次 volatile 读 + 两次分支判断，
        // 几乎零成本；不要为了判断级别先拼字符串或建集合。
        if (!shouldBuffer(level)) return
        val safeTag = tag.take(64)
        val safeMessage = message.take(MAX_MESSAGE_LENGTH)
        val important = level != 'D'
        val buffer = if (important) importantBuffer else verboseBuffer
        val cap = if (important) MAX_IMPORTANT_LOGS else MAX_VERBOSE_LOGS
        val now = System.currentTimeMillis()
        // 时间桶参与 key：同一窗口内合并计数，跨窗口生成新条目（时间线不丢）。
        // key 里的消息先归一化（剥掉重试计数）：同一错误的不同 attempt 在窗内
        // 合并为一条；buffer 里存的仍是原始消息，展示与导出不受影响。
        val key = "$level|$safeTag|${now / DEDUP_WINDOW_MS}|${normalizeDedupMessage(safeMessage)}"
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
        // 详细级（D）：默认只留内存与 logcat，不落盘 —— 逐 token 的原始流写文件等于每块一次 IO，
        // 且会把 5×2MB / 7 天的摘要日志刷满，真出问题时反而翻不到重点。
        // 但排查 provider/思考流（如「thinking 正文丢」）必须看 SSE 原文，
        // 所以给出独立开关 + 独立类别（sse，1MB×2 份且只留 1 天），开关默认关。
        if (!important) {
            if (verboseFileEnabled) {
                FileLogSink.append(FileLogSink.KIND_SSE, "$level $safeTag: $safeMessage")
            }
            return
        }
        // 文件持久化（与内存开关一致；时间戳由 FileLogSink 统一加）
        FileLogSink.append(FileLogSink.KIND_APP, "$level $safeTag: $safeMessage")
    }

    /**
     * 去重 key 用的消息归一化：剥掉重试计数，使同一错误的不同 attempt 在
     * [DEDUP_WINDOW_MS] 内合并为一条（repeat 照常累加）。
     *
     * 只作用于 key：buffer 里存的仍是原始消息，展示与导出不受影响。
     * 注意 `try \d+` 也会匹配 `retry 2` 里的 `try 2`（→ `retry N`），
     * 归一化结果一致，无副作用。
     */
    private fun normalizeDedupMessage(message: String): String {
        var normalized = dedupRetryFractionPattern.replace(message, "(n/m)")
        normalized = dedupAttemptPattern.replace(normalized, "attempt N")
        normalized = dedupTryPattern.replace(normalized, "try N")
        normalized = dedupNumberedParenPattern.replace(normalized, "(#)")
        return normalized
    }

    private fun formatLine(entry: Entry): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(entry.timestamp)
        val repeatSuffix = if (entry.repeat > 1) " (×${entry.repeat})" else ""
        return "$time ${entry.level} ${entry.tag}: ${entry.message}$repeatSuffix"
    }
}
