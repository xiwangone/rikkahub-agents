package me.rerere.rikkahub.di

import android.util.Log
import me.rerere.common.android.Logging
import me.rerere.rikkahub.utils.LogRedactor
import okhttp3.logging.HttpLoggingInterceptor

/**
 * HttpLoggingInterceptor 输出桥：共享 OkHttpClient 上的 HttpLoggingInterceptor
 * （DEBUG-only、HEADERS 级）原本只写 logcat，与 RequestLoggingInterceptor
 * （走 Logging.logRequest、已脱敏、受请求日志开关门控）重复，且日志页看不到它。
 *
 * 桥接后每行先过 LogRedactor.maskText 脱敏（与 redactHeader 双层脱敏），再：
 * - 始终写 logcat（Log.i，与今天默认 logger 的级别/tag 一致，不丢调试输出）；
 * - 仅在请求日志开关开时经 Logging.log("OkHttp", …) 进 TextLog。
 * 开关关时不写 TextLog：请求日志开关管的是应用内记录，不绕过它。
 */
object OkHttpLoggingBridge : HttpLoggingInterceptor.Logger {
    private const val TAG = "OkHttp"

    override fun log(message: String) {
        logLine(message)
    }

    fun logLine(message: String) {
        val masked = LogRedactor.maskText(message)
        Log.i(TAG, masked)
        if (!Logging.isRequestLoggingEnabled()) return
        Logging.log(TAG, masked)
    }
}
