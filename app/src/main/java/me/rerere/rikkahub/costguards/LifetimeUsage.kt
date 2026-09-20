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
 * 会话的累计用量（与上下文压缩解耦）。
 *
 * 背景：`TokenBudgetTracker.aggregate()` 遍历的是「当前存留消息」，压缩会把前缀折叠成摘要，
 * 被折叠消息的 usage 随之消失，统计因此缩水。而「累计消费」应当只增不减（对齐平台账单语义）。
 *
 * 算法（按消息粒度结算，见 [settleLifetimeUsage]）：调用方持有一份**内存基线**
 * （消息 id → 该消息已计入的用量），每次会话变化只结算差值：
 * - 新消息 → 全量计入；
 * - 同一条消息的 usage 被改写（重试 / 流式补写）→ 只计入差值（可为负，抵消此前误计）；
 * - 消息被压缩 / 删除（基线里消失）→ 已计入部分**保留**，累计不缩水。
 *
 * 为什么不再是「聚合值差分 + max(0, Δ)」：聚合值会因重试、同一消息 usage 多次改写、分支切换而
 * **上下波动**，而 `max(0, Δ)` 把下降吞掉、只留上涨 ⇒ **重复计入**（实测某会话 cached 多算 70 万、
 * output 多算 4.9 万，把「命中率」抬到 124%）。按消息粒度结算没有这个缺口。
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
    /** 已计入的轮次数（新消息出现时 +1） */
    val turns: Int = 0,
    /**
     * 是否已建立消息级基线。首次见到某会话（含旧版本遗留记录）时先置位并只登记基线、不累加，
     * 避免把历史消息当成「新增」重复计入；之后才按差值结算。
     */
    val hasBaseline: Boolean = false,
)

/**
 * 初次见到会话（或旧版本遗留记录）时的累计起点：把当前存留消息整体视作基线，只登记不累加。
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
 * @param baseline 上一次结算时各消息的已计入用量（内存基线）
 * @param current 当前会话「消息 id → usage」快照（调用方已按 selectIndex 取当前分支）
 * @return 新的累计值；无变化时原样返回 [prev]
 */
fun settleLifetimeUsage(
    prev: LifetimeUsage,
    baseline: Map<String, MessageUsageSnapshot>,
    current: Map<String, MessageUsageSnapshot>,
): LifetimeUsage {
    var deltaInput = 0L
    var deltaCached = 0L
    var deltaOutput = 0L
    var deltaCost = 0.0
    var newMessages = 0
    current.forEach { (id, now) ->
        val before = baseline[id]
        if (before == null) {
            deltaInput += now.input
            deltaCached += now.cached
            deltaOutput += now.output
            deltaCost += now.cost
            newMessages++
        } else {
            // 同一消息的 usage 被改写：只结算差值（负差抵消此前误计）
            deltaInput += now.input - before.input
            deltaCached += now.cached - before.cached
            deltaOutput += now.output - before.output
            deltaCost += now.cost - before.cost
        }
    }

    val noValueChange =
        deltaInput == 0L && deltaCached == 0L && deltaOutput == 0L && deltaCost == 0.0
    if (noValueChange && newMessages == 0) {
        return prev
    }

    val nextInput = (prev.inputTokens + deltaInput).coerceAtLeast(0)
    return prev.copy(
        inputTokens = nextInput,
        // 命中量是输入量的子集：既抹平历史脏值，也挡住 provider 报出的 cached > prompt
        cachedTokens = (prev.cachedTokens + deltaCached).coerceAtLeast(0).coerceAtMost(nextInput),
        outputTokens = (prev.outputTokens + deltaOutput).coerceAtLeast(0),
        costUsd = (prev.costUsd + deltaCost).coerceAtLeast(0.0),
        turns = prev.turns + newMessages,
    )
}
