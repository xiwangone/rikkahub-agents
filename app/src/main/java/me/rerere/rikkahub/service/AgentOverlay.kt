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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.hooks.readBooleanPreference

/**
 * Lightweight top-of-screen pill that shows while a generation turn is active so the
 * user always knows when the agent is driving the UI. Uses TYPE_APPLICATION_OVERLAY
 * with FLAG_NOT_TOUCHABLE so it never blocks user gestures. No-ops silently if
 * SYSTEM_ALERT_WINDOW has not been granted — overlay is purely informational.
 */
object AgentOverlay {
    private const val TAG = "AgentOverlay"

    @Volatile private var view: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

    fun show(context: Context, text: String = context.getString(R.string.agent_overlay_working)) {
        val app = context.applicationContext
        if (!canShow(app)) {
            Log.d(TAG, "show: SYSTEM_ALERT_WINDOW not granted, no-op")
            return
        }
        if (!isEnabled(app)) {
            Log.d(TAG, "show: overlay disabled by user, no-op")
            return
        }
        if (isForeground()) {
            // App is on screen; the chat UI has its own progress indicator. Drop the pill so it
            // doesn't overlap the top bar. It will appear if a later turn leaves the app.
            Log.d(TAG, "show: app in foreground, suppressing pill")
            mainHandler.post { hideInternal(app) }
            return
        }
        mainHandler.post { showInternal(app, text) }
    }

    fun hide(context: Context) {
        val app = context.applicationContext
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
            Log.w(TAG, "addView failed", t)
        }
    }

    private fun hideInternal(app: Context) {
        val v = view ?: return
        view = null
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        try {
            wm.removeViewImmediate(v)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView failed", t)
        }
    }
}
