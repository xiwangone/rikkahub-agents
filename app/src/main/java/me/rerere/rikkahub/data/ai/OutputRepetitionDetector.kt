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

    /** True once runaway repetition has been observed. Sticky for the lifetime of the instance. */
    val isDetected: Boolean get() = detected

    /**
     * Feed one streamed delta. Returns true when this call observed runaway repetition (the
     * caller should stop the stream). Cheap: most calls only append and counters.
     */
    fun feed(delta: String): Boolean {
        if (detected) return true
        if (delta.isEmpty()) return false

        buffer.append(delta)
        if (buffer.length > bufferLimitChars) {
            buffer.delete(0, buffer.length - bufferLimitChars / 2)
        }

        sinceLastCheck += delta.length
        val enoughText = buffer.length >= maxPeriod * minRepeats
        if (!enoughText || sinceLastCheck < minCheckIntervalChars) return false
        sinceLastCheck = 0

        detected = hasRunawayTail(buffer)
        return detected
    }

    /**
     * True when the buffer tail is one segment repeated [minRepeats] times, for any candidate
     * period in range. Compares the trailing `period * minRepeats` chars against a shift of
     * itself by `period` — a pure character-level check, no regex and no allocations.
     */
    private fun hasRunawayTail(text: CharSequence): Boolean {
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
            if (identical) return true
        }
        return false
    }
}
