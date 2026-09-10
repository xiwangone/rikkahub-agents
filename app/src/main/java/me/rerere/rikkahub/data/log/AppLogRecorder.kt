package me.rerere.rikkahub.data.log

import android.content.Context

/**
 * 应用层日志记录器（兼容层）：所有实现已迁移至 [AppLog] 应用内自记录 buffer。
 *
 * 旧版通过 Runtime.exec("logcat --pid=...") 读取系统日志，但 release App 无 READ_LOGS
 * 权限导致日志空白。现在改为 App 内同步写入 buffer 方案，不再依赖 logcat。
 *
 * 保留此类作为 API 兼容入口，所有方法委托给 [AppLog]。
 */
object AppLogRecorder {
    /** @see AppLog.Entry */
    typealias Entry = AppLog.Entry

    fun isEnabled(context: Context): Boolean = AppLog.isEnabled(context)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) = AppLog.setEnabled(context, enabled)

    fun startIfEnabled(context: Context) = AppLog.startIfEnabled(context)

    fun getLogs(): List<Entry> = AppLog.getLogs()

    fun clear() = AppLog.clear()

    /** @see AppLog.exportText —— 默认脱敏（导出即外发场景） */
    fun exportText(
        keyword: String? = null,
        redact: Boolean = true,
    ): String = AppLog.exportText(keyword, redact)
}
