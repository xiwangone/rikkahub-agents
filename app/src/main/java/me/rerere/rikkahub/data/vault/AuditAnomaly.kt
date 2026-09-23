package me.rerere.rikkahub.data.vault

import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity

/**
 * 审计异常信号：窗口内同一凭证的调用密度或用途宽度超出常规。
 *
 * 仅供界面提示，**不做阻断** —— 判定基于已有审计记录，不额外落库。
 */
data class AuditAnomaly(
    val credentialName: String,
    /** 窗口内实际调用次数（聚合行按 count 累加，用于展示） */
    val calls: Int,
    /** 窗口内不同用途（action 去重）数量 */
    val actions: Int,
    /**
     * 折算到观察窗口的**等效次数**（判定用）。
     *
     * 聚合行会把更长时间内（最长一个聚合窗口）的发生攒在一行里，直接拿 `calls` 判会把
     * “60 分钟里 25 次”误报成“近 5 分钟 25 次”；故按覆盖时长折算。单次行为等于 `calls`。
     */
    val effectiveCalls: Int,
) {
    val highFrequency: Boolean get() = effectiveCalls >= AuditAnomalyDetector.BURST_CALLS
    val wideSweep: Boolean get() = actions >= AuditAnomalyDetector.SWEEP_ACTIONS
}

/**
 * 异常判定（纯函数，便于单测）：
 * - 高频：窗口内调用次数 ≥ [BURST_CALLS]
 * - 批量遍历：窗口内不同用途数 ≥ [SWEEP_ACTIONS]
 */
object AuditAnomalyDetector {
    /** 观察窗口：5 分钟 */
    const val WINDOW_MS = 5 * 60 * 1000L

    /** 观察窗口（分钟，用于文案展示） */
    const val WINDOW_MINUTES = 5

    /** 窗口内次数达到该值视为高频 */
    const val BURST_CALLS = 20

    /** 窗口内不同用途达到该值视为批量遍历 */
    const val SWEEP_ACTIONS = 5

    fun detect(
        logs: List<VaultAuditLogEntity>,
        now: Long = System.currentTimeMillis(),
        windowMs: Long = WINDOW_MS,
    ): List<AuditAnomaly> {
        val cutoff = now - windowMs
        return logs.asSequence()
            // 聚合行的“最新发生”在窗口内则视为活跃（首次时间可能早于窗口，属正常）
            .filter { (it.lastTsMs ?: it.tsMs) >= cutoff }
            .groupBy { it.credentialName }
            .mapNotNull { (name, group) ->
                val calls = group.sumOf { it.count.coerceAtLeast(1) }
                val spanMs = (group.maxOf { it.lastTsMs ?: it.tsMs } - group.minOf { it.tsMs }).coerceAtLeast(0L)
                // 覆盖时长摊成几个观察窗口（至少 1，避免除零）；等效次数 = 总次数 / 窗口数
                val windows = maxOf(1.0, spanMs.toDouble() / windowMs)
                val anomaly = AuditAnomaly(
                    credentialName = name,
                    calls = calls,
                    actions = group.map { it.action }.distinct().size,
                    effectiveCalls = kotlin.math.ceil(calls / windows).toInt(),
                )
                anomaly.takeIf { it.highFrequency || it.wideSweep }
            }
            .sortedByDescending { it.calls }
            .toList()
    }
}
