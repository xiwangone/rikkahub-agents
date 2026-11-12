package me.rerere.rikkahub.data.log

import org.koin.core.logger.Level
import org.koin.core.logger.Logger

private const val TAG = "Koin"

/**
 * Koin 日志桥：把 Koin 的启动/解析日志写进 AppLog（logcat + 应用内日志缓冲），
 * 替代默认的 androidLogger()。logcat 输出不丢：AppLog 的每个方法都先调
 * android.util.Log；进应用内缓冲则受 AppLog 自身开关门控（未开启时只走 logcat，
 * 与今天 androidLogger() 的行为一致）。
 *
 * 级别映射：DEBUG → d、INFO → i、WARNING → w、ERROR → e；NONE 丢弃。
 * 构造时传 Level.DEBUG，与 androidLogger() 的默认级别一致。
 */
class KoinAppLogLogger : Logger(Level.DEBUG) {
    override fun display(
        level: Level,
        msg: String,
    ) {
        when (level) {
            Level.DEBUG -> AppLog.d(TAG, msg)
            Level.INFO -> AppLog.i(TAG, msg)
            Level.WARNING -> AppLog.w(TAG, msg)
            Level.ERROR -> AppLog.e(TAG, msg)
            Level.NONE -> Unit
        }
    }
}
