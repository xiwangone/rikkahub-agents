package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.utils.plus

/** 拖动会话序号（进程内累计）：seq=1 即冷启动后第一次拖动 —— 用于验证「首次拖动更卡」。 */
internal object ReorderPerfSeq {
    var value = 0
}

/** 拖动会话内的卡片重组次数（临时埋点）：验证「声明 Model 稳定」是否真的减少了重组。 */
internal object ReorderRecomposeCounter {
    var count = 0
}

/**
 * 拖动排序性能埋点（用于定位“拖动不跟手”），一次拖动会话累计四类数据：
 * ① 移动统计：相邻移动间隔 gap（混了手速与主线程阻塞，只能同场景横向比）、单次处理耗时 handle。
 * ② 帧节奏：Choreographer 帧间隔（p50 直接暴露刷新周期、jank33/50 记掉帧）。
 * ③ 渲染细分：FrameMetrics —— sync=组合+测量+布局、draw=绘制指令录制、unknownDelay=帧开始前主线程被占、
 *    firstDraw=含首次绘制的帧数、gpu（API 31+）、dropped=系统报的丢帧。
 * ④ 重组次数：拖动列表 item 内 SideEffect 自增，反映“每格重排触发几次卡片重组”。
 * 判读：handleMaxUs 大 → onMove 本身重；syncP95／recompose 大 → 重组/布局重；
 * 帧节奏与 sync 都正常而体感仍卡 → 属“位移不跟手”，不是渲染开销。
 *
 * 埋点会在手势路径上同步读 /proc、/sys 并注册帧回调，因此 [enabled] 默认由 BuildConfig.DEBUG 门控：
 * release 构建下 record/flush/dispose 立即返回，不启动会话、不落日志。
 */
internal class ReorderPerfTracker(
    private val window: Window? = null,
    val enabled: Boolean = false,
) {
    private var moves = 0
    private var gapSumMs = 0L
    private var gapMaxMs = 0L
    private var handleSumUs = 0L
    private var handleMaxUs = 0L
    private var lastAtMs = 0L
    private var startMs = 0L

    // —— 拖动期间的帧间隔（Choreographer：主线程忙时回调顺延，间隔随之变大）——
    // 停手到 flush（400ms）之间仍会计帧，属可接受；p50 会直接暴露屏幕刷新周期（60Hz≈17ms / 120Hz≈8ms）
    private val choreographer: Choreographer? by lazy {
        runCatching { Choreographer.getInstance() }.getOrNull()
    }
    private val frameGapsMs = mutableListOf<Long>()
    private var frameRunning = false
    private var lastFrameNanos = 0L
    private var sessionSeq = 0
    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!frameRunning) return
                if (lastFrameNanos != 0L) {
                    frameGapsMs.add((frameTimeNanos - lastFrameNanos) / 1_000_000)
                }
                lastFrameNanos = frameTimeNanos
                choreographer?.postFrameCallback(this)
            }
        }

    // —— 渲染细分（FrameMetrics：无需新依赖，API 24+；单位统一存微秒）——
    // 回调走主线程 Handler（每帧一条消息，开销可忽略）；gpu 仅 Android 12+ 可读
    private val fmHandler = Handler(Looper.getMainLooper())
    private var fmRunning = false
    private var fmFrames = 0
    private var fmDropped = 0
    private var fmFirstDraw = 0
    private var fmSpikes = 0
    private val fmSyncUs = mutableListOf<Long>()
    private val fmDrawUs = mutableListOf<Long>()
    private val fmTotalUs = mutableListOf<Long>()
    private val fmUnknownUs = mutableListOf<Long>()
    private val fmGpuUs = mutableListOf<Long>()
    private val fmListener =
        object : Window.OnFrameMetricsAvailableListener {
            override fun onFrameMetricsAvailable(
                window: Window,
                frameMetrics: FrameMetrics,
                dropCountSinceLastInvocation: Int,
            ) {
                if (!fmRunning) return
                fmFrames++
                fmDropped += dropCountSinceLastInvocation
                val firstDraw = frameMetrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L
                if (firstDraw) fmFirstDraw++
                val syncUs = frameMetrics.getMetric(FrameMetrics.SYNC_DURATION) / 1_000
                val drawUs = frameMetrics.getMetric(FrameMetrics.DRAW_DURATION) / 1_000
                val totalUs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000
                val unkUs = frameMetrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) / 1_000
                val gpuUs =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        frameMetrics.getMetric(FrameMetrics.GPU_DURATION) / 1_000
                    } else {
                        0L
                    }
                fmSyncUs.add(syncUs)
                fmDrawUs.add(drawUs)
                fmTotalUs.add(totalUs)
                fmUnknownUs.add(unkUs)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    fmGpuUs.add(gpuUs)
                }
                // 长帧归因（临时）：单帧 ≥ 50ms 就落一条，带「会话内相对时刻」+ 各段耗时与是否首次绘制
                // —— 回答「每次拖动里那一次 ~170ms 长帧到底卡在哪一刻、哪一段」
                if (totalUs >= SPIKE_US && fmSpikes < MAX_SPIKES) {
                    fmSpikes++
                    AppLog.i(
                        TAG,
                        "spike seq=$sessionSeq t=+${System.currentTimeMillis() - startMs}ms total=${totalUs}us " +
                            "sync=${syncUs}us draw=${drawUs}us unk=${unkUs}us gpu=${gpuUs}us firstDraw=$firstDraw",
                    )
                }
            }
        }

    // —— 系统压力（会话起止各一次快照：GC / CPU / 堆 / 负载 / 温控降频）——
    // 用于排除「偶发卡」的环境因素：后台 GC、CPU 竞争、温控降频都会让同一份代码时好时坏
    private var stressCpuStartMs = 0L
    private var stressGcStart = 0L
    private var stressGcMsStart = 0L
    private var stressBgcStart = 0L
    private var stressBgcMsStart = 0L
    private var stressHeapStartMb = 0L
    private var stressNativeStartMb = 0L
    private var stressStartInfo = ""

    private fun runtimeStat(key: String): Long =
        runCatching { Debug.getRuntimeStat(key)?.trim()?.toLongOrNull() ?: -1L }.getOrDefault(-1L)

    private fun javaHeapMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / MB
    }

    private fun loadAvg1(): String =
        runCatching { File("/proc/loadavg").readText().trim().substringBefore(' ') }.getOrNull() ?: "?"

    private fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "?"
        val pm = window?.context?.getSystemService(PowerManager::class.java) ?: return "?"
        return pm.currentThermalStatus.toString()
    }

    private fun powerSaveMode(): String =
        if (window?.context?.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) "1" else "0"

    /** 各核当前频率取最大（MHz）——温控降频时这个值会明显掉。 */
    private fun cpuMaxFreqMhz(): String {
        var max = 0L
        for (i in 0 until CPU_FREQ_PROBES) {
            val freq =
                runCatching {
                    File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLong()
                }.getOrNull() ?: continue
            if (freq > max) max = freq
        }
        return if (max > 0) (max / 1000).toString() else "?"
    }

    private fun snapshotStressStart() {
        stressCpuStartMs = android.os.Process.getElapsedCpuTime()
        stressGcStart = runtimeStat(GC_COUNT)
        stressGcMsStart = runtimeStat(GC_TIME)
        stressBgcStart = runtimeStat(BLOCKING_GC_COUNT)
        stressBgcMsStart = runtimeStat(BLOCKING_GC_TIME)
        stressHeapStartMb = javaHeapMb()
        stressNativeStartMb = Debug.getNativeHeapAllocatedSize() / MB
        stressStartInfo =
            "load1=${loadAvg1()} thermal=${thermalStatus()} saver=${powerSaveMode()} cpuMaxMhz=${cpuMaxFreqMhz()}"
    }

    private fun startSession() {
        sessionSeq = ++ReorderPerfSeq.value
        ReorderRecomposeCounter.count = 0
        snapshotStressStart()
        if (!frameRunning) {
            frameRunning = true
            lastFrameNanos = 0L
            frameGapsMs.clear()
            choreographer?.postFrameCallback(frameCallback)
        }
        val fmWindow = window
        if (!fmRunning && fmWindow != null) {
            fmRunning = true
            fmFrames = 0
            fmDropped = 0
            fmFirstDraw = 0
            fmSpikes = 0
            fmSyncUs.clear()
            fmDrawUs.clear()
            fmTotalUs.clear()
            fmUnknownUs.clear()
            fmGpuUs.clear()
            fmWindow.addOnFrameMetricsAvailableListener(fmListener, fmHandler)
        }
    }

    private fun stopSession() {
        frameRunning = false
        choreographer?.removeFrameCallback(frameCallback)
        if (fmRunning) {
            fmRunning = false
            window?.removeOnFrameMetricsAvailableListener(fmListener)
        }
    }

    /** 页面离开时兜底停表，避免监听器残留。 */
    fun dispose() {
        if (!enabled) return
        moves = 0
        stopSession()
    }

    private fun percentile(sorted: List<Long>, p: Int): Long =
        if (sorted.isEmpty()) 0L else sorted[(sorted.size - 1) * p / 100]

    fun record(handleUs: Long) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (moves > 0 && now - lastAtMs > SESSION_GAP_MS) flush()
        if (moves == 0) {
            startMs = now
            gapSumMs = 0
            gapMaxMs = 0
            handleSumUs = 0
            handleMaxUs = 0
            startSession()
        } else {
            val gap = now - lastAtMs
            gapSumMs += gap
            if (gap > gapMaxMs) gapMaxMs = gap
        }
        moves++
        handleSumUs += handleUs
        if (handleUs > handleMaxUs) handleMaxUs = handleUs
        lastAtMs = now
    }

    fun flush() {
        if (!enabled) return
        if (moves == 0) return
        val n = moves
        val frames = frameGapsMs.sorted()
        val sync = fmSyncUs.sorted()
        val draw = fmDrawUs.sorted()
        val total = fmTotalUs.sorted()
        val unknown = fmUnknownUs.sorted()
        val gpu = fmGpuUs.sorted()
        val recomposed = ReorderRecomposeCounter.count
        stopSession()
        AppLog.i(
            TAG,
            "seq=$sessionSeq moves=$n span=${lastAtMs - startMs}ms gapAvg=${if (n > 1) gapSumMs / (n - 1) else 0}ms " +
                "gapMax=${gapMaxMs}ms handleAvgUs=${handleSumUs / n} handleMaxUs=$handleMaxUs | " +
                "frames=${frames.size} p50=${percentile(frames, 50)}ms p95=${percentile(frames, 95)}ms " +
                "max=${frames.lastOrNull() ?: 0L}ms jank33=${frames.count { it >= 33 }} jank50=${frames.count { it >= 50 }} | " +
                "recompose=$recomposed",
        )
        AppLog.i(
            TAG,
            "fm seq=$sessionSeq n=$fmFrames syncP95=${percentile(sync, 95)}us syncMax=${sync.lastOrNull() ?: 0L}us " +
                "drawP95=${percentile(draw, 95)}us totalMax=${total.lastOrNull() ?: 0L}us " +
                "unkP95=${percentile(unknown, 95)}us gpuP95=${percentile(gpu, 95)}us " +
                "firstDraw=$fmFirstDraw dropped=$fmDropped",
        )
        AppLog.i(
            TAG,
            "stress seq=$sessionSeq cpu=+${android.os.Process.getElapsedCpuTime() - stressCpuStartMs}ms " +
                "gc=+${runtimeStat(GC_COUNT) - stressGcStart} gcMs=+${runtimeStat(GC_TIME) - stressGcMsStart} " +
                "bgc=+${runtimeStat(BLOCKING_GC_COUNT) - stressBgcStart} " +
                "bgcMs=+${runtimeStat(BLOCKING_GC_TIME) - stressBgcMsStart} " +
                "heap=${stressHeapStartMb}->${javaHeapMb()}MB " +
                "native=${stressNativeStartMb}->${Debug.getNativeHeapAllocatedSize() / MB}MB | " +
                "start[$stressStartInfo] end[load1=${loadAvg1()} thermal=${thermalStatus()}]",
        )
        moves = 0
    }

    private companion object {
        const val TAG = "ReorderPerf"
        const val SESSION_GAP_MS = 800L
        const val SPIKE_US = 50_000L
        const val MAX_SPIKES = 8
        const val MB = 1024L * 1024L
        const val CPU_FREQ_PROBES = 8
        const val GC_COUNT = "art.gc.gc-count"
        const val GC_TIME = "art.gc.gc-time"
        const val BLOCKING_GC_COUNT = "art.gc.blocking-gc-count"
        const val BLOCKING_GC_TIME = "art.gc.blocking-gc-time"
    }
}
