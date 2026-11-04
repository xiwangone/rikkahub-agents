package me.rerere.rikkahub.data.log

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 文件日志落盘器：把日志/事件写入 filesDir/logs/<kind>-YYYYMMDD.log。
 *
 * - 用途：请求日志、应用日志、文本日志、生命周期事件统一持久化，进程被杀/重启后仍可读。
 * - 轮转：单文件超过 [MAX_FILE_SIZE] 时滚动 .1/.2（保留 [MAX_FILES] 份）；启动时清理 7 天前的文件。
 * - 线程：所有写入走单线程 daemon executor，不阻塞调用方；[flush] 等队列排空（崩溃快照用）。
 * - 安全：调用方必须先脱敏再写入（请求日志已由 RequestLoggingInterceptor 脱敏；AppLog 由 LogRedactor 兜底）。
 */
object FileLogSink {
    const val KIND_APP = "app"
    const val KIND_REQ = "req"
    const val KIND_TEXT = "text"
    const val KIND_LIFECYCLE = "lifecycle"

    /**
     * 浏览器出口审计（`BrowserAudit`）：模型发起的浏览器动作 + WebView 实际发出的
     * 非 GET 请求。单独一个类别，便于 `browser-<日期>.log` 单独排查/导出。
     */
    const val KIND_BROWSER = "browser"

    /**
     * 详细级日志原文（SSE 逐块、含 reasoning_content 等）：默认不落盘，
     * 仅在「详细日志落盘」开关打开时写入。因为原文量极大（每块一条），
     * 单独一类 + 小配额 + 短保留，避免把摘要日志（[KIND_APP]）冲掉。
     */
    const val KIND_SSE = "sse"

    private const val MAX_FILE_SIZE = 2L * 1024 * 1024 // 2MB
    private const val MAX_FILES = 5
    private const val RETENTION_DAYS = 7L

    // sse 类单独小配额：1MB × 2 份、保留 1 天。原文用于「当次排查」，不需要长期留存。
    private const val SSE_MAX_FILE_SIZE = 1L * 1024 * 1024
    private const val SSE_MAX_FILES = 2
    private const val SSE_RETENTION_DAYS = 1L

    private fun maxFileSizeOf(kind: String): Long =
        if (kind == KIND_SSE) SSE_MAX_FILE_SIZE else MAX_FILE_SIZE

    private fun maxFilesOf(kind: String): Int = if (kind == KIND_SSE) SSE_MAX_FILES else MAX_FILES

    private fun retentionDaysOf(fileName: String): Long =
        if (fileName.startsWith("$KIND_SSE-")) SSE_RETENTION_DAYS else RETENTION_DAYS
    private val fileDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    private val executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "file-log-sink").apply { isDaemon = true } }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logsDir: File? = null

    fun init(
        context: Context,
        header: String,
    ) {
        val dir = File(context.filesDir, "logs")
        runCatching { dir.mkdirs() }
        logsDir = dir
        executor.execute {
            cleanupOldFiles()
            val stamp = timeFormat.format(Date())
            appendLineSync("--", "== $stamp $header ==")
        }
    }

    fun append(
        kind: String,
        line: String,
    ) {
        if (logsDir == null) return
        // 落盘兜底脱敏：日志一旦写文件就长期留存（7 天 × 5 份轮转）。任何调用方疏漏
        // （例如把带密钥的 URL 原样打进来）都会变成磁盘上的明文，所以这里统一再过一遍脱敏器。
        val safe = me.rerere.rikkahub.utils.LogRedactor.maskText(line)
        executor.execute { appendLineSync(kind, safe) }
    }

    /** 等待队列排空（进程被杀/崩溃快照前调用，最多等 3s） */
    fun flush() {
        val done = CountDownLatch(1)
        executor.execute(done::countDown)
        runCatching { done.await(3, TimeUnit.SECONDS) }
    }

    /** 读某类别最近 [days] 天的末尾 [maxLines] 行；日期和轮转文件均按时间顺序合并。 */
    fun recentLines(
        kind: String,
        maxLines: Int,
        days: Int = 1,
    ): String {
        val dir = logsDir ?: return ""
        if (maxLines <= 0) return ""
        val dayCount = days.coerceIn(1, RETENTION_DAYS.toInt())
        val collected = mutableListOf<String>()
        val today = LocalDate.now()
        for (offset in dayCount - 1 downTo 0) {
            val date = today.minusDays(offset.toLong()).format(fileDateFormatter)
            val main = File(dir, "$kind-$date.log")
            // rotate() 向 .N 推送最老文件，.1 最近，主文件最新。
            val candidates = (maxFilesOf(kind) downTo 1).map { File(main.path + ".$it") } + main
            for (file in candidates) {
                if (file.exists()) {
                    collected += runCatching { file.readLines() }.getOrDefault(emptyList())
                }
            }
        }
        return collected.takeLast(maxLines).joinToString("\n").trim()
    }

    private fun appendLineSync(
        kind: String,
        line: String,
    ) {
        val dir = logsDir ?: return
        val file = File(dir, "${kind}-${dateFormat.format(Date())}.log")
        if (file.exists() && file.length() > maxFileSizeOf(kind)) {
            rotateFiles(file, maxFilesOf(kind))
        }
        try {
            FileOutputStream(file, true).use { fos ->
                val stamp = timeFormat.format(Date())
                fos.write("$stamp $line\n".toByteArray(Charsets.UTF_8))
            }
        } catch (_: Throwable) {
        }
    }

    private fun rotateFiles(
        file: File,
        maxFiles: Int,
    ) {
        for (i in maxFiles - 1 downTo 1) {
            val from = File(file.path + ".$i")
            val to = File(file.path + ".${i + 1}")
            if (from.exists()) runCatching { if (to.exists()) to.delete(); from.renameTo(to) }
        }
        val first = File(file.path + ".1")
        if (first.exists()) runCatching { first.delete() }
        runCatching { file.renameTo(first) }
    }

    private fun cleanupOldFiles() {
        val dir = logsDir ?: return
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { f ->
            // sse（详细原文）保留 1 天，其余 7 天
            val cutoff = now - retentionDaysOf(f.name) * 24 * 60 * 60 * 1000
            if (f.lastModified() < cutoff) runCatching { f.delete() }
        }
    }
}
