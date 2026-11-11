package me.rerere.rikkahub.costguards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 上下文窗口口径的回归锁。
 *
 * 背景：`per_message_max` 曾被当成"上下文窗口大小"（注释与工具描述都这么写），
 * 而当设置里没填窗口时，输出里干脆没有这个字段 —— 两者叠加会让人把
 * "缺失" 补成 "0"，从而误判上下文快满。这里锁住行为：未填时显式 unavailable。
 */
class CostGuardToolsTest {
    @Test
    fun `window unset reports unavailable without a token count`() {
        val info = resolveContextWindowInfo(0L)
        assertNull(info.tokens)
        assertEquals("unavailable", info.source)
    }

    @Test
    fun `configured window is reported as settings source`() {
        val info = resolveContextWindowInfo(1_000_000L)
        assertEquals(1_000_000L, info.tokens)
        assertEquals("settings", info.source)
    }
}
