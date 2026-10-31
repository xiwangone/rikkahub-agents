package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-mechanics tests for [OutputRepetitionDetector] (no Android, no coroutines).
 *
 * The two profiles exercised here are the ones GenerationLoop instantiates: the conservative
 * one for answer text, the 8/3/48 one for reasoning deltas.
 */
class OutputRepetitionDetectorTest {

    /** 9 chars: below the default minPeriod (12), so only a multiple-of-9 period can match it. */
    private val loop9 = "我需要检查一下文件"

    /** 15 chars: >= default minPeriod, so the default profile matches it directly. */
    private val loop15 = "0123456789abcde"

    /**
     * Non-periodic filler of the requested length: the running index makes every 10/11-char
     * item differ (both in digit width and in the digit itself).
     */
    private fun filler(targetChars: Int): String {
        val sb = StringBuilder()
        var i = 0
        while (sb.length < targetChars) {
            sb.append("行").append(i).append("：内容互不相同；")
            i++
        }
        return sb.toString()
    }

    /**
     * A 210-char exactly-periodic tail must be caught by the default profile.
     *
     * Regression guard for the first-check floor: the gate used to be `maxPeriod * minRepeats`
     * (800 chars), so a loop that appears early in a step went unexamined until char 800 even
     * though every span it tests needs far less text.
     */
    @Test
    fun `default profile catches a long loop before the old 800-char floor`() {
        val detector = OutputRepetitionDetector()
        assertTrue(detector.feed(loop15.repeat(14)))
        assertTrue(detector.isDetected)
    }

    @Test
    fun `default profile leaves ordinary varied text alone`() {
        val detector = OutputRepetitionDetector()
        val text = filler(1200)
        var offset = 0
        while (offset < text.length) {
            val delta = text.substring(offset, minOf(offset + 100, text.length))
            assertFalse(detector.feed(delta))
            offset += 100
        }
        assertFalse(detector.isDetected)
    }

    /**
     * On identical content, both profiles get exactly one look (the input is a single delta that
     * clears both check intervals) — and only the reasoning profile fires. The loop run is 63
     * chars of a 9-char phrase: too short for the text profile, which needs 4 copies of some
     * candidate period >= 12 (the nearest multiple, 18, would need 72 chars).
     */
    @Test
    fun `reasoning profile catches a short loop that the text profile misses`() {
        val content = filler(144) + loop9.repeat(7)
        val reasoning = OutputRepetitionDetector(minPeriod = 8, minRepeats = 3, minCheckIntervalChars = 48)
        val text = OutputRepetitionDetector()
        assertTrue(reasoning.feed(content))
        assertFalse(text.feed(content))
    }

    /** Once flagged, the detector stays flagged for the rest of its (per-step) lifetime. */
    @Test
    fun `detection is sticky and later deltas keep reporting it`() {
        val detector = OutputRepetitionDetector()
        assertTrue(detector.feed(loop15.repeat(14)))
        assertTrue(detector.feed("x"))
        assertTrue(detector.feed(""))
    }

    /**
     * The trip record names the matching segment size — the one number that separates a real echo
     * loop from a long-context run that legitimately re-quotes a fixed phrase, and therefore the
     * number the early-stop log line has to carry.
     *
     * 期望值随 `repeatsFor` 的分级变化（2026-10-29 引入）：period=15 < 16 → `minRepeats + 1`，
     * 默认 minRepeats=4 ⇒ repeats=5。
     */
    @Test
    fun `trip detail names the matched segment size`() {
        val detector = OutputRepetitionDetector()
        assertNull(detector.tripDetail())
        assertTrue(detector.feed(loop15.repeat(14)))
        assertEquals("period=15 repeats=5", detector.tripDetail())
    }

    /**
     * Documents how cheap a reasoning-stream trip is: the 8/3/48 profile's first possible check
     * lands at 48 accumulated chars, and 48 chars of one repeated character is already an 8x3 match.
     * A blank/punctuation run of that size ends the whole turn, so this is the shape to look for
     * when a "runaway repetition" log turns out to be a false positive.
     */
    @Test
    fun `reasoning profile trips on a whitespace-only run`() {
        val detector = OutputRepetitionDetector(minPeriod = 8, minRepeats = 3, minCheckIntervalChars = 48)
        assertTrue(detector.feed(" ".repeat(48)))
        // period=8 < 16 → repeatsFor = minRepeats + 1 = 4（2026-10-29 的分级软化）
        assertEquals("period=8 repeats=4", detector.tripDetail())
    }

    /**
     * 分级规则（2026-10-29 `repeatsFor`）：**周期越长要求越少**。
     *
     * 短周期（<16）在正常写作/思考里本来就会碰撞（路径、标识符、数字）→ 要求 `minRepeats + 1`；
     * 中段（16–47）→ `minRepeats`；长周期（≥48）连续出现几乎不可能是正常输出 → `minRepeats - 1`（最少 2）。
     * 这三档此前只有两条用例间接覆盖，这里直接固化，避免以后再改分级时无声偏移。
     */
    @Test
    fun `repeat threshold scales with the matched period`() {
        // 短周期：period=8 → 3 + 1 = 4 次
        val short = OutputRepetitionDetector(minPeriod = 8, minRepeats = 3, minCheckIntervalChars = 8)
        assertTrue(short.feed("z".repeat(40)))
        assertEquals("period=8 repeats=4", short.tripDetail())

        // 中周期：period=20（16 ≤ 20 < 48）→ 3 次
        val mid = OutputRepetitionDetector(minPeriod = 20, minRepeats = 3, minCheckIntervalChars = 20)
        assertTrue(mid.feed("y".repeat(80)))
        assertEquals("period=20 repeats=3", mid.tripDetail())

        // 长周期：period=60（≥48）→ 3 - 1 = 2 次
        val long = OutputRepetitionDetector(minPeriod = 60, minRepeats = 3, minCheckIntervalChars = 60)
        assertTrue(long.feed("w".repeat(180)))
        assertEquals("period=60 repeats=2", long.tripDetail())
    }
}
