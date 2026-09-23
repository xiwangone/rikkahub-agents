package me.rerere.rikkahub.data.vault

import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity

/**
 * 审计异常信号：窗口内同一凭证的调用密度或用途宽度超出常规。
 *
 * 仅供界面提示，**不做阻断** —— 判定基于已有审计记录，不额外落库。
 */
data class AuditAnomaly(
    val credentialName: String,
    /** 窗口内调用次数 */
    val calls: Int,
    /** 窗口内不同用途（action 去重）数量 */
    val actions: Int,
) {
    val highFrequency: Boolean get() = calls >= AuditAnomalyDetector.BURST_CALLS
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
            .filter { it.tsMs >= cutoff }
            .groupBy { it.credentialName }
            .mapNotNull { (name, group) ->
                val anomaly = AuditAnomaly(
                    credentialName = name,
                    calls = group.size,
                    actions = group.map { it.action }.distinct().size,
                )
                anomaly.takeIf { it.highFrequency || it.wideSweep }
            }
            .sortedByDescending { it.calls }
    }
}
