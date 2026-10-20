package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertFalse
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
}
