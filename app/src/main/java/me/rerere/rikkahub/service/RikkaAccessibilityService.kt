package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.phoneuse.PhoneUseAccessibilityService
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

// 类名 RikkaAccessibilityService 26 字符, 超过 23 字符会被 logcat 截断, Service 缩写为 Svc。
private const val TAG = "RikkaAccessibilitySvc"
private const val GESTURE_TIMEOUT_MS = 5_000L
private const val LOG_RING_SIZE = 50

data class ActionLogEntry(
    val type: String,
    val paramsSummary: String,
    val success: Boolean,
    val timestampMs: Long,
)

/**
 * 统一无障碍服务：同时承担 [PhoneUseAccessibilityService]（[me.rerere.rikkahub.phoneuse.PhoneController]）
 * 与屏幕自动化 / 前台应用感知能力，用户在系统设置中只需开启一个无障碍开关即可同时驱动
 * `phone_use` 与屏幕工具族；平时只静态监听窗口切换事件，仅在动作等待静止窗口期间临时打开
 * 内容变化事件，避免全系统滚动触发跨进程事件风暴，且不额外常驻专用线程。
 */
class RikkaAccessibilityService : PhoneUseAccessibilityService() {

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private val _lastActions = MutableStateFlow<List<ActionLogEntry>>(emptyList())
    val lastActions = _lastActions.asStateFlow()

    // Serialise overlapping gesture-dispatch callers. Each caller takes a ticket; the
    // shared current-counter advances when a caller's `finally` runs. Both fields are
    // INSTANCE-scoped so a service-process restart resets them.
    private val serializeGate = AtomicInteger(0)
    private val gateCurrent = AtomicInteger(0)
    private val contentListenCount = AtomicInteger(0)

    // Last accessibility event uptime, used by ScreenState to settle
    // between capture and comparison.
    @Volatile
    var lastWindowEventUptime: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _running.value = true
        AppLog.i(TAG, "AccessibilityService connected (unified PhoneController + ScreenAutomation)")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        _running.value = false
        _lastActions.value = emptyList()
        contentListenCount.set(0)
        AppLog.i(TAG, "AccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        _running.value = false
        contentListenCount.set(0)
        super.onDestroy()
    }

    /**
     * 仅在 `withActionEnvelope` 等待界面静止的短暂窗口内开启 `TYPE_WINDOW_CONTENT_CHANGED`，
     * 结束后恢复为仅监听 `TYPE_WINDOW_STATE_CHANGED`，避免全天跨进程投递滚动事件。
     */
    fun setListenContentChanges(enabled: Boolean) {
        val active = if (enabled) {
            contentListenCount.incrementAndGet() > 0
        } else {
            contentListenCount.updateAndGet { if (it > 0) it - 1 else 0 } > 0
        }
        runCatching {
            val info = serviceInfo ?: return
            val targetEvents = if (active) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            } else {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            }
            if (info.eventTypes != targetEvents) {
                info.eventTypes = targetEvents
                serviceInfo = info
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastWindowEventUptime = android.os.SystemClock.uptimeMillis()
        // Phase 12 — feed foreground-app transitions to the workflow trigger dispatcher.
        // We only care about TYPE_WINDOW_STATE_CHANGED and only when the package name is
        // present. The dispatcher itself de-dupes (skips no-op transitions) and dispatches
        // off-thread, so this stays fast on the AccessibilityService dispatcher.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank()) {
                me.rerere.rikkahub.workflow.trigger.AppForegroundDispatcher
                    .onForegroundChange(pkg)
            }
        }
    }

    override fun onInterrupt() {
        // Required override; no-op.
    }

    fun appendLog(entry: ActionLogEntry) {
        val current = _lastActions.value
        val next = (current + entry).takeLast(LOG_RING_SIZE)
        _lastActions.value = next
    }

    /**
     * Dispatches a gesture and suspends until it completes / cancels / times out (5s).
     * Serializes overlapping callers via ticket gate and uses the main looper callback handler.
     */
    suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean {
        val gate = serializeGate.incrementAndGet()
        try {
            while (gateCurrent.get() != gate - 1) {
                currentCoroutineContext().ensureActive()
                kotlinx.coroutines.delay(10)
            }
            val ok =
                withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        val callback =
                            object : AccessibilityService.GestureResultCallback() {
                                override fun onCompleted(d: GestureDescription) {
                                    if (cont.isActive) cont.resume(true)
                                }

                                override fun onCancelled(d: GestureDescription) {
                                    if (cont.isActive) cont.resume(false)
                                }
                            }
                        val dispatched = dispatchGesture(gesture, callback, null)
                        if (!dispatched && cont.isActive) cont.resume(false)
                    }
                } ?: false
            return ok
        } finally {
            gateCurrent.set(gate)
        }
    }

    fun buildTapPath(
        x: Float,
        y: Float,
    ): Path = Path().apply { moveTo(x, y) }

    fun buildSwipePath(
        sx: Float,
        sy: Float,
        ex: Float,
        ey: Float,
    ): Path =
        Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }

    /**
     * Walk the active window's node tree depth-first. `filter` decides whether to emit a node;
     * traversal stops once `cap` nodes have been emitted. Returns (emitted, totalSeen, truncated).
     */
    fun traverseTree(
        root: AccessibilityNodeInfo,
        filter: (AccessibilityNodeInfo, depth: Int) -> Boolean,
        cap: Int,
        emit: (AccessibilityNodeInfo, depth: Int, traversalIndex: Int) -> Unit,
        recycle: Boolean = false,
    ): Triple<Int, Int, Boolean> {
        var emitted = 0
        var seen = 0
        var truncated = false

        // recycle=true 时在 API < 33 上回收每个已访问的非根节点（33+ 节点不池化，no-op）
        val canRecycle = recycle && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

        fun walk(
            n: AccessibilityNodeInfo,
            depth: Int,
        ) {
            if (truncated) return
            seen++
            if (filter(n, depth)) {
                emit(n, depth, seen)
                emitted++
                if (emitted >= cap) {
                    truncated = true
                    return
                }
            }
            for (i in 0 until n.childCount) {
                if (truncated) return
                val child = n.getChild(i) ?: continue
                walk(child, depth + 1)
                if (canRecycle && child !== root) {
                    child.recycle()
                }
            }
        }
        walk(root, 0)
        return Triple(emitted, seen, truncated)
    }

    /**
     * Walks up the parent chain looking for a clickable node. Returns the original if it's
     * already clickable, or the nearest clickable ancestor, or null if none exists.
     */
    fun resolveClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    /**
     * Captures a screenshot of the given display. Suspends until callback fires; returns
     * ScreenshotOutcome.Success(softwareBitmap) or Failure(reason). The success bitmap is a
     * software bitmap (ARGB_8888) — caller MUST call bitmap.recycle() when done to free
     * native memory. Returns Failure("api_too_low") on Android < 11 (API 30).
     */
    suspend fun captureScreenshot(displayId: Int): ScreenshotOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotOutcome.Failure("api_too_low")
        }
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(
                displayId,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val bmp =
                                try {
                                    Bitmap
                                        .wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                        ?.copy(Bitmap.Config.ARGB_8888, false)
                                } finally {
                                    result.hardwareBuffer.close()
                                }
                            if (cont.isActive) {
                                cont.resume(
                                    if (bmp != null) {
                                        ScreenshotOutcome.Success(bmp)
                                    } else {
                                        ScreenshotOutcome.Failure("bitmap_decode_failed")
                                    },
                                )
                            }
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resume(ScreenshotOutcome.Failure("exception:${t.message}"))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val reason =
                            when (errorCode) {
                                ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "rate_limited"
                                ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no_access"
                                ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal_error"
                                else -> "error_code_$errorCode"
                            }
                        if (cont.isActive) cont.resume(ScreenshotOutcome.Failure(reason))
                    }
                },
            )
        }
    }

    sealed class ScreenshotOutcome {
        data class Success(
            val bitmap: Bitmap,
        ) : ScreenshotOutcome()

        data class Failure(
            val reason: String,
        ) : ScreenshotOutcome()
    }

    companion object {
        @Volatile
        var instance: RikkaAccessibilityService? = null
            internal set
    }
}
