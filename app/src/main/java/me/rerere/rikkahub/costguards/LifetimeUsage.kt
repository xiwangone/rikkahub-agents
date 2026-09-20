package me.rerere.rikkahub.costguards

import kotlinx.serialization.Serializable

/**
 * 单条消息的用量快照。**仅存在于内存**（会话级基线），不写入持久化 ——
 * 长会话消息数以千计，落盘会让 datastore 每轮全量重写变大变慢。
 */
data class MessageUsageSnapshot(
    val input: Long = 0,
    val cached: Long = 0,
    val output: Long = 0,
    val cost: Double = 0.0,
)

/**
 * 一次请求的用量指纹（消息 id + 三个计数）。
 *
 * 为什么需要它：**一次回复里的多步工具调用会发出多次请求，但共用同一条 assistant 消息**
 * （实测某条消息内含 43 次工具调用，却只有一份 usage），usage 被后来的请求覆盖式写入。
 * 只按消息 id 结算会丢掉中间每一步（少算一个数量级）；按指纹结算则每一次请求各计一次，
 * 与平台「每次请求都按完整输入计费」的口径一致。
 */
fun usageFingerprint(messageId: String, snapshot: MessageUsageSnapshot): String =
    "$messageId|${snapshot.input}|${snapshot.cached}|${snapshot.output}|${snapshot.cost}"

/**
 * 会话的累计用量（与上下文压缩解耦）。
 *
 * 背景：「累计消费」应当对齐平台账单语义（每次请求按完整输入计费），且**只增不减** ——
 * 而 `TokenBudgetTracker.aggregate()` 只遍历「当前存留消息」，压缩会把前缀折叠成摘要，
 * 被折叠消息的 usage 随之消失，统计因此缩水；且它每条消息只算一次，会漏掉同一条消息上的多步请求。
 *
 * 算法（按请求指纹结算，见 [settleLifetimeUsage]）：调用方持有一份**内存集**，
 * 记录所有已计入的 [usageFingerprint]；每次会话变化只把**没见过的指纹**计入：
 * - 新请求（同一消息上的下一步，或新消息）→ 指纹不同 → 计入该请求的完整用量；
 * - 同一次请求被重复观察（流式多次上报同一 usage）→ 指纹相同 → 去重，不会重复计入；
 * - 消息被压缩 / 删除 → 已计入部分**保留**，累计不缩水。
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
    /** 已计入的请求数（每个新指纹 +1） */
    val turns: Int = 0,
    /**
     * 是否已建立指纹基线。首次见到某会话（含旧版本遗留记录）时先置位、只登记指纹不累加，
     * 避免把历史请求当成「新增」重复计入；之后才按新指纹结算。
     */
    val hasBaseline: Boolean = false,
)

/**
 * 初次见到会话（或旧版本遗留记录）时的累计起点：把当前每条消息的用量整体登记为基线，只登记不累加。
 */
fun baselineLifetimeUsage(current: Map<String, MessageUsageSnapshot>): LifetimeUsage {
    val sumInput = current.values.sumOf { it.input }
    return LifetimeUsage(
        inputTokens = sumInput,
        cachedTokens = current.values.sumOf { it.cached }.coerceAtMost(sumInput),
        outputTokens = current.values.sumOf { it.output },
        costUsd = current.values.sumOf { it.cost },
        turns = current.size,
        hasBaseline = true,
    )
}

/**
 * 结算一次会话用量（纯函数，便于单测）。
 *
 * @param prev 已持久化的累计（必须已建立基线，即 `prev.hasBaseline == true`）
 * @param seen 已计入的 [usageFingerprint] 集合（内存）
 * @param current 当前会话「消息 id → usage」快照（调用方已按 selectIndex 取当前分支）
 * @return 新的累计值；没有新指纹时原样返回 [prev]
 */
fun settleLifetimeUsage(
    prev: LifetimeUsage,
    seen: Set<String>,
    current: Map<String, MessageUsageSnapshot>,
): LifetimeUsage {
    var deltaInput = 0L
    var deltaCached = 0L
    var deltaOutput = 0L
    var deltaCost = 0.0
    var added = 0
    current.forEach { (id, snapshot) ->
        if (usageFingerprint(id, snapshot) in seen) return@forEach
        // 每个新指纹 = 一次新请求 → 计入该次请求的完整用量（不是与上一次的差值）
        deltaInput += snapshot.input
        deltaCached += snapshot.cached
        deltaOutput += snapshot.output
        deltaCost += snapshot.cost
        added++
    }

    if (added == 0) return prev

    val nextInput = (prev.inputTokens + deltaInput).coerceAtLeast(0)
    return prev.copy(
        inputTokens = nextInput,
        // 命中量是输入量的子集：既抹平历史脏值，也挡住 provider 报出的 cached > prompt
        cachedTokens = (prev.cachedTokens + deltaCached).coerceAtLeast(0).coerceAtMost(nextInput),
        outputTokens = (prev.outputTokens + deltaOutput).coerceAtLeast(0),
        costUsd = (prev.costUsd + deltaCost).coerceAtLeast(0.0),
        turns = prev.turns + added,
    )
}
