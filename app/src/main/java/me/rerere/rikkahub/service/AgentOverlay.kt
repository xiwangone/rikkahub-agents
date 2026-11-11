package me.rerere.rikkahub.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.loadWarmUiSettings
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.utils.LogRedactor
import me.rerere.rikkahub.ui.floating.resolveFloatingColorScheme
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight top-of-screen pill that shows while a generation turn is active so the
 * user always knows when the agent is driving the UI. Uses TYPE_APPLICATION_OVERLAY
 * with FLAG_NOT_TOUCHABLE so it never blocks user gestures. No-ops silently if
 * SYSTEM_ALERT_WINDOW has not been granted — overlay is purely informational.
 *
 * Visibility is **state-driven and reference-counted across parallel turns**:
 *  - [show] increments [activeTurns] and [hide] decrements it (clamped at 0), so when
 *    multiple conversations run in parallel, one finishing turn does not prematurely
 *    hide the overlay while another turn is still active.
 */
object AgentOverlay {
    private const val TAG = "AgentOverlay"

    @Volatile private var view: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前活跃并行生成回合计数（由 GenerationLoop 的 onStart / onCompletion 驱动）。 */
    private val activeTurns = AtomicInteger(0)

    private val active: Boolean
        get() = activeTurns.get() > 0

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

    private fun isForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

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
                AppLog.w(TAG, "addObserver failed", it)
                log("addObserver failed: ${it.message}")
            }
        }
    }

    private fun log(message: String) {
        // Overlay log lines may carry user-visible text: redact once, then log.
        val redacted = LogRedactor.maskText(message)
        AppLog.d(TAG, redacted)
        runCatching { AppLog.d(TAG, redacted) }
    }

    /** 回合开始：累加活跃回合数并按当前前后台立即决定是否显示。 */
    fun show(context: Context, text: String = context.getString(R.string.agent_overlay_working)) {
        val app = context.applicationContext
        val count = activeTurns.incrementAndGet()
        lastText = text
        if (!canShow(app)) {
            log("show: SYSTEM_ALERT_WINDOW not granted, no-op (activeTurns=$count)")
            return
        }
        if (!isEnabled(app)) {
            log("show: overlay disabled by user, no-op (activeTurns=$count)")
            return
        }
        ensureObserver(app)
        if (isForeground()) {
            log("show: app in foreground, suppressing pill (activeTurns=$count)")
            mainHandler.post { hideInternal(app) }
            return
        }
        log("show: app in background, showing pill (activeTurns=$count)")
        mainHandler.post { showInternal(app, text) }
    }

    /** 回合结束：递减活跃回合数，仅当全部并行回合都结束时移除悬浮条。 */
    fun hide(context: Context) {
        val app = context.applicationContext
        val remaining = activeTurns.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        if (remaining > 0) {
            log("hide: turn finished, $remaining turn(s) still active — keeping pill")
            return
        }
        log("hide: all turns finished, removing pill")
        mainHandler.post { hideInternal(app) }
    }

    private fun applyMaterialTheme(app: Context, tv: TextView) {
        val density = app.resources.displayMetrics.density
        val colorScheme = resolveFloatingColorScheme(app, loadWarmUiSettings(app))
        tv.setTextColor(colorScheme.onSurface.toArgb())
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val padH = (14 * density).toInt()
        val padV = (8 * density).toInt()
        tv.setPadding(padH, padV, padH, padV)
        tv.elevation = 6f * density
        tv.background = GradientDrawable().apply {
            cornerRadius = 16f * density
            setColor(colorScheme.surfaceContainerHigh.toArgb())
            setStroke((1 * density).toInt().coerceAtLeast(1), colorScheme.outlineVariant.toArgb())
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun showInternal(app: Context, text: String) {
        val existing = view
        if (existing != null) {
            applyMaterialTheme(app, existing)
            existing.text = text
            return
        }
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val tv = TextView(app).apply {
            this.text = text
            applyMaterialTheme(app, this)
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
            y = (8 * app.resources.displayMetrics.density).toInt()
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
