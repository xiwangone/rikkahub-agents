package me.rerere.common.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批 4（降噪去重）：采样与节流门的行为。
 *
 * 两个门都是纯 Kotlin、无 Android 依赖，所以单测直接跑在 JVM 上；
 * 调用点（流式 onEvent、ASR 丢帧、隧道周期重试）接线本身需在集成环境
 * 环境验证，见各模块的说明。
 */
class LogSamplingTest {

    @Test
    fun `sampling gate logs the first event and every Nth event`() {
        val gate = SamplingGate(100)
        assertTrue(gate.shouldLog()) // 1st
        repeat(98) { assertFalse(gate.shouldLog()) } // 2..99
        assertTrue(gate.shouldLog()) // 100th
        repeat(99) { assertFalse(gate.shouldLog()) } // 101..199
        assertTrue(gate.shouldLog()) // 200th
        assertEquals(200, gate.totalSeen)
    }

    @Test
    fun `sampling gate with every 1 logs every event`() {
        val gate = SamplingGate(1)
        repeat(5) { assertTrue(gate.shouldLog()) }
        assertEquals(5, gate.totalSeen)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sampling gate rejects every below 1`() {
        SamplingGate(0)
    }

    @Test
    fun `throttle gate logs the first call immediately`() {
        var now = 1_000L
        val gate = ThrottleGate(5_000L, clock = { now })
        assertTrue(gate.shouldLog())
        assertEquals(0, gate.takeSuppressedCount())
    }

    @Test
    fun `throttle gate suppresses calls inside the window and counts them`() {
        var now = 1_000L
        val gate = ThrottleGate(5_000L, clock = { now })
        assertTrue(gate.shouldLog())

        now = 1_001L
        assertFalse(gate.shouldLog())
        now = 3_000L
        assertFalse(gate.shouldLog())
        now = 5_999L
        assertFalse(gate.shouldLog())
        assertEquals(3, gate.takeSuppressedCount())
        // 读出后清零：不重复计入下个窗口。
        assertEquals(0, gate.takeSuppressedCount())
    }

    @Test
    fun `throttle gate logs again once the window passes and reports suppressed count`() {
        var now = 0L
        val gate = ThrottleGate(5_000L, clock = { now })
        assertTrue(gate.shouldLog())

        now = 2_000L
        assertFalse(gate.shouldLog())
        now = 4_000L
        assertFalse(gate.shouldLog())

        // 窗口边界（含）放行：上次放行在 0，5000 - 0 >= 5000。
        now = 5_000L
        assertTrue(gate.shouldLog())
        assertEquals(2, gate.takeSuppressedCount())

        // 新窗口从 5000 起算：9999 仍抑制，10000 放行。
        now = 9_999L
        assertFalse(gate.shouldLog())
        now = 10_000L
        assertTrue(gate.shouldLog())
        assertEquals(1, gate.takeSuppressedCount())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throttle gate rejects non-positive interval`() {
        ThrottleGate(0L)
    }
}
