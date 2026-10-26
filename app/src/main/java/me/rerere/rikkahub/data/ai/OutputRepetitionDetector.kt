package me.rerere.rikkahub.data.ai

/**
 * Streaming-output repetition detector.
 *
 * [Why] The tool-call loop guard in `GenerationLoop` only watches tool invocations; it is blind
 * to a model that starts repeating its own prose or reasoning ("echo loops"). Those loops burn
 * tokens and wall-clock time with no progress. This detector is the runtime backstop for that
 * case: it inspects the streamed deltas and reports when the tail of the output has become a
 * literal repeat of one short segment.
 *
 * [How] Text is accumulated into a bounded buffer. After every [minCheckIntervalChars] of new
 * input, the tail is tested against every candidate period in [minPeriod]..[maxPeriod]: if the
 * last `period * minRepeats` characters consist of the same `period`-long segment repeated
 * [minRepeats] times, the output is considered runaway-repetitive.
 *
 * [Safety] Deliberately conservative so ordinary writing never trips it:
 * - a period must be at least [minPeriod] chars, so short overlaps ("haha", "嗯嗯", `....`) are
 *   never flagged;
 * - four full repeats of that segment are required, which ordinary prose does not produce;
 * - the check costs nothing until enough text has accumulated.
 *
 * The caller decides what to do with a positive result; this class only reports. It is
 * intentionally stateless beyond the buffer and one sticky flag, so it can be created per turn.
 */
internal class OutputRepetitionDetector(
    private val minPeriod: Int = 12,
    private val maxPeriod: Int = 200,
    private val minRepeats: Int = 4,
    private val minCheckIntervalChars: Int = 200,
    private val bufferLimitChars: Int = 8_000,
) {
    private val buffer = StringBuilder()
    private var sinceLastCheck = 0
    private var detected = false
    private var matchedPeriod: Int? = null

    /** True once runaway repetition has been observed. Sticky for the lifetime of the instance. */
    val isDetected: Boolean get() = detected

    /**
     * Details of the check that tripped, or null while nothing has tripped (`period=42 repeats=4`
     * reads as "a 42-char segment seen 4 times in a row").
     *
     * [Why] Without the matched period the log line cannot distinguish a real echo loop from a false
     * positive on a long-context run that legitimately re-quotes the same fixed phrase; the size of
     * the matching segment is the one number that separates them.
     */
    fun tripDetail(): String? = matchedPeriod?.let { "period=$it repeats=$minRepeats" }

    /**
     * Feed one streamed delta. Returns true when this call observed runaway repetition. The caller
     * still forwards the delta that tripped (it is content the model already produced) and ends the
     * stream on the next one — see `GenerationAgentRun.interceptStream`. Cheap: most calls only
     * append and counters.
     */
    fun feed(delta: String): Boolean {
        if (detected) return true
        if (delta.isEmpty()) return false

        buffer.append(delta)
        if (buffer.length > bufferLimitChars) {
            buffer.delete(0, buffer.length - bufferLimitChars / 2)
        }

        sinceLastCheck += delta.length
        // Gate on the *smallest* candidate span, not the largest. hasRunawayTail already skips
        // every period whose span exceeds the buffer (`if (length < span) continue`), so testing
        // is never done against a span the buffer cannot supply — `maxPeriod * minRepeats` here
        // only postponed the first possible check from ~200 chars (minCheckIntervalChars, the
        // other half of this AND) to 800 chars. A short-period loop starting at char 300 ran
        // unchecked through char 800.
        val enoughText = buffer.length >= minPeriod * minRepeats
        if (!enoughText || sinceLastCheck < minCheckIntervalChars) return false
        sinceLastCheck = 0

        val period = hasRunawayTail(buffer)
        if (period != null) matchedPeriod = period
        detected = period != null
        return detected
    }

    /**
     * The period whose trailing `period * minRepeats` chars are one segment repeated
     * [minRepeats] times, or null when no candidate period in range matches. Compares the tail
     * against a shift of itself by `period` — a pure character-level check, no regex and no allocations.
     */
    private fun hasRunawayTail(text: CharSequence): Int? {
        val length = text.length
        for (period in minPeriod..maxPeriod) {
            val span = period * minRepeats
            if (length < span) continue
            val start = length - span
            var identical = true
            var i = 0
            while (i < span - period) {
                if (text[start + i] != text[start + i + period]) {
                    identical = false
                    break
                }
                i++
            }
            if (identical) return period
        }
        return null
    }
}
