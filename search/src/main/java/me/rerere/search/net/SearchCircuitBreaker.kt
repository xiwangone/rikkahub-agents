package me.rerere.search.net

/**
 * In-memory circuit breaker for a single keyless search engine. State lives only
 * in the holding object for the process lifetime - no disk, no cache. Thread-safe:
 * search() runs on Dispatchers.IO and calls may overlap.
 *
 * States: CLOSED (normal) -> OPEN (after [failureThreshold] consecutive failures) ->
 * HALF_OPEN (once [cooldownMillis] has elapsed, exactly one probe is let through) ->
 * CLOSED (probe succeeds) or OPEN (probe fails, cooldown restarts).
 *
 * A probe that never reports back (cancelled, or fails with something that is not
 * recorded) must not strand the breaker in HALF_OPEN forever: after
 * [halfOpenTimeoutMillis] a new probe is allowed, so the breaker self-heals.
 */
class SearchCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val cooldownMillis: Long = 60_000L,
    private val halfOpenTimeoutMillis: Long = 60_000L,
    private val now: () -> Long = { System.currentTimeMillis() }, // injectable for tests
) {
    private enum class State { CLOSED, OPEN, HALF_OPEN }

    private var state = State.CLOSED
    private var failureCount = 0
    private var lastFailureAt = 0L
    private var halfOpenSince = 0L

    @Synchronized
    fun canAttempt(): Boolean = when (state) {
        State.CLOSED -> true
        State.HALF_OPEN -> {
            // A probe is already outstanding, unless it has been outstanding longer
            // than the timeout without reporting back: then it is treated as lost
            // and a fresh probe is allowed, so the breaker cannot strand in HALF_OPEN.
            if (now() - halfOpenSince >= halfOpenTimeoutMillis) {
                halfOpenSince = now()
                true
            } else {
                false
            }
        }
        State.OPEN -> {
            if (now() - lastFailureAt >= cooldownMillis) {
                state = State.HALF_OPEN
                halfOpenSince = now()
                true
            } else {
                false
            }
        }
    }

    @Synchronized
    fun remainingCooldownMillis(): Long {
        if (state == State.CLOSED) return 0L
        val remaining = cooldownMillis - (now() - lastFailureAt)
        return remaining.coerceAtLeast(0L)
    }

    @Synchronized
    fun recordSuccess() {
        state = State.CLOSED
        failureCount = 0
        halfOpenSince = 0L
    }

    @Synchronized
    fun recordFailure() {
        failureCount += 1
        lastFailureAt = now()
        if (state == State.HALF_OPEN || failureCount >= failureThreshold) {
            state = State.OPEN
            halfOpenSince = 0L
        }
    }
}
