package me.rerere.rikkahub.service

/**
 * 轮询退避计算。
 *
 * 从 TelegramBotService 提取的纯函数。
 */
/** Capped exponential backoff: 5s, 10s, 20s, 40s, 80s, 120s (capped). */
internal fun computeBackoffMs(consecutiveErrors: Int): Long {
    val base = 5_000L
    val cap = 120_000L
    if (consecutiveErrors <= 0) return base
    val shift = (consecutiveErrors - 1).coerceAtMost(20)
    val computed = base shl shift
    return computed.coerceAtMost(cap)
}
