package me.rerere.rikkahub.ui.pages.log

import me.rerere.rikkahub.utils.LogRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 眼睛显隐的状态与显示决策（从 AppLogPage 抽出的纯单元，不依赖 Compose 运行时）：
 *  - 默认全部未显隐，按时间戳逐条切换，清空后重置
 *  - 未显隐显示脱敏文本，显隐显示原文
 */
class LogRevealStateTest {

    @Test
    fun `default is hidden`() {
        val state = LogRevealState()
        assertFalse(state.isRevealed(1000L))
        assertFalse(state.isRevealed(2000L))
    }

    @Test
    fun `toggle reveals then hides again`() {
        val revealed = LogRevealState().toggle(1000L)
        assertTrue(revealed.isRevealed(1000L))
        val hidden = revealed.toggle(1000L)
        assertFalse(hidden.isRevealed(1000L))
    }

    @Test
    fun `toggle does not mutate the previous state`() {
        val state = LogRevealState()
        val next = state.toggle(1000L)
        assertFalse("previous snapshot must stay hidden", state.isRevealed(1000L))
        assertTrue(next.isRevealed(1000L))
    }

    @Test
    fun `toggling one entry does not affect others`() {
        val state = LogRevealState().toggle(1000L)
        assertTrue(state.isRevealed(1000L))
        assertFalse(state.isRevealed(2000L))
        val both = state.toggle(2000L)
        assertTrue(both.isRevealed(1000L))
        assertTrue(both.isRevealed(2000L))
        val onlySecond = both.toggle(1000L)
        assertFalse(onlySecond.isRevealed(1000L))
        assertTrue(onlySecond.isRevealed(2000L))
    }

    @Test
    fun `clear resets all`() {
        val state = LogRevealState().toggle(1000L).toggle(2000L)
        val cleared = state.clear()
        assertFalse(cleared.isRevealed(1000L))
        assertFalse(cleared.isRevealed(2000L))
        // 原快照不受影响
        assertTrue(state.isRevealed(1000L))
    }

    @Test
    fun `hidden shows masked text`() {
        val raw = "x-goog-api-key: AIzaSyD1234567890abcdefghij1234567890"
        val masked = LogRedactor.maskText(raw)
        assertFalse("test setup: secret must be masked", masked.contains("AIzaSyD1234567890abcdefghij1234567890"))
        assertEquals(masked, displayLogText(raw, revealed = false))
    }

    @Test
    fun `revealed shows original text`() {
        val raw = "x-goog-api-key: AIzaSyD1234567890abcdefghij1234567890"
        assertEquals(raw, displayLogText(raw, revealed = true))
    }

    @Test
    fun `repeat suffix is masked when hidden and kept when revealed`() {
        val raw = "token=sk-abcdef123456 (×3)"
        assertEquals(LogRedactor.maskText(raw), displayLogText(raw, revealed = false))
        assertEquals(raw, displayLogText(raw, revealed = true))
    }

    @Test
    fun `plain text is unchanged either way`() {
        val raw = "model=gpt-4o-mini, tokens=1024"
        assertEquals(raw, displayLogText(raw, revealed = false))
        assertEquals(raw, displayLogText(raw, revealed = true))
    }
}
