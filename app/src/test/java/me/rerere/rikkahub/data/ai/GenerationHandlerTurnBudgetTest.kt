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

    // ── 单步流时钟（矛盾核查 1-1：重试不再重置时钟）─────────────────────
    //
    // 旧逻辑：每次重试都重置流开始时刻，每 attempt 各拿满 15 分钟；默认 5 次重试 =
    // 6 attempt × 15 分钟，再加 5 次服务端 Retry-After（单次上限 5 分钟），
    // 单步最长 6×15 + 5×5 = 115 分钟。新逻辑：15 分钟上限按步计量。

    @Test
    fun stepStreamClock_retryDoesNotResetStart() {
        val clock = StepStreamClock()
        val start = 1_000_000L
        clock.onAttemptStart(0, start)
        assertEquals(start, clock.streamStartedAtMs)
        // 第 2 次 attempt 在步开始 14 分钟后启动：开始时刻不变，只剩 1 分钟预算
        clock.onAttemptStart(1, start + 14 * 60_000L)
        assertEquals(start, clock.streamStartedAtMs)
        assertFalse(clock.isBudgetExhausted(start + 14 * 60_000L))
        // 第 3 次 attempt 在步开始 15 分钟后启动：预算已耗尽
        clock.onAttemptStart(2, start + 15 * 60_000L)
        assertEquals(start, clock.streamStartedAtMs)
        assertTrue(clock.isBudgetExhausted(start + 15 * 60_000L))
    }

    @Test
    fun stepStreamClock_firstChunkPersistsAcrossRetries() {
        val clock = StepStreamClock()
        val start = 1_000_000L
        clock.onAttemptStart(0, start)
        clock.onFirstChunk(start + 5_000L)
        assertEquals(start + 5_000L, clock.firstChunkAtMs)
        // 重试的首 chunk 不覆盖步内首次到达的时刻：收尾 90 秒窗口按步计量
        clock.onAttemptStart(1, start + 60_000L)
        clock.onFirstChunk(start + 65_000L)
        assertEquals(start + 5_000L, clock.firstChunkAtMs)
    }

    @Test
    fun stepStreamClock_nextStepResetsClock() {
        val clock = StepStreamClock()
        val start = 1_000_000L
        clock.onAttemptStart(0, start)
        clock.onFirstChunk(start + 5_000L)
        // 下一步的首次 attempt 重新记时、清首 chunk 时刻
        val nextStart = start + 20 * 60_000L
        clock.onAttemptStart(0, nextStart)
        assertEquals(nextStart, clock.streamStartedAtMs)
        assertEquals(0L, clock.firstChunkAtMs)
        assertFalse(clock.isBudgetExhausted(nextStart))
    }

    @Test
    fun stepStreamClock_budgetNeverExhaustedBeforeStreamStarts() {
        // 非流式路径不走 onStreamAttemptReset：时钟未启动时永不耗尽、不钳制等待
        val clock = StepStreamClock()
        assertFalse(clock.isBudgetExhausted(1_000_000L))
        assertEquals(4_000L, clock.clampDelayMs(4_000L, 1_000_000L))
        assertEquals(300_000L, clock.clampDelayMs(300_000L, 1_000_000L))
    }

    @Test
    fun stepStreamClock_clampsRetryDelayToRemainingBudget() {
        val clock = StepStreamClock()
        val start = 1_000_000L
        clock.onAttemptStart(0, start)
        // 剩余 60 秒时，300 秒 Retry-After 钳制到 60 秒
        assertEquals(60_000L, clock.clampDelayMs(300_000L, start + 14 * 60_000L))
        // 剩余充足时原样返回
        assertEquals(4_000L, clock.clampDelayMs(4_000L, start + 60_000L))
        // 预算耗尽时等待为 0（retryOn 已拒重试，此处只是钳制底线）
        assertEquals(0L, clock.clampDelayMs(4_000L, start + 15 * 60_000L))
    }

    @Test
    fun stepStreamClock_sixAttemptsShareOne15MinuteBudget() {
        // 旧算式 6×15 + 5×5 = 115 分钟；新逻辑 6 次 attempt 共用同一个 15 分钟步预算，
        // 步开始 15 分钟后无论第几次 attempt 都已耗尽，不会再有新的重试。
        val clock = StepStreamClock()
        val start = 1_000_000L
        clock.onAttemptStart(0, start)
        repeat(6) { attempt ->
            clock.onAttemptStart(attempt, start + attempt * 60_000L)
            assertEquals(start, clock.streamStartedAtMs)
        }
        assertTrue(clock.isBudgetExhausted(start + 15 * 60_000L))
        assertEquals(0L, clock.clampDelayMs(300_000L, start + 15 * 60_000L))
    }
}
