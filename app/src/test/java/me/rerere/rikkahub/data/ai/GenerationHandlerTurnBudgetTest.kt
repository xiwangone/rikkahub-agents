package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies that the per-turn wall-clock budget is now read from [ToolRuntimeLimits] rather
 * than a deleted compile-time constant, and that the default value is 10 minutes.
 *
 * Full GenerationHandler instantiation requires Android Context + full Koin graph — beyond JVM
 * unit test scope. Instead, we test the invariants that GenerationHandler.kt relies on:
 *  - The runtime holder field exists and is readable/writable.
 *  - The default value is 10 min (per the task override; the spec says 5 min but that was
 *    overridden because the original const was 10 min).
 *  - Writes to the holder are immediately visible (the deleted const had only one value;
 *    the holder can change dynamically).
 */
class GenerationHandlerTurnBudgetTest {

    private var prevBudget: Long = 0L

    @Before
    fun save() { prevBudget = ToolRuntimeLimits.turnBudgetMs }

    @After
    fun restore() { ToolRuntimeLimits.turnBudgetMs = prevBudget }

    @Test
    fun defaultBudget_is10Minutes() {
        // The original hardcoded const in GenerationHandler.kt was 10 min.
        // The task override spec says: "Turn-budget default = 10 minutes, NOT the spec's 5."
        assertEquals(10L * 60L * 1_000L, TermuxDefaults.DEFAULT_TURN_BUDGET_MS)
    }

    @Test
    fun toolRuntimeLimits_initialDefault_matches10Minutes() {
        // On a fresh JVM process the holder initialises from TermuxDefaults.DEFAULT_TURN_BUDGET_MS.
        // We read prevBudget (captured before any test runs) to check what the holder started at.
        assertEquals(TermuxDefaults.DEFAULT_TURN_BUDGET_MS, prevBudget)
    }

    @Test
    fun turnBudget_canBeChangedAtRuntime() {
        val newBudget = 5L * 60_000L
        ToolRuntimeLimits.turnBudgetMs = newBudget
        assertEquals(newBudget, ToolRuntimeLimits.turnBudgetMs)
        assertNotEquals(prevBudget, ToolRuntimeLimits.turnBudgetMs)
    }

    @Test
    fun turnBudget_clampedValueFitsInHolder() {
        // Verify that a clamped value from TermuxDefaults survives a round-trip through
        // the holder without loss (no cast / truncation hazard).
        val clamped = TermuxDefaults.clampTurnBudgetMs(3_700_000L) // over max → should snap
        ToolRuntimeLimits.turnBudgetMs = clamped
        assertEquals(clamped, ToolRuntimeLimits.turnBudgetMs)
        assertEquals(TermuxDefaults.MAX_TURN_BUDGET_MS, ToolRuntimeLimits.turnBudgetMs)
    }

    // ── 收尾总结不变量（预算到点后「至少有一次无工具总结机会」）─────────────────
    //
    // GenerationAgentRun 本体需要 Android Context + 完整 Koin 图，JVM 单测够不着；
    // 这里钉住它依赖的纯函数：cap 起算点、兜底判定、宽限窗口数学、terminalSummary 标志。

    @Test
    fun wrapUpStreamCap_firstChunkNotArrived_neverExceeded() {
        // 首包未到时上限不触发：prefill 再长也不吃总结窗口，首包阶段只由空闲看门狗兜底。
        val start = 1_000_000L
        assertFalse(isStreamCapExceeded(true, start + 200_000L, start, 0L))
    }

    @Test
    fun wrapUpStreamCap_longPrefillDoesNotConsumeSummaryWindow() {
        // 核心回归：流开始 200 秒（长上下文 prefill），首 chunk 5 秒前才到 → 未超限。
        // 旧逻辑从流开始计时，90 秒上限在这里早已撞上，总结零文本就被断流。
        val start = 1_000_000L
        assertFalse(isStreamCapExceeded(true, start + 205_000L, start, start + 200_000L))
    }

    @Test
    fun wrapUpStreamCap_countsFromFirstChunk() {
        val start = 1_000_000L
        assertFalse(isStreamCapExceeded(true, start + 10_000L, start, start))
        assertTrue(isStreamCapExceeded(true, start + 91_000L, start, start))
    }

    @Test
    fun normalStreamCap_stillCountsFromStreamStart() {
        // 普通轮不受收尾规则影响：仍从流开始计时，上限 15 分钟。
        val start = 1_000_000L
        assertFalse(isStreamCapExceeded(false, start + 10 * 60_000L, start, start))
        assertTrue(isStreamCapExceeded(false, start + 16 * 60_000L, start, start))
    }

    @Test
    fun wrapUpGrace_isBudgetFifthClamped() {
        // 30 分钟预算 → 宽限 5 分钟（上限）；20 分钟 → 4 分钟；1 分钟 → 30 秒（下限）。
        assertEquals(5 * 60_000L, wrapUpGraceMsForBudget(30 * 60_000L))
        assertEquals(4 * 60_000L, wrapUpGraceMsForBudget(20 * 60_000L))
        assertEquals(30_000L, wrapUpGraceMsForBudget(1 * 60_000L))
        assertEquals(2 * 60_000L, wrapUpGraceMsForBudget(10 * 60_000L))
    }

    @Test
    fun wrapUpSummaryHasText_onlyNonBlankTextCounts() {
        assertFalse(wrapUpSummaryHasText(emptyList()))
        assertFalse(wrapUpSummaryHasText(listOf(UIMessagePart.Reasoning("thinking"))))
        assertFalse(wrapUpSummaryHasText(listOf(UIMessagePart.Text(""))))
        assertFalse(wrapUpSummaryHasText(listOf(UIMessagePart.Text("   "))))
        assertTrue(wrapUpSummaryHasText(listOf(UIMessagePart.Text("总结"))))
        assertTrue(
            wrapUpSummaryHasText(
                listOf(UIMessagePart.Reasoning("thinking"), UIMessagePart.Text("总结")),
            ),
        )
    }

    @Test
    fun terminalSummaryAppend_alwaysSetsFlag() {
        // 三处硬停（token / 循环保护 / 步数上限）共用这一个构造点：标志必须为 true，
        // 否则总结请求越不过 maxSteps，提醒发了也白发。
        val notice = UIMessage.system("test notice")
        val decision = terminalSummaryAppend(notice)
        assertTrue(decision.terminalSummary)
        assertEquals(notice, decision.message)
    }
}
