package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.ai.tools.local.NULL_CONTEXT
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Agent-trajectory bookkeeping: what the app learns about tool usage.
 *
 * Two things matter and are asserted here:
 *  1. the counters behave (count / failures / duration accumulate, averages divide safely,
 *     ordering is deterministic) — this is the data the tool-surface work is steered by;
 *  2. the privacy contract holds — an entry carries **only aggregate counters**, never
 *     arguments. The serialised shape is pinned so a future field cannot silently start
 *     persisting call payloads.
 *
 * Runs against the uninitialised [NULL_CONTEXT]: every SharedPreferences touch inside the
 * tracker is wrapped in runCatching, so persistence is simply skipped on a host JVM.
 */
class ToolUsageTrackerTest {

    @Before
    fun setUp() {
        // 统计默认关（普通用户不需要埋点）；这些用例验证的是**计数器本身的行为**，故显式打开。
        ToolUsageTracker.setEnabled(true)
        ToolUsageTracker.clear(NULL_CONTEXT)
    }

    @After
    fun tearDown() {
        ToolUsageTracker.clear(NULL_CONTEXT)
        // 关回去：开关是进程级全局态，不在用例间泄漏。
        ToolUsageTracker.setEnabled(false)
    }

    @Test
    fun `counters accumulate count failures and duration`() {
        ToolUsageTracker.record(NULL_CONTEXT, "web_fetch", durationMs = 120, failed = false)
        ToolUsageTracker.record(NULL_CONTEXT, "web_fetch", durationMs = 80, failed = true)

        val entry = ToolUsageTracker.snapshot(NULL_CONTEXT).single()
        assertEquals("web_fetch", entry.name)
        assertEquals(2L, entry.count)
        assertEquals(1L, entry.failures)
        assertEquals(200L, entry.totalMs)
        assertEquals(100L, entry.avgMs)
        assertTrue("lastUsedAt should be stamped on record", entry.lastUsedAt > 0)
    }

    @Test
    fun `negative durations cannot rewind the total`() {
        ToolUsageTracker.record(NULL_CONTEXT, "t", durationMs = -500, failed = false)
        ToolUsageTracker.record(NULL_CONTEXT, "t", durationMs = 30, failed = false)

        val entry = ToolUsageTracker.snapshot(NULL_CONTEXT).single()
        assertEquals(30L, entry.totalMs)
        assertEquals(2L, entry.count)
    }

    @Test
    fun `an answered interactive tool counts as one call with no execution time`() {
        // 交互式工具（如 ask_user）没有可执行体：它的结果就是用户的回答，不经过 Tool.execute，
        // 因此由调用方在「回答落库」处补记一次。耗时记 0（等人思考不是工具执行时间）。
        // 锁定口径：这种补记仍要计数、且不能算失败 —— 否则 usage 报告会把用过的工具列成「从未调用」。
        ToolUsageTracker.record(NULL_CONTEXT, "ask_user", durationMs = 0, failed = false)

        val entry = ToolUsageTracker.snapshot(NULL_CONTEXT).single()
        assertEquals(1L, entry.count)
        assertEquals(0L, entry.failures)
        assertEquals(0L, entry.avgMs)
    }

    @Test
    fun `snapshot orders by call count desc then by name`() {
        ToolUsageTracker.record(NULL_CONTEXT, "b", 1, false)
        ToolUsageTracker.record(NULL_CONTEXT, "b", 1, false)
        ToolUsageTracker.record(NULL_CONTEXT, "a", 1, false)
        ToolUsageTracker.record(NULL_CONTEXT, "a", 1, false)
        ToolUsageTracker.record(NULL_CONTEXT, "c", 1, false)

        val names = ToolUsageTracker.snapshot(NULL_CONTEXT).map { it.name }
        assertEquals(listOf("a", "b", "c"), names)
    }

    @Test
    fun `clear resets every counter`() {
        ToolUsageTracker.record(NULL_CONTEXT, "t", 10, true)
        ToolUsageTracker.clear(NULL_CONTEXT)
        assertTrue(ToolUsageTracker.snapshot(NULL_CONTEXT).isEmpty())
    }

    @Test
    fun `average is zero before the first call`() {
        assertEquals(0L, ToolUsageTracker.Entry(name = "never-called").avgMs)
    }

    @Test
    fun `entry exposes only aggregate counters, never arguments`() {
        val entry = ToolUsageTracker.Entry(
            name = "x",
            count = 3,
            failures = 1,
            totalMs = 42,
            lastUsedAt = 7,
        )
        val keys = Json.parseToJsonElement(Json.encodeToString(ToolUsageTracker.Entry.serializer(), entry))
            .jsonObject.keys

        assertEquals(
            "persisted shape must stay aggregate-only (no call payloads)",
            setOf("name", "count", "failures", "totalMs", "lastUsedAt"),
            keys,
        )
    }

    @Test
    fun `failure kinds are persisted only when present`() {
        // 空 failureKinds 是默认值 → kotlinx 不写进 JSON（省体积，与报告侧“从不失败就不占字段”一致）；
        // 有内容时才出现，且维度**只到异常类名**（不含消息/参数）。
        // 注：只传 name 时其余字段也是默认值、同样不编码，所以这里显式给 count 作锚。
        val empty = ToolUsageTracker.Entry(name = "x", count = 1)
        assertEquals(
            setOf("name", "count"),
            Json.parseToJsonElement(Json.encodeToString(ToolUsageTracker.Entry.serializer(), empty)).jsonObject.keys,
        )

        val withKinds = ToolUsageTracker.Entry(name = "x", count = 1, failureKinds = mapOf("IOException" to 2L))
        assertEquals(
            setOf("name", "count", "failureKinds"),
            Json.parseToJsonElement(Json.encodeToString(ToolUsageTracker.Entry.serializer(), withKinds)).jsonObject.keys,
        )
    }

    @Test
    fun `failure kinds accumulate by exception class name only`() {
        ToolUsageTracker.record(NULL_CONTEXT, "t", 1, failed = true, failureKind = "IllegalArgumentException")
        ToolUsageTracker.record(NULL_CONTEXT, "t", 1, failed = true, failureKind = "IllegalArgumentException")
        ToolUsageTracker.record(NULL_CONTEXT, "t", 1, failed = true, failureKind = "IOException")
        // 未分类的失败（无类名）只计入 failures，不污染分布
        ToolUsageTracker.record(NULL_CONTEXT, "t", 1, failed = true)

        val entry = ToolUsageTracker.snapshot(NULL_CONTEXT).single()
        assertEquals(4L, entry.failures)
        assertEquals(mapOf("IllegalArgumentException" to 2L, "IOException" to 1L), entry.failureKinds)
    }
}
