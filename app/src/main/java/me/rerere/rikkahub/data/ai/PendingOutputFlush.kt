package me.rerere.rikkahub.data.ai

/**
 * Holds the latest coalesced output snapshot until a production delivery boundary.
 * The consuming callback runs in the caller's coroutine context; cancellation is
 * deliberately propagated rather than converted into an unconditional emit.
 */
internal class PendingOutputFlush<T> {
    private var pending: T? = null

    fun set(value: T) {
        pending = value
    }

    fun clear() {
        pending = null
    }

    suspend fun flush(deliver: suspend (T) -> Unit) {
        val value = pending ?: return
        pending = null
        deliver(value)
    }
}
