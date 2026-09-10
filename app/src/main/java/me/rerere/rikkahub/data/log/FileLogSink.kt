package me.rerere.rikkahub.data.log

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private const val MAX_FILE_SIZE = 2L * 1024 * 1024 // 2MB
    private const val MAX_FILES = 5
    private const val RETENTION_DAYS = 7L

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
        executor.execute { appendLineSync(kind, line) }
    }

    /** 等待队列排空（进程被杀/崩溃快照前调用，最多等 3s） */
    fun flush() {
        val done = CountDownLatch(1)
        executor.execute(done::countDown)
        runCatching { done.await(3, TimeUnit.SECONDS) }
    }

    /** 读最近 N 行（某个类别），用于崩溃快照/页面展示 */
    fun recentLines(
        kind: String,
        maxLines: Int,
    ): String {
        val dir = logsDir ?: return ""
        val main = File(dir, "${kind}-${dateFormat.format(Date())}.log")
        val candidates = mutableListOf<File>()
        if (main.exists()) candidates.add(main)
        for (i in 1..MAX_FILES) {
            val f = File(main.path + ".$i")
            if (f.exists()) candidates.add(f)
        }
        val all = StringBuilder()
        var remaining = maxLines
        for (file in candidates) {
            if (remaining <= 0) break
            val lines =
                runCatching { file.readLines() }.getOrDefault(emptyList())
            val take = lines.size.coerceAtMost(remaining)
            all.append(lines.takeLast(take).joinToString("\n")).append('\n')
            remaining -= take
        }
        return all.toString().trim()
    }

    private fun appendLineSync(
        kind: String,
        line: String,
    ) {
        val dir = logsDir ?: return
        val file = File(dir, "${kind}-${dateFormat.format(Date())}.log")
        if (file.exists() && file.length() > MAX_FILE_SIZE) {
            rotate(file)
        }
        try {
            FileOutputStream(file, true).use { fos ->
                val stamp = timeFormat.format(Date())
                fos.write("$stamp $line\n".toByteArray(Charsets.UTF_8))
            }
        } catch (_: Throwable) {
        }
    }

    private fun rotate(file: File) {
        for (i in MAX_FILES - 1 downTo 1) {
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
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24 * 60 * 60 * 1000
        dir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) runCatching { f.delete() }
        }
    }
}