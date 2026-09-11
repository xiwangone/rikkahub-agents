package me.rerere.rikkahub.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.ui.hooks.readBooleanPreference

/**
 * Lightweight top-of-screen pill that shows while a generation turn is active so the
 * user always knows when the agent is driving the UI. Uses TYPE_APPLICATION_OVERLAY
 * with FLAG_NOT_TOUCHABLE so it never blocks user gestures. No-ops silently if
 * SYSTEM_ALERT_WINDOW has not been granted — overlay is purely informational.
 *
 * Visibility is **state-driven, not edge-triggered**（2026-09-10 修复）：以前的实现只在回合
 * 开始那一瞬判定前后台（`show()` 里判一次），于是「前台发起回合 → 用户退到后台」不会出现
 * 悬浮条，「后台发起回合 → 用户回到前台」也不会消失，表现为时有时无。现在改为：
 *  - 回合生命周期只记录 `active`（`show` / `hide` 由 GenerationLoop 调）；
 *  - 实际显隐由 [ProcessLifecycleOwner] 的 onStart / onStop 驱动（onStop && active → 显示）。
 */
object AgentOverlay {
    private const val TAG = "AgentOverlay"

    @Volatile private var view: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前是否有回合在跑（由 GenerationLoop 的 onStart / onCompletion 驱动）。 */
    @Volatile private var active = false

    /** 最近一次 show 传入的文案，供前后台切换后重新显示时复用。 */
    @Volatile private var lastText: String = ""

    /** 生命周期回调只注册一次。 */
    @Volatile private var observerRegistered = false

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** SharedPreferences key backing the "working overlay" master switch in Settings. */
    const val PREF_ENABLED = "agent_overlay_enabled"

    /** Whether the overlay is currently enabled by the user (Settings switch, default ON). */
    private fun isEnabled(context: Context): Boolean =
        context.applicationContext.readBooleanPreference(PREF_ENABLED, true)

    /**
     * Whether the process is currently in the foreground (an Activity the user is looking at).
     * When the app is foreground, the chat UI already shows its own "working" indicator, so the
     * pill is pure visual clutter over the top bar — we suppress it and only surface it once the
     * app goes to the background (or a headless surface drives the phone).
     */
    private fun isForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /**
     * 注册前后台监听（幂等）。必须在**主线程**注册：`LifecycleRegistry.addObserver` 会断言主线程，
     * 而 [show] 是从 GenerationLoop 的 flow `onStart` 调用的，那条链带 `flowOn(Dispatchers.IO)`
     * —— 也就是说这里默认跑在 IO 线程。2026-09-10 实测：直接注册会抛
     * "Method addObserver must be called on the main thread"，被 runCatching 吞掉后
     * 只留下 `observerRegistered = true` 的假状态，于是后台永不显示悬浮条。
     */
    private fun ensureObserver(app: Context) {
        if (observerRegistered) return
        mainHandler.post {
            if (observerRegistered) return@post
            runCatching {
                ProcessLifecycleOwner.get().lifecycle.addObserver(
                    object : DefaultLifecycleObserver {
                        override fun onStop(owner: LifecycleOwner) {
                            log("lifecycle onStop, active=$active")
                            if (active) mainHandler.post { showInternal(app, lastText) }
                        }

                        override fun onStart(owner: LifecycleOwner) {
                            log("lifecycle onStart")
                            mainHandler.post { hideInternal(app) }
                        }
                    },
                )
                observerRegistered = true
            }.onFailure {
                // 注册失败不要留下"已注册"假状态，否则永不重试
                Log.w(TAG, "addObserver failed", it)
                log("addObserver failed: ${it.message}")
            }
        }
    }

    /** 诊断：同时写系统日志与 App 内存日志（后者可被 read_app_logs 读到）。 */
    private fun log(message: String) {
        Log.d(TAG, message)
        runCatching { AppLog.d(TAG, message) }
    }

    /** 回合开始：记录状态并按当前前后台立即决定是否显示。 */
    fun show(context: Context, text: String = context.getString(R.string.agent_overlay_working)) {
        val app = context.applicationContext
        active = true
        lastText = text
        if (!canShow(app)) {
            log("show: SYSTEM_ALERT_WINDOW not granted, no-op")
            return
        }
        if (!isEnabled(app)) {
            log("show: overlay disabled by user, no-op")
            return
        }
        ensureObserver(app)
        if (isForeground()) {
            // App is on screen; the chat UI has its own progress indicator. Drop the pill so it
            // doesn't overlap the top bar — it will appear via the lifecycle observer if a later
            // turn (or the rest of this one) leaves the app.
            log("show: app in foreground, suppressing pill (will appear on background)")
            mainHandler.post { hideInternal(app) }
            return
        }
        log("show: app in background, showing pill")
        mainHandler.post { showInternal(app, text) }
    }

    /** 回合结束：清状态并移除悬浮条。 */
    fun hide(context: Context) {
        val app = context.applicationContext
        active = false
        log("hide: turn finished, removing pill")
        mainHandler.post { hideInternal(app) }
    }

    @SuppressLint("RtlHardcoded")
    private fun showInternal(app: Context, text: String) {
        val existing = view
        if (existing != null) {
            existing.text = text
            return
        }
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val tv = TextView(app).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val pad = (12 * app.resources.displayMetrics.density).toInt()
            val padV = (6 * app.resources.displayMetrics.density).toInt()
            setPadding(pad, padV, pad, padV)
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(0xCC202020.toInt())
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 紧贴状态栏下方（y=0），不遮挡屏幕上半部分
            y = 0
        }
        try {
            wm.addView(tv, params)
            view = tv
        } catch (t: Throwable) {
            log("addView failed: ${t.message}")
        }
    }

    private fun hideInternal(app: Context) {
        val v = view ?: return
        view = null
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        try {
            wm.removeViewImmediate(v)
        } catch (t: Throwable) {
            log("removeView failed: ${t.message}")
        }
    }
}
