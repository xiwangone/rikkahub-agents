package me.rerere.rikkahub.costguards

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage

/** Per-run ledger: persisted history is the baseline, never a new billable request. */
internal class CompletedUsageLedger(initialMessages: List<UIMessage>) {
    private val accounted = initialMessages
        .filter { it.role == MessageRole.ASSISTANT }
        .associate { it.id to it.usage?.withEffectiveTotal() }
        .toMutableMap()

    /** Committed cumulative snapshots may arrive at multiple boundaries; emit only new usage. */
    fun takeDelta(message: UIMessage): TokenUsage? {
        if (message.role != MessageRole.ASSISTANT) return null
        val cumulative = message.usage?.withEffectiveTotal() ?: return null
        val previous = accounted[message.id]
        val currentCost = cumulative.cost
        val previousCost = previous?.cost
        val incurred = if (previous == null) cumulative else TokenUsage(
            promptTokens = (cumulative.promptTokens - previous.promptTokens).coerceAtLeast(0),
            completionTokens = (cumulative.completionTokens - previous.completionTokens).coerceAtLeast(0),
            cachedTokens = (cumulative.cachedTokens - previous.cachedTokens).coerceAtLeast(0),
            totalTokens = (cumulative.totalTokens - previous.totalTokens).coerceAtLeast(0),
            cost = when {
                currentCost == null -> null
                previousCost == null -> currentCost
                else -> (currentCost - previousCost).coerceAtLeast(0.0)
            },
        )
        // Never lower the watermark if a stale snapshot arrives out of order.
        accounted[message.id] = if (previous == null) cumulative else TokenUsage(
            promptTokens = maxOf(previous.promptTokens, cumulative.promptTokens),
            completionTokens = maxOf(previous.completionTokens, cumulative.completionTokens),
            cachedTokens = maxOf(previous.cachedTokens, cumulative.cachedTokens),
            totalTokens = maxOf(previous.totalTokens, cumulative.totalTokens),
            cost = when {
                currentCost == null -> previousCost
                previousCost == null -> currentCost
                else -> maxOf(previousCost, currentCost)
            },
        )
        return incurred.takeUnless {
            it.promptTokens == 0 && it.completionTokens == 0 && it.cachedTokens == 0 &&
                it.totalTokens == 0 && (it.cost ?: 0.0) == 0.0
        }
    }

    private fun TokenUsage.withEffectiveTotal(): TokenUsage = copy(
        totalTokens = totalTokens.takeIf { it > 0 } ?: (promptTokens + completionTokens),
    )
}
