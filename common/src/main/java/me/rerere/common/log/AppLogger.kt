package me.rerere.common.log

import android.util.Log

/**
 * 跨模块日志桥。
 *
 * 背景：应用内日志（可在「设置 → 日志」查看/导出、并落盘轮转）实现在 app 模块，
 * 而 `ai` / `speech` / `search` 等模块位于依赖链的下游，无法反向引用 app 的日志实现，
 * 于是这些模块的日志过去只进 logcat —— 一旦出问题（例如模型调用、语音链路），
 * 事后在应用内日志里查不到任何线索。
 *
 * 用法：模块内直接调用 `AppLogger.i(tag, msg)`。
 * app 启动时注入 [Sink]（转发到应用内日志）；**未注入时退回 android.util.Log**，
 * 因此不依赖初始化顺序，也不会丢日志或崩溃。
 */
object AppLogger {
    interface Sink {
        fun d(
            tag: String,
            message: String,
        )

        fun i(
            tag: String,
            message: String,
        )

        fun w(
            tag: String,
            message: String,
        )

        fun w(
            tag: String,
            message: String,
            tr: Throwable?,
        )

        fun e(
            tag: String,
            message: String,
        )

        fun e(
            tag: String,
            message: String,
            tr: Throwable?,
        )
    }

    @Volatile
    private var sink: Sink? = null

    /** 由 app 模块在启动时调用一次。 */
    fun install(sink: Sink) {
        this.sink = sink
    }

    fun d(
        tag: String,
        message: String,
    ) {
        val s = sink
        if (s != null) s.d(tag, message) else Log.d(tag, message)
    }

    fun i(
        tag: String,
        message: String,
    ) {
        val s = sink
        if (s != null) s.i(tag, message) else Log.i(tag, message)
    }

    fun w(
        tag: String,
        message: String,
    ) {
        val s = sink
        if (s != null) s.w(tag, message) else Log.w(tag, message)
    }

    fun w(
        tag: String,
        message: String,
        tr: Throwable?,
    ) {
        val s = sink
        if (s != null) s.w(tag, message, tr) else Log.w(tag, message, tr)
    }

    fun e(
        tag: String,
        message: String,
    ) {
        val s = sink
        if (s != null) s.e(tag, message) else Log.e(tag, message)
    }

    fun e(
        tag: String,
        message: String,
        tr: Throwable?,
    ) {
        val s = sink
        if (s != null) s.e(tag, message, tr) else Log.e(tag, message, tr)
    }
}
