package me.rerere.rikkahub.service

import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 悬浮气泡的拖动处理：从 controller 抽出，避免主类继续膨胀。
 * 全部依赖以 lambda 传入，与 FloatingBubbleEdgeHideState 同一形态。
 */
internal class FloatingBubbleDragHandler(
    private val params: () -> WindowManager.LayoutParams?,
    private val edgeHide: FloatingBubbleEdgeHideState,
    private val updateLayout: (WindowManager.LayoutParams) -> Unit,
    private val dp: (Int) -> Int,
    private val screenWidthPx: () -> Int,
    private val screenHeightPx: () -> Int,
) {
    fun install(view: View) {
        var downX = 0f
        var downY = 0f
        var initialX = 0
        var initialY = 0
        var dragged = false
        var startedHidden = false
        var restoredDuringDrag = false
        view.setOnTouchListener { _, event ->
            val lp = params() ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    edgeHide.cancelAnimator()
                    downX = event.rawX
                    downY = event.rawY
                    initialX = lp.x
                    initialY = lp.y
                    dragged = false
                    startedHidden = edgeHide.bubbleHidden
                    restoredDuringDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(6)) dragged = true
                    if (dragged) {
                        if (startedHidden && !restoredDuringDrag) {
                            // 从贴边隐藏的细条开始拖动：先完整弹出，再跟随手指。
                            edgeHide.hiddenEdge?.let { edge ->
                                lp.x =
                                    computeEdgeRestoreTargetX(
                                        screenWidthPx(),
                                        lp.width,
                                        edge,
                                    )
                                initialX = lp.x
                            }
                            edgeHide.clearHidden()
                            restoredDuringDrag = true
                        }
                        lp.x =
                            (initialX - dx).coerceIn(
                                0,
                                (screenWidthPx() - lp.width).coerceAtLeast(0),
                            )
                        lp.y =
                            (initialY + dy).coerceIn(
                                dp(24),
                                (
                                    screenHeightPx() -
                                        lp.height -
                                        dp(48)
                                ).coerceAtLeast(dp(24)),
                            )
                        updateLayout(lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragged && event.action == MotionEvent.ACTION_UP) {
                        if (startedHidden) {
                            // 点按细条：滑出完整气泡，不打开面板。
                            edgeHide.restore()
                        } else {
                            view.performClick()
                        }
                    } else {
                        // 松手：停在边缘则贴边隐藏，否则留在原地。
                        edgeHide.maybeHide()
                    }
                    true
                }
                else -> false
            }
        }
    }
}
