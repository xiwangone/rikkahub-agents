package me.rerere.rikkahub.utils

import android.content.Context
import android.util.Log
import androidx.core.content.edit

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val MAX_STACKTRACE_LENGTH = 8000

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            markCrashed(appContext, thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun hasCrashed(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)

    fun getStackTrace(context: Context): String? =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)

    fun clearCrashed(context: Context) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE) }
    }

    private fun markCrashed(
        context: Context,
        thread: Thread,
        throwable: Throwable,
    ) {
        val stackTrace =
            buildString {
                appendLine("Thread: ${thread.name}")
                appendLine(throwable.stackTraceToString())
            }.take(MAX_STACKTRACE_LENGTH)
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
            } // commit() 同步写入，确保崩溃前写完

        // 崩溃快照：堆栈 + 应用日志尾部 + 请求日志尾部，写 crash-latest.txt（尽力同步，崩溃时不再开线程）
        persistCrashSnapshot(context, thread, throwable)
    }

    private fun persistCrashSnapshot(
        context: Context,
        thread: Thread,
        throwable: Throwable,
    ) {
        val dir = context.getDir("crash", Context.MODE_PRIVATE)
        dir.mkdirs()
        val file = java.io.File(dir, "crash-latest.txt")
        val appLogTail = me.rerere.rikkahub.data.log.AppLog.getLogs().takeLast(50).joinToString("\n") {
            java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(it.timestamp) +
                " ${it.level} ${it.tag}: ${it.message}"
        }
        val requestTail = me.rerere.common.android.Logging.getRecentLogs().filterIsInstance<me.rerere.common.android.LogEntry.RequestLog>().take(20).joinToString("\n") {
            "[HTTP] ${it.method} ${it.url} code=${it.responseCode} dur=${it.durationMs}ms err=${it.error ?: "-"}"
        }
        val lifecycleTail = me.rerere.rikkahub.data.log.FileLogSink.recentLines(
            me.rerere.rikkahub.data.log.FileLogSink.KIND_LIFECYCLE,
            30,
        )
        val content =
            buildString {
                appendLine("=== Crash Snapshot ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} ===")
                appendLine("Thread: ${thread.name}")
                appendLine()
                appendLine(throwable.stackTraceToString())
                appendLine()
                appendLine("--- AppLog tail (50) ---")
                appendLine(me.rerere.rikkahub.utils.LogRedactor.maskText(appLogTail))
                appendLine()
                appendLine("--- Request tail (20) ---")
                appendLine(me.rerere.rikkahub.utils.LogRedactor.maskText(requestTail))
                appendLine()
                appendLine("--- Lifecycle tail (30) ---")
                appendLine(lifecycleTail)
            }
        runCatching {
            file.writeText(content, Charsets.UTF_8)
        }
        me.rerere.rikkahub.data.log.FileLogSink.flush()
    }
}
