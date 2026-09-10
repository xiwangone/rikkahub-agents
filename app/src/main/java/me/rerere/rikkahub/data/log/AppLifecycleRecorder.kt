package me.rerere.rikkahub.data.log

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle

/**
 * 进程生命周期事件记录器：把「冷启动 / 前后台 / 内存压力 / 上次疑似被杀」等事件
 * 写入 FileLogSink(KIND_LIFECYCLE)，用于诊断「左滑退后台重进像重启/丢会话」类问题。
 *
 * 判定原理：Android 无法在进程被杀瞬间回调，采用「下次启动对比」：
 * - 每次 PROCESS_START 记录 lastStartTs / lastState；
 * - 若上次状态停留在 FOREGROUND/BACKGROUND（无干净退出路径）→ 判定「上次疑似被杀」；
 * - 若 CrashHandler 有崩溃标记 → 判定「上次崩溃重启」并消费标记。
 */
object AppLifecycleRecorder {
    private const val PREFS = "app_lifecycle"
    private const val KEY_LAST_TS = "last_ts"
    private const val KEY_LAST_STATE = "last_state"
    private const val KEY_FIRST_RUN = "first_run_done"

    private const val MIN_KILL_INTERVAL_MS = 5_000L

    @Volatile
    private var startedCount = 0

    @Volatile
    private var appStartElapsedMs = 0L

    fun install(
        application: Application,
        appStartElapsedMs: Long,
    ) {
        this.appStartElapsedMs = appStartElapsedMs
        val context = application.applicationContext
        recordProcessStart(context)

        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    val before = startedCount
                    startedCount++
                    if (before == 0) {
                        record(context, "FOREGROUND", restart = false)
                        // 冷启动到首个 Activity 可见耗时（诊断"像重启"体验）
                        val gapMs = android.os.SystemClock.elapsedRealtime() - appStartElapsedMs
                        FileLogSink.append(
                            FileLogSink.KIND_LIFECYCLE,
                            "[proc] FIRST_FOREGROUND_AFTER_START ${gapMs}ms",
                        )
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    startedCount--
                    if (startedCount == 0) {
                        record(context, "BACKGROUND", restart = false)
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            },
        )
    }

    /** 内存压力事件：由 Application.onTrimMemory 转发 */
    fun onTrimMemory(level: Int) {
        val label =
            when (level) {
                android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "TRIM_UI_HIDDEN"
                android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "TRIM_BACKGROUND"
                android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "TRIM_MODERATE"
                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "TRIM_RUNNING_LOW"
                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "TRIM_RUNNING_CRITICAL"
                else -> "TRIM_$level"
            }
        FileLogSink.append(FileLogSink.KIND_LIFECYCLE, "[mem] $label")
    }

    private fun recordProcessStart(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val firstRun = !prefs.getBoolean(KEY_FIRST_RUN, false)
        if (firstRun) {
            prefs.edit().putBoolean(KEY_FIRST_RUN, true).apply()
        }

        val lastTs = prefs.getLong(KEY_LAST_TS, 0L)
        val lastState = prefs.getString(KEY_LAST_STATE, null)
        val now = System.currentTimeMillis()

        val restartReason =
            if (firstRun || lastTs == 0L) {
                null
            } else if (now - lastTs < MIN_KILL_INTERVAL_MS) {
                // 短间隔内冷启动：上次可能只是快速退出/开发者重装，不算被杀
                null
            } else {
                val crashed = me.rerere.rikkahub.utils.CrashHandler.hasCrashed(context)
                if (crashed) {
                    me.rerere.rikkahub.utils.CrashHandler.clearCrashed(context)
                    "上次崩溃重启（距上次 ${formatGap(now - lastTs)}，状态=$lastState）"
                } else {
                    "上次疑似被系统杀死（距上次 ${formatGap(now - lastTs)}，状态=$lastState）"
                }
            }

        prefs.edit().putLong(KEY_LAST_TS, now).putString(KEY_LAST_STATE, "START").apply()

        val detail = restartReason?.let { "；原因: $it" } ?: ""
        FileLogSink.append(
            FileLogSink.KIND_LIFECYCLE,
            "[proc] PROCESS_START (heap=${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB)$detail",
        )
    }

    private fun record(
        context: Context,
        state: String,
        restart: Boolean,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_STATE, state).apply()
        FileLogSink.append(FileLogSink.KIND_LIFECYCLE, "[proc] $state")
    }

    private fun formatGap(ms: Long): String {
        val sec = ms / 1000
        return if (sec < 60) "${sec}s" else "${sec / 60}m${sec % 60}s"
    }
}