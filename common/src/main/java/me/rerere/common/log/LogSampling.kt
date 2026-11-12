package me.rerere.common.log

/**
 * 高频日志的采样与节流门：纯 Kotlin、无 Android 依赖，供各模块的日志调用点共用。
 *
 * 背景：流式 onEvent（每 token 1 条）、ASR 丢帧（音频读取循环内每帧 1 条）、隧道
 * 周期重试（每 30 秒 1 条）这类调用点靠 AppLog 的 3 秒去重桶压不住——消息里带
 * 计数、序号或长度，每条都不一样，去重 key 永远不同。采样与节流在调用点先把
 * 量降下来，去重桶只负责兜底。
 *
 * 约定：一个门只用在一个调用点内（一个事件流、一个录音循环、一次隧道守护），
 * 不保证线程安全；不要跨调用点共用一个门，否则计数互相污染。
 */

/**
 * 每 N 次放行 1 次的采样门：首个事件必记，其后每 [every] 个事件记 1 条。
 *
 * 供流式 onEvent 这类“量大但单条便宜”的调用点用：release 下把 O(事件数) 的
 * 摘要行压到 O(事件数 / every)，同时保留首条以便确认流已启动。
 * debug 构建不走采样（全量记，排查时要看每一条）。
 *
 * @param every 采样间隔（>= 1；1 表示每次都放行，即不采样）
 */
class SamplingGate(private val every: Int) {
    init {
        require(every >= 1) { "every must be >= 1, was $every" }
    }

    private var seen = 0

    /** 第 1 次与每 [every] 次调用返回 true，其余返回 false。 */
    fun shouldLog(): Boolean {
        seen += 1
        return seen == 1 || seen % every == 0
    }

    /** 已观察到的事件总数（供调用方在汇总行里写“共 N 条”）。 */
    val totalSeen: Int get() = seen
}

/**
 * 时间窗口节流门：窗口内首次放行，其后抑制，直到窗口过去后再次放行。
 *
 * 供 ASR 丢帧、隧道缺配置这类“同一错误周期性重复”的调用点用：首次立即留痕，
 * 窗口内只计数不记行，放行时在汇总行里带上被抑制的条数。
 *
 * @param intervalMs 节流窗口（毫秒，必须 > 0）
 * @param clock 时间源，默认系统墙钟；测试注入假时钟
 */
class ThrottleGate(
    private val intervalMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(intervalMs > 0) { "intervalMs must be > 0, was $intervalMs" }
    }

    private var lastLoggedAtMs: Long? = null
    private var suppressedSinceLastLog = 0

    /**
     * 窗口内首次调用（或上次放行已超过 [intervalMs]）返回 true；
     * 其余调用返回 false 并把抑制计数加一。
     */
    fun shouldLog(): Boolean {
        val now = clock()
        val last = lastLoggedAtMs
        return if (last == null || now - last >= intervalMs) {
            lastLoggedAtMs = now
            true
        } else {
            suppressedSinceLastLog++
            false
        }
    }

    /**
     * 自上次放行以来被抑制的调用次数；读出后清零。
     *
     * 调用方在 [shouldLog] 返回 true 后立刻读一次，写进汇总行
     * （如“(+N suppressed)”）；不读则下个窗口的计数会接着累加。
     */
    fun takeSuppressedCount(): Int {
        val count = suppressedSinceLastLog
        suppressedSinceLastLog = 0
        return count
    }
}
