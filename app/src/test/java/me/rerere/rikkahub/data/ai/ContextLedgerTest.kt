package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.tools.ToolUsageTracker
import me.rerere.rikkahub.data.ai.tools.local.NULL_CONTEXT
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 常驻内容账本：只记长度、分项与总量自洽、受同一开关控制（关 = 零写入）。
 *
 * 跑在未初始化的 [NULL_CONTEXT] 上：内部 SharedPreferences 触碰都被 runCatching 包住，
 * 宿主 JVM 上只是跳过持久化。
 */
class ContextLedgerTest {

    private fun snapshot(
        assistantPrompt: Int = 100,
        memory: Int = 200,
        recentChats: Int = 30,
        toolPrompts: Int = 900,
        addendum: Int = 50,
        stable: Int = 1_000,
        volatile: Int = 280,
        toolCount: Int = 12,
    ) = ContextLedgerSnapshot(
        assistantPrompt = assistantPrompt,
        memory = memory,
        recentChats = recentChats,
        toolPrompts = toolPrompts,
        addendum = addendum,
        stable = stable,
        volatile = volatile,
        toolCount = toolCount,
        atMs = 1L,
    )

    @Before
    fun setUp() {
        ToolUsageTracker.setEnabled(true)
        ContextLedger.clear(NULL_CONTEXT)
    }

    @After
    fun tearDown() {
        ContextLedger.clear(NULL_CONTEXT)
        ToolUsageTracker.setEnabled(false)
    }

    @Test
    fun `totals are the sum of the two sections and tokens are a chars-per-3 estimate`() {
        val snap = snapshot(stable = 1_000, volatile = 280)
        assertEquals(1_280, snap.totalChars)
        assertEquals(426, snap.estTokens) // 1280 / 3 向下取整，口径同工具面报告
    }

    @Test
    fun `empty snapshot stays at zero without dividing by zero`() {
        val empty = ContextLedgerSnapshot()
        assertEquals(0, empty.totalChars)
        assertEquals(0, empty.estTokens)
    }

    @Test
    fun `record then last returns the same breakdown`() {
        ContextLedger.record(NULL_CONTEXT, snapshot())

        val read = ContextLedger.last(NULL_CONTEXT)
        assertEquals(1_000, read?.stable)
        assertEquals(280, read?.volatile)
        assertEquals(900, read?.toolPrompts)
        assertEquals(12, read?.toolCount)
    }

    @Test
    fun `nothing is recorded while stats are disabled`() {
        ToolUsageTracker.setEnabled(false)
        ContextLedger.record(NULL_CONTEXT, snapshot())

        assertNull(ContextLedger.last(NULL_CONTEXT))
    }

    @Test
    fun `clear drops the last snapshot`() {
        ContextLedger.record(NULL_CONTEXT, snapshot())
        ContextLedger.clear(NULL_CONTEXT)
        assertNull(ContextLedger.last(NULL_CONTEXT))
        assertTrue(ContextLedgerSnapshot().totalChars == 0)
    }
}
