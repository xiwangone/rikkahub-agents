package me.rerere.rikkahub.costguards

import kotlinx.serialization.Serializable

/**
 * 会话的累计用量（与上下文压缩解耦）。
 *
 * 背景：`TokenBudgetTracker.aggregate()` 遍历的是「当前存留消息」，压缩会把前缀折叠成摘要，
 * 被折叠消息的 usage 随之消失，统计因此缩水。而「累计消费」应当只增不减（对齐平台账单语义）。
 *
 * 算法：会话每次变化时 `累计 += max(0, 当前聚合值 − 上次记录的聚合值)`。
 * 压缩瞬间当前值骤降 → 增量为 0（累计不动）；此后逐轮上涨 → 正常累加。无需跟踪消息 id。
 *
 * 口径边界：这是「应用实际收到的 usage 之和」，不含失败请求与不挂消息的辅助调用，
 * 不等于平台账单，用于量级对账。
 */
@Serializable
data class LifetimeUsage(
    val inputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double = 0.0,
    /** 已计入的轮次数（有 usage 的消息数增量） */
    val turns: Int = 0,
    /** 上一次观察到的聚合值，仅用于计算增量，不展示 */
    val lastInput: Long = 0,
    val lastCached: Long = 0,
    val lastOutput: Long = 0,
    val lastCost: Double = 0.0,
)
