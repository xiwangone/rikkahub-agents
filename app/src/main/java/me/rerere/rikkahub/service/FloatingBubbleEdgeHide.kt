package me.rerere.rikkahub.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.WindowManager
/** 浮窗气泡边长：46dp，与桌面图标视觉面积对齐，图标资产本身已是去蓝边圆形 */
internal const val BUBBLE_SIZE_DP = 46

/** 贴边隐藏后留在屏幕内的可见宽度：15dp，点按或拖动可恢复 */
internal const val EDGE_HIDE_VISIBLE_DP = 15

/** 松手时判定为“停在边缘”的距离：气泡边距屏幕边缘 ≤ 24dp 即贴边隐藏 */
internal const val EDGE_SNAP_THRESHOLD_DP = 24

/** 贴边隐藏/恢复的滑动画时长 */
internal const val EDGE_ANIM_DURATION_MS = 180L

/** 收起态气泡窗口标志：必须带 FLAG_LAYOUT_NO_LIMITS，允许贴边隐藏滑向屏幕边界外。 */
internal const val COLLAPSED_BUBBLE_WINDOW_FLAGS =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

internal enum class FloatingBubbleEdge { LEFT, RIGHT }

/**
 * 贴边隐藏的目标 x（LayoutParams.x，gravity 为 TOP|END 时为距右边缘的距离）。
 * 气泡停在左右边缘 thresholdPx 内时返回隐藏目标 x 与所在边缘，否则返回 null（留在原地）。
 */
internal fun computeEdgeHideTargetX(
    screenWidthPx: Int,
    bubbleWidthPx: Int,
    currentX: Int,
    visiblePx: Int,
    thresholdPx: Int,
): Pair<Int, FloatingBubbleEdge>? {
    val maxX = (screenWidthPx - bubbleWidthPx).coerceAtLeast(0)
    return when {
        currentX <= thresholdPx -> (-(bubbleWidthPx - visiblePx)) to FloatingBubbleEdge.RIGHT
        currentX >= maxX - thresholdPx -> (screenWidthPx - visiblePx) to FloatingBubbleEdge.LEFT
        else -> null
    }
}

/** 从贴边隐藏恢复到完整气泡时，气泡贴在对应边缘的目标 x。 */
internal fun computeEdgeRestoreTargetX(
    screenWidthPx: Int,
    bubbleWidthPx: Int,
    edge: FloatingBubbleEdge,
): Int = if (edge == FloatingBubbleEdge.RIGHT) 0 else (screenWidthPx - bubbleWidthPx).coerceAtLeast(0)

/** 贴边隐藏的运行时状态与滑动画：脱离 controller 独立成类，避免主类继续膨胀。 */
internal class FloatingBubbleEdgeHideState(
    private val params: () -> WindowManager.LayoutParams?,
    private val updateLayout: (WindowManager.LayoutParams) -> Unit,
    private val dp: (Int) -> Int,
    private val screenWidthPx: () -> Int,
) {
    var bubbleHidden = false
        private set
    var hiddenEdge: FloatingBubbleEdge? = null
        private set
    private var edgeAnimator: ValueAnimator? = null

    /** 松手时：气泡停在左右边缘则滑入贴边隐藏，否则留在原地。 */
    fun maybeHide() {
        val lp = params() ?: return
        val target =
            computeEdgeHideTargetX(
                screenWidthPx(),
                lp.width,
                lp.x,
                dp(EDGE_HIDE_VISIBLE_DP),
                dp(EDGE_SNAP_THRESHOLD_DP),
            ) ?: return
        bubbleHidden = true
        hiddenEdge = target.second
        animateTo(target.first)
    }

    /** 从贴边隐藏恢复到完整气泡（滑出动画）。 */
    fun restore() {
        val lp = params() ?: return
        val edge = hiddenEdge ?: return
        bubbleHidden = false
        hiddenEdge = null
        animateTo(computeEdgeRestoreTargetX(screenWidthPx(), lp.width, edge))
    }

    /** 只清状态不动画（拖动从细条弹出时用，位置由调用方接管）。 */
    fun clearHidden() {
        bubbleHidden = false
        hiddenEdge = null
    }

    /** 取消进行中的动画（手势按下、窗口 detach 时用）。 */
    fun cancelAnimator() {
        edgeAnimator?.cancel()
        edgeAnimator = null
    }

    /** 窗口 detach：取消动画并清空贴边状态。 */
    fun reset() {
        cancelAnimator()
        clearHidden()
    }

    /** 水平滑动画：只改 lp.x，可被 cancelAnimator 取消。 */
    private fun animateTo(targetX: Int) {
        val lp = params() ?: return
        val startX = lp.x
        if (startX == targetX) return
        cancelAnimator()
        val animator =
            ValueAnimator.ofInt(startX, targetX).apply {
                duration = EDGE_ANIM_DURATION_MS
                addUpdateListener { value ->
                    params()?.let { p ->
                        p.x = value.animatedValue as Int
                        updateLayout(p)
                    }
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        private var cancelled = false

                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (edgeAnimator === animation) edgeAnimator = null
                            if (!cancelled) {
                                params()?.let { p ->
                                    if (p.x != targetX) {
                                        p.x = targetX
                                        updateLayout(p)
                                    }
                                }
                            }
                        }
                    },
                )
            }
        edgeAnimator = animator
        animator.start()
    }
}
