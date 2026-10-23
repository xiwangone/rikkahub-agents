package me.rerere.ai.core

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
    // Provider-reported generation cost in USD (OpenRouter `usage.cost`). Null when the
    // provider doesn't report it. Nullable + defaulted so older persisted messages decode fine.
    val cost: Double? = null,
)

fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    val totalTokens = promptTokens + completionTokens
    val cachedTokens = if (other.cachedTokens > 0) {
        other.cachedTokens
    } else {
        this?.cachedTokens ?: 0
    }
    val cost = other.cost ?: this?.cost
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens,
        cost = cost,
    )
}

/** Add usage from distinct model requests; unlike [merge], this never treats one as a snapshot. */
fun TokenUsage?.sum(other: TokenUsage?): TokenUsage? {
    val left = this
    if (left == null) return other
    if (other == null) return left
    return TokenUsage(
        promptTokens = left.promptTokens + other.promptTokens,
        completionTokens = left.completionTokens + other.completionTokens,
        cachedTokens = left.cachedTokens + other.cachedTokens,
        totalTokens = (left.totalTokens.takeIf { it > 0 } ?: (left.promptTokens + left.completionTokens)) +
            (other.totalTokens.takeIf { it > 0 } ?: (other.promptTokens + other.completionTokens)),
        cost = when {
            left.cost == null -> other.cost
            other.cost == null -> left.cost
            else -> left.cost + other.cost
        },
    )
}
