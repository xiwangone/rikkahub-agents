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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.quota.QuotaAggregate
import me.rerere.rikkahub.data.quota.QuotaSnapshotHolder
import me.rerere.rikkahub.data.quota.QuotaStatus

/**
 * Agent 工作状态悬浮窗（可交互版）。
 *
 * 行为：
 * - 拖动：自由移动，松手后贴边吸附到屏幕左/右边缘
 * - 贴边时显示为竖条状态线：
 *   - 额度充足（GREEN）→ 绿线
 *   - 额度紧张（YELLOW）→ 黄线
 *   - 额度危险（RED）→ 红线
 *   - 未登录/未捕获/未知（UNKNOWN）→ 白线
 * - 短按：展开/收起额度明细卡片（QuotaAggregate 内容）
 * - 长按（>800ms 且未移动）：关闭悬浮窗
 *
 * 数据源：Agent 运行期间由 GenerationHandler 调用 show()；额度快照由
 * [updateQuota] 更新（QuotaConsolePage 捕获 → QuotaAggregate）。
 */
object AgentOverlay {
    private const val TAG = "AgentOverlay"

    @Volatile private var rootView: View? = null
    private var dotView: View? = null
    private var expandedView: LinearLayout? = null
    private var isExpanded = false
    private var latestQuota: QuotaAggregate? = null
    private var latestText: String = "The agent is working"
    private var quotaJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(
        context: Context,
        text: String = "The agent is working",
    ) {
        latestText = text
        val app = context.applicationContext
        if (!canShow(app)) {
            Log.d(TAG, "show: SYSTEM_ALERT_WINDOW not granted, no-op")
            return
        }
        mainHandler.post { showInternal(app) }
        // 订阅额度快照：更新状态线颜色 + 展开卡片
        if (quotaJob == null) {
            quotaJob =
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
                ).launch {
                    QuotaSnapshotHolder.aggregate.collect { aggregate ->
                        updateQuota(aggregate)
                    }
                }
        }
    }

    fun hide(context: Context) {
        quotaJob?.cancel()
        quotaJob = null
        val app = context.applicationContext
        mainHandler.post { hideInternal(app) }
    }

    /** 更新额度快照（驱动状态线颜色 + 展开卡片内容）。 */
    fun updateQuota(aggregate: QuotaAggregate?) {
        latestQuota = aggregate
        mainHandler.post {
            val dot = dotView ?: return@post
            (dot.background as? GradientDrawable)?.setColor(statusColor(aggregate))
            refreshExpanded()
        }
    }

    private fun statusColor(aggregate: QuotaAggregate?): Int =
        when (aggregate?.overallStatus) {
            QuotaStatus.GREEN -> Color.rgb(34, 197, 94)
            QuotaStatus.YELLOW -> Color.rgb(234, 179, 8)
            QuotaStatus.RED -> Color.rgb(239, 68, 68)
            else -> Color.WHITE // 未登录/未捕获/未知 → 白线
        }

    /** 贴边竖条尺寸（dp）。 */
    private const val BAR_WIDTH_DP = 8f
    private const val BAR_HEIGHT_DP = 100f

    /**
     * 根据位置调整 dot 形态：贴边 → 竖条状态线；未贴边 → 工作 pill。
     * 在拖动/吸附后调用。
     */
    private fun applyDockShape(app: Context, x: Int, isDocked: Boolean) {
        val dot = dotView ?: return
        val density = app.resources.displayMetrics.density
        val lp = dot.layoutParams
        if (isDocked) {
            // 贴边：竖条（隐藏文字，只留状态色）
            lp.width = (BAR_WIDTH_DP * density).toInt()
            lp.height = (BAR_HEIGHT_DP * density).toInt()
            (dot as TextView).text = ""
            (dot.background as? GradientDrawable)?.cornerRadius = (4 * density).toInt()
        } else {
            // 未贴边：pill（显示工作状态文字）
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            (dot as TextView).text = latestText
            (dot.background as? GradientDrawable)?.cornerRadius = 100f
        }
        dot.layoutParams = lp
        // 记录贴边状态，供后续展开卡片定位参考
        latestDocked = isDocked
    }

    @Volatile private var latestDocked = false

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun showInternal(app: Context) {
        if (rootView != null) {
            (dotView as? TextView)?.text = latestText
            return
        }
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = app.resources.displayMetrics.density

        // 悬浮窗根容器（LinearLayout：竖条 dot + 展开卡片）
        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 状态线 dot（可拖动主体）
        val dot = TextView(app).apply {
            text = latestText
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val pad = (16 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(pad, padV, pad, padV)
            background =
                GradientDrawable().apply {
                    cornerRadius = 100f
                    setColor(statusColor(latestQuota))
                }
        }
        root.addView(dot)
        dotView = dot

        // 展开卡片（默认 GONE）
        val expanded = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            background =
                GradientDrawable().apply {
                    cornerRadius = (12 * density).toInt()
                    setColor(0xE6202020.toInt())
                }
        }
        // 展开卡片固定宽度 260dp（放下工作状态 + 额度明细）
        expanded.layoutParams = LinearLayout.LayoutParams((260 * density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        root.addView(expanded)
        expandedView = expanded

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        val params =
            WindowManager
                .LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = (64 * density).toInt()
                }

        // 拖动 + 贴边吸附 + 点击逻辑
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var lastTouchDownTime = 0L

        dot.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = (view.layoutParams as? WindowManager.LayoutParams)?.x ?: 0
                    initialY = (view.layoutParams as? WindowManager.LayoutParams)?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    lastTouchDownTime = System.currentTimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    val p = root.layoutParams as WindowManager.LayoutParams
                    p.x = initialX + deltaX
                    p.y = initialY + deltaY
                    wm.updateViewLayout(root, p)
                    // 拖动离开边缘 → 恢复 pill
                    val screenWidth = app.resources.displayMetrics.widthPixels
                    val viewWidth = root.width.coerceAtLeast(dot.width)
                    if (latestDocked && p.x > 0 && p.x + viewWidth < screenWidth) {
                        applyDockShape(app, p.x, isDocked = false)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - lastTouchDownTime
                    val moved =
                        kotlin.math.abs(event.rawX - initialTouchX) > 10 ||
                            kotlin.math.abs(event.rawY - initialTouchY) > 10
                    if (!moved && duration < 300) {
                        // 短按：切换展开/收起
                        isExpanded = !isExpanded
                        expanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
                        refreshExpanded()
                    } else if (duration > 800 && !moved) {
                        // 长按：关闭悬浮窗
                        hideInternal(app)
                    } else {
                        // 拖动结束：贴边吸附到最近边缘
                        val p = root.layoutParams as WindowManager.LayoutParams
                        val screenWidth = app.resources.displayMetrics.widthPixels
                        val viewWidth = root.width.coerceAtLeast(dot.width)
                        val dockLeft = p.x + viewWidth / 2 < screenWidth / 2
                        p.x = if (dockLeft) 0 else screenWidth - viewWidth
                        wm.updateViewLayout(root, p)
                        // 贴边 → 竖条状态线；拖动离开 → 恢复 pill
                        applyDockShape(app, p.x, isDocked = dockLeft || p.x >= screenWidth - viewWidth - 1)
                    }
                    true
                }

                else -> false
            }
        }

        try {
            wm.addView(root, params)
            rootView = root
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed", t)
        }
    }

    /** 刷新展开卡片内容（额度明细 + 工作状态）。 */
    private fun refreshExpanded() {
        val expanded = expandedView ?: return
        if (!isExpanded) return
        expanded.removeAllViews()
        val density = expanded.context.resources.displayMetrics.density

        // 工作状态行
        val statusRow =
            TextView(expanded.context).apply {
                text = "🤖 $latestText"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 0, 0, (4 * density).toInt())
            }
        expanded.addView(statusRow)

        // 额度行
        val quota = latestQuota
        if (quota == null || quota.snapshots.isEmpty()) {
            val empty =
                TextView(expanded.context).apply {
                    text = "📊 额度未捕获（去设置 → 额度查询 登录后查询）"
                    setTextColor(Color.LTGRAY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                }
            expanded.addView(empty)
        } else {
            quota.snapshots.forEach { snap ->
                val row =
                    TextView(expanded.context).apply {
                        text =
                            buildString {
                                append(snap.rawText.take(30))
                                append(" → ")
                                append("%.2f".format(snap.numericValue))
                                if (snap.status == QuotaStatus.UNKNOWN) append(" (?)")
                            }
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                    }
                expanded.addView(row)
            }
        }
    }

    private fun hideInternal(app: Context) {
        val v = rootView ?: return
        rootView = null
        dotView = null
        expandedView = null
        isExpanded = false
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        try {
            wm.removeViewImmediate(v)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView failed", t)
        }
    }
}
