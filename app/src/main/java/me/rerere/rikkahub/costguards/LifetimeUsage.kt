package me.rerere.rikkahub.costguards

import kotlinx.serialization.Serializable

/**
 * 单次请求的用量快照（在调用点组装、随事件传递，不落盘）。
 */
data class MessageUsageSnapshot(
    val input: Long = 0,
    val cached: Long = 0,
    val output: Long = 0,
    val cost: Double = 0.0,
)

/**
 * 会话的累计用量（**事件驱动**：每次 API 请求累加一次）。
 *
 * 口径与平台账单一致：**每一次请求**都按它自己的完整输入计费 —— 一轮里的多步工具调用
 * （工具调用循环）各自算一次。
 *
 * 为什么不再"扫消息聚合"：一次回复里的多步请求**共用同一条 assistant 消息**，usage 被后来的
 * 请求覆盖式写入（实测某条消息内含 43 次工具调用，却只留下一份 usage），事后扫描只能看到最后一次
 * → 总量少算数倍（实测约 7.6 倍）。主流客户端（Cursor / Cline 一类）都是"响应带 usage 就当场累加"，
 * 本实现与其一致。
 *
 * 口径边界：这是「应用实际收到的 usage 之和」，不含失败请求与不挂消息的辅助调用
 * （标题生成 / OCR / 记忆抽取等），不等于平台账单，用于量级对账。
 */
@Serializable
data class LifetimeUsage(
    val inputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double = 0.0,
    /** 已计入的请求数（每次请求 +1） */
    val turns: Int = 0,
)

/**
 * 累加一次请求的用量（纯函数，便于单测）。
 *
 * 命中量按输入量收敛：语义上"命中"是输入的子集，个别 provider / 中转会报出 `cached > prompt`，
 * 直接累加会把命中率抬到 100% 以上（口径失真）。
 */
fun accumulateLifetimeUsage(
    prev: LifetimeUsage,
    usage: MessageUsageSnapshot,
): LifetimeUsage {
    val nextInput = (prev.inputTokens + usage.input).coerceAtLeast(0)
    return prev.copy(
        inputTokens = nextInput,
        cachedTokens = (prev.cachedTokens + usage.cached).coerceAtLeast(0).coerceAtMost(nextInput),
        outputTokens = (prev.outputTokens + usage.output).coerceAtLeast(0),
        costUsd = (prev.costUsd + usage.cost).coerceAtLeast(0.0),
        turns = prev.turns + 1,
    )
}
