package me.rerere.rikkahub.costguards

import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.model.Conversation

/**
 * Phase 15 — pure-function token aggregator over a [Conversation]. Walks the currently-
 * selected branch (one message per node, picked via `selectIndex`), sums every
 * non-null [TokenUsage], and reports the running totals.
 *
 * The aggregator is intentionally read-only — it never mutates the conversation or the
 * assistant settings. The auto-stop integration (live indicator pill in chat header,
 * GenerationHandler cancellation when over hard cap) is the Phase 15.5 follow-up that
 * needs deeper hooks into the streaming pipeline.
 *
 * v1 surface:
 *   - LLM-callable tool (`check_token_usage`) returns the totals + budget status so the
 *     model can self-stop or notify the user.
 *   - Future Compose / Telegram surfaces collect the same numbers.
 *
 * Budget classification (v1.1 — 2026-08-07):
 *   classify() uses perMessageMax (single-message peak = real context-window size)
 *   instead of totalTokens (sum of all turns with context overlap → inflated).
 *   totalTokens is still tracked for informational display but is NOT used for caps.
 */
object TokenBudgetTracker {
    data class Totals(
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedTokens: Long,
        val totalTokens: Long,
        val perMessageMax: Long,
        val messageCount: Int,
        /** 最近一条消息（本轮）的缓存命中率，0~1；无数据为 0 */
        val lastRequestHitPct: Double = 0.0,
    )

    enum class BudgetStatus {
        UNDER_SOFT,
        WARN, // crossed soft, below hard
        OVER_HARD, // crossed hard
        NO_BUDGET, // no caps configured
    }

    data class Snapshot(
        val totals: Totals,
        val softCap: Int?,
        val hardCap: Int?,
        val status: BudgetStatus,
    )

    fun aggregate(conversation: Conversation): Totals {
        var input = 0L
        var output = 0L
        var cached = 0L
        var total = 0L
        var perMax = 0L
        var count = 0
        var lastHitPct = 0.0
        for (node in conversation.messageNodes) {
            val msg = node.messages.getOrNull(node.selectIndex) ?: continue
            val usage = msg.usage ?: continue
            input += usage.promptTokens.toLong()
            output += usage.completionTokens.toLong()
            cached += usage.cachedTokens.toLong()
            val totalThis =
                (
                    usage.totalTokens.takeIf { it > 0 }
                        ?: (usage.promptTokens + usage.completionTokens)
                ).toLong()
            total += totalThis
            if (totalThis > perMax) perMax = totalThis
            count++
            // 最近一条（按节点遍历顺序最后出现）的命中率 = 本轮命中率
            if (usage.promptTokens > 0) {
                lastHitPct =
                    usage.cachedTokens.toDouble() / usage.promptTokens.toDouble()
            }
        }
        return Totals(
            inputTokens = input,
            outputTokens = output,
            cachedTokens = cached,
            totalTokens = total,
            perMessageMax = perMax,
            messageCount = count,
            lastRequestHitPct = lastHitPct,
        )
    }

    fun classify(
        totals: Totals,
        softCap: Int?,
        hardCap: Int?,
    ): BudgetStatus {
        // No budget configured → no-budget. Spec calls this "off"; tool surface shows
        // numbers but doesn't recommend action.
        if (softCap == null && hardCap == null) return BudgetStatus.NO_BUDGET
        // Use perMessageMax (single-message peak = real context-window size)
        // instead of totalTokens (sum of all turns — includes repeated context).
        val actual = totals.perMessageMax
        if (hardCap != null && actual >= hardCap) return BudgetStatus.OVER_HARD
        if (softCap != null && actual >= softCap) return BudgetStatus.WARN
        return BudgetStatus.UNDER_SOFT
    }

    fun snapshot(
        conversation: Conversation,
        softCap: Int?,
        hardCap: Int?,
    ): Snapshot {
        val totals = aggregate(conversation)
        return Snapshot(
            totals = totals,
            softCap = softCap,
            hardCap = hardCap,
            status = classify(totals, softCap, hardCap),
        )
    }
}
