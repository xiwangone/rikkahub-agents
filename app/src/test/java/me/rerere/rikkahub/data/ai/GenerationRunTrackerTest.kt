package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.ai.tools.ToolUsageTracker
import me.rerere.rikkahub.data.ai.tools.local.NULL_CONTEXT
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 运行归因：①**判定**要准（各出口 → 枚举，且不能把"进程被杀"误判成"用户停"）；②**采集**要有界
 * （聚合 + 最近 N 条、开关关闭时零写入）。
 *
 * 跑在未初始化的 [NULL_CONTEXT] 上：内部所有 SharedPreferences 触碰都被 runCatching 包住，
 * 宿主 JVM 上只是跳过持久化。
 */
class GenerationRunTrackerTest {

    private fun run(
        outcome: GenerationOutcome,
        modelId: String? = "m1",
        durationMs: Long = 10,
    ) = GenerationRun(
        ts = 1L,
        conversationId = "c1",
        modelId = modelId,
        providerId = "p1",
        assistantId = "a1",
        outcome = outcome.name,
        durationMs = durationMs,
    )

    @Before
    fun setUp() {
        // 归因采集复用工具统计开关（单一开关源）：用例验证计数器本身，故显式打开。
        ToolUsageTracker.setEnabled(true)
        GenerationRunTracker.clear(NULL_CONTEXT)
    }

    @After
    fun tearDown() {
        GenerationRunTracker.clear(NULL_CONTEXT)
        ToolUsageTracker.setEnabled(false)
    }

    // ---------- 判定（纯函数） ----------

    @Test
    fun `no cause and no abort reason is a clean completion`() {
        assertEquals(
            GenerationOutcome.COMPLETED,
            classifyGenerationOutcome(cause = null, abortReason = null, cancelSource = null),
        )
    }

    @Test
    fun `an explicit abort reason outranks the cause`() {
        // 墙钟/循环保护是循环体内显式设置的，比异常类型更可信
        assertEquals(
            GenerationOutcome.TIMEOUT,
            classifyGenerationOutcome(CancellationException("stopped"), GenerationOutcome.TIMEOUT, "user_stop"),
        )
        assertEquals(
            GenerationOutcome.LOOP_GUARD,
            classifyGenerationOutcome(null, GenerationOutcome.LOOP_GUARD, null),
        )
    }

    @Test
    fun `cancellation counts as user stop only when a source was marked`() {
        assertEquals(
            GenerationOutcome.USER_CANCELLED,
            classifyGenerationOutcome(CancellationException("stopped"), null, "user_stop"),
        )
        // 没有标记 = 不是用户点的（进程被杀等）→ 不能谎报成用户操作
        assertEquals(
            GenerationOutcome.UNKNOWN,
            classifyGenerationOutcome(CancellationException("killed"), null, null),
        )
    }

    @Test
    fun `transport and rate limit failures map to dedicated outcomes`() {
        assertEquals(
            GenerationOutcome.NETWORK_ERROR,
            classifyGenerationOutcome(IOException("connection reset"), null, null, "socket timeout"),
        )
        assertEquals(
            GenerationOutcome.RATE_LIMIT,
            classifyGenerationOutcome(RuntimeException("boom"), null, null, "429 rate_limit exceeded"),
        )
        // 其它接口错误统一归 API_ERROR（鉴权/额度等细分仍留在 errorKind）
        assertEquals(
            GenerationOutcome.API_ERROR,
            classifyGenerationOutcome(RuntimeException("boom"), null, null, "401 unauthorized"),
        )
    }

    @Test
    fun `cancellation marks are one-shot`() {
        GenerationRunTracker.markCancelled("conv-1", "user_stop")
        assertEquals("user_stop", GenerationRunTracker.consumeCancellation("conv-1"))
        assertNull("标记用掉即失效，避免把下一轮误判为用户取消", GenerationRunTracker.consumeCancellation("conv-1"))
        assertNull(GenerationRunTracker.consumeCancellation(null))
    }

    // ---------- 采集 ----------

    @Test
    fun `runs are aggregated by outcome and by model`() {
        GenerationRunTracker.record(NULL_CONTEXT, run(GenerationOutcome.COMPLETED))
        GenerationRunTracker.record(NULL_CONTEXT, run(GenerationOutcome.TIMEOUT))
        GenerationRunTracker.record(NULL_CONTEXT, run(GenerationOutcome.COMPLETED, modelId = "m2"))

        val state = GenerationRunTracker.snapshot(NULL_CONTEXT)
        assertEquals(2L, state.totals["COMPLETED"])
        assertEquals(1L, state.totals["TIMEOUT"])
        // m1 各一次（同一模型的多种结束原因要分开计），m2 一次
        assertEquals(1L, state.byModel["m1"]?.get("COMPLETED"))
        assertEquals(1L, state.byModel["m1"]?.get("TIMEOUT"))
        assertEquals(1L, state.byModel["m2"]?.get("COMPLETED"))
        assertEquals(3, state.recent.size)
        assertEquals(30L, state.totalDurationMs)
    }

    @Test
    fun `recent detail is capped while totals keep counting`() {
        repeat(GenerationRunTracker.MAX_RECENT + 5) {
            GenerationRunTracker.record(NULL_CONTEXT, run(GenerationOutcome.COMPLETED))
        }
        val state = GenerationRunTracker.snapshot(NULL_CONTEXT)
        assertEquals(GenerationRunTracker.MAX_RECENT, state.recent.size)
        assertEquals((GenerationRunTracker.MAX_RECENT + 5).toLong(), state.totals["COMPLETED"])
    }

    @Test
    fun `nothing is recorded while stats are disabled`() {
        ToolUsageTracker.setEnabled(false)
        GenerationRunTracker.record(NULL_CONTEXT, run(GenerationOutcome.COMPLETED))

        val state = GenerationRunTracker.snapshot(NULL_CONTEXT)
        assertTrue(state.recent.isEmpty())
        assertTrue(state.totals.isEmpty())
    }

    @Test
    fun `time split accumulates model and tool wall clock`() {
        GenerationRunTracker.record(
            NULL_CONTEXT,
            run(GenerationOutcome.COMPLETED).copy(modelMs = 1_000, toolMs = 2_500, durationMs = 4_000),
        )
        GenerationRunTracker.record(
            NULL_CONTEXT,
            run(GenerationOutcome.TIMEOUT).copy(modelMs = 500, toolMs = 0, durationMs = 900),
        )

        val state = GenerationRunTracker.snapshot(NULL_CONTEXT)
        assertEquals(1_500L, state.totalModelMs)
        assertEquals(2_500L, state.totalToolMs)
        assertEquals(4_900L, state.totalDurationMs)
    }

    @Test
    fun `negative partial timings cannot rewind the split`() {
        GenerationRunTracker.record(
            NULL_CONTEXT,
            run(GenerationOutcome.COMPLETED).copy(modelMs = -1_000, toolMs = -5),
        )

        val state = GenerationRunTracker.snapshot(NULL_CONTEXT)
        assertEquals(0L, state.totalModelMs)
        assertEquals(0L, state.totalToolMs)
    }

    @Test
    fun `cached tokens are clamped to prompt tokens at the data layer`() {
        // 个别服务端会给出「命中 > 输入」的越界值 → 与既有会话累计一致，在数据层夹住
        GenerationRunTracker.record(
            NULL_CONTEXT,
            run(GenerationOutcome.COMPLETED).copy(promptTokens = 1_000, cachedTokens = 1_500),
        )

        val state = GenerationRunTracker.snapshot(NULL_CONTEXT)
        assertEquals(1_000L, state.totalCachedTokens)
        assertEquals(1_000, state.recent.single().cachedTokens)
    }

    @Test
    fun `clear resets every counter`() {
        GenerationRunTracker.record(NULL_CONTEXT, run(GenerationOutcome.API_ERROR))
        GenerationRunTracker.clear(NULL_CONTEXT)
        assertTrue(GenerationRunTracker.snapshot(NULL_CONTEXT).totals.isEmpty())
    }
}
