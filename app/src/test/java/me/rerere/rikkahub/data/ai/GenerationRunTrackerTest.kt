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
        ts: Long = 1L,
        cost: Double? = null,
        providerKey: String? = "openai",
        providerName: String? = "OpenAI",
        modelDisplayName: String? = "GPT",
        promptTokens: Long = 0,
        completionTokens: Long = 0,
    ) = GenerationRun(
        ts = ts,
        conversationId = "c1",
        modelId = modelId,
        providerId = "p1",
        providerKey = providerKey,
        providerName = providerName,
        modelDisplayName = modelDisplayName,
        assistantId = "a1",
        outcome = outcome.name,
        durationMs = durationMs,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cost = cost,
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

    // ---------- 用量统计（provider × 模型 / 费用 / 按日分桶） ----------

    @Test
    fun `runs are aggregated by provider and model with cost`() {
        GenerationRunTracker.record(
            NULL_CONTEXT,
            run(GenerationOutcome.COMPLETED, cost = 0.0123, promptTokens = 100, completionTokens = 50),
        )
        GenerationRunTracker.record(
            NULL_CONTEXT,
            run(GenerationOutcome.COMPLETED, cost = 0.004, promptTokens = 200, completionTokens = 10),
        )
        // 未上报费用的生成：tokens 照计，费用不估算
        GenerationRunTracker.record(
            NULL_CONTEXT,
            run(GenerationOutcome.COMPLETED, modelId = "m2", providerKey = "google", cost = null, promptTokens = 300),
        )

        val state = GenerationRunTracker.snapshot(NULL_CONTEXT)
        val openai = state.byProviderModel["openai/m1"]
        assertEquals(2L, openai?.runs)
        assertEquals(300L, openai?.promptTokens)
        assertEquals(60L, openai?.completionTokens)
        assertEquals(0.0163, openai?.cost ?: 0.0, 1e-9)
        assertEquals(2L, openai?.costReportedRuns)
        assertEquals("OpenAI", openai?.providerName)
        assertEquals("GPT", openai?.modelDisplayName)

        val google = state.byProviderModel["google/m2"]
        assertEquals(1L, google?.runs)
        assertEquals(0.0, google?.cost ?: -1.0, 1e-9)
        assertEquals(0L, google?.costReportedRuns)
        assertEquals(0.0163, state.totalCost, 1e-9)
    }

    @Test
    fun `day buckets roll and prune beyond 90 days`() {
        val today = java.time.LocalDate.now()
        val oldTs = today.minusDays(100).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val nowTs = System.currentTimeMillis()
        GenerationRunTracker.record(NULL_CONTEXT, run(GenerationOutcome.COMPLETED, ts = oldTs, cost = 1.0))
        GenerationRunTracker.record(NULL_CONTEXT, run(GenerationOutcome.COMPLETED, ts = nowTs, cost = 2.0))

        val state = GenerationRunTracker.snapshot(NULL_CONTEXT)
        // 100 天前的分桶被滚动清理
        assertTrue(state.daily.keys.none { it < today.minusDays(89).toString() })
        assertEquals(1, state.daily.size)
        // 累计不受滚动清理影响
        assertEquals(3.0, state.totalCost, 1e-9)
    }

    @Test
    fun `range stats slice daily buckets correctly`() {
        val today = java.time.LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        fun tsOf(daysAgo: Long) = today.minusDays(daysAgo).atStartOfDay(zone).toInstant().toEpochMilli()
        GenerationRunTracker.record(NULL_CONTEXT, run(GenerationOutcome.COMPLETED, ts = tsOf(0), cost = 1.0, promptTokens = 10))
        GenerationRunTracker.record(NULL_CONTEXT, run(GenerationOutcome.COMPLETED, ts = tsOf(6), cost = 2.0, promptTokens = 20))
        GenerationRunTracker.record(NULL_CONTEXT, run(GenerationOutcome.COMPLETED, ts = tsOf(20), cost = 4.0, promptTokens = 40))

        val state = GenerationRunTracker.snapshot(NULL_CONTEXT)
        val todayStats = state.rangeStats(UsageRange.TODAY, today)
        assertEquals(1L, todayStats.runs)
        assertEquals(1.0, todayStats.cost, 1e-9)

        val week = state.rangeStats(UsageRange.LAST_7_DAYS, today)
        assertEquals(2L, week.runs)
        assertEquals(3.0, week.cost, 1e-9)
        assertEquals(30L, week.promptTokens)

        val month = state.rangeStats(UsageRange.LAST_30_DAYS, today)
        assertEquals(3L, month.runs)
        assertEquals(7.0, month.cost, 1e-9)

        val all = state.rangeStats(UsageRange.ALL_TIME, today)
        assertEquals(3L, all.runs)
        assertEquals(3L, all.byProviderModel["openai/m1"]?.runs)
    }

    @Test
    fun `dayKeyOf and pruneDaily are pure and timezone local`() {
        val today = java.time.LocalDate.now()
        val key = dayKeyOf(System.currentTimeMillis())
        assertEquals(today.toString(), key)
        val daily =
            mapOf(
                today.minusDays(89).toString() to DayStats(),
                today.minusDays(90).toString() to DayStats(),
                today.toString() to DayStats(),
            )
        val pruned = pruneDaily(daily, today)
        assertTrue(pruned.containsKey(today.minusDays(89).toString()))
        assertTrue(pruned.containsKey(today.toString()))
        // 第 90 天（含今天共 91 天）被清掉，只留 90 天
        assertTrue(!pruned.containsKey(today.minusDays(90).toString()))
    }
}
