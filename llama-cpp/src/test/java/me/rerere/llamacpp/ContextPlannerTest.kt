package me.rerere.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPlannerTest {

    /** Roughly a 2B model: small enough that context, not weights, is the constraint. */
    private fun smallModel(
        slidingWindow: Int? = null,
        nCtxTrain: Int = 32768,
    ) = GgufModelInfo(
        nLayers = 26,
        nEmbd = 2048,
        nHeadKv = 4,
        nEmbdHeadK = 256,
        nEmbdHeadV = 256,
        nVocab = 262144,
        nCtxTrain = nCtxTrain,
        slidingWindow = slidingWindow,
        weightsBytes = 2_600_000_000L,
    )

    private fun tools(count: Int, bytesEach: Int = 700) =
        (1..count).map { ToolDeclaration("tool_$it", bytesEach) }

    private val plentyOfRam = 12_000_000_000L

    @Test
    fun `picks the largest ladder rung that fits memory`() {
        val plan = ContextPlanner.plan(smallModel(), plentyOfRam, tools(40), 500)
        assertEquals(32768, plan.nCtx)
    }

    @Test
    fun `never exceeds the model's trained context`() {
        val plan = ContextPlanner.plan(smallModel(nCtxTrain = 8192), plentyOfRam, tools(40), 500)
        assertEquals(8192, plan.nCtx)
    }

    @Test
    fun `sliding window models pay KV cache only for the window`() {
        val windowed = ContextPlanner.plan(smallModel(slidingWindow = 1024), plentyOfRam, tools(40), 500)
        val full = ContextPlanner.plan(smallModel(slidingWindow = null), plentyOfRam, tools(40), 500)
        assertTrue(
            "a windowed model must estimate less memory at the same context",
            windowed.estimatedBytes < full.estimatedBytes,
        )
    }

    @Test
    fun `steps the cache to q8_0 before giving up context`() {
        // Budget that fits 8192 at Q8_0 but not at F16, and 16384 Q8_0 exceeds budget.
        // Measured: 8192 F16=4414828493, 8192 Q8_0=3964989389, 16384 Q8_0=4474807040.
        // Budget = 4_100_000_000 < F16, >= Q8_0. availableRam = 4_100_000_000 / 0.8.
        val ramBudget = 5_125_000_000L
        val plan = ContextPlanner.plan(smallModel(), ramBudget, tools(40), 500)
        assertEquals(
            "should pick 8192 context",
            8192, plan.nCtx,
        )
        assertEquals(
            "cache should quantise rather than shrink context",
            KvCacheType.Q8_0, plan.cacheTypeK,
        )
    }

    @Test
    fun `drops the most recently enabled tools first and names them`() {
        // A 4096 context cannot hold 40 tools at 700 bytes each.
        val plan = ContextPlanner.plan(smallModel(nCtxTrain = 4096), plentyOfRam, tools(40), 500)
        assertTrue("some tools must be dropped", plan.droppedToolNames.isNotEmpty())
        assertTrue(
            "the last enabled tool goes first, the first enabled survives",
            plan.droppedToolNames.contains("tool_40") && !plan.droppedToolNames.contains("tool_1"),
        )
    }

    @Test
    fun `prefix-preserving drop policy - early large tool blocks later small ones`() {
        // One large tool enabled first, then several small tools. The large tool does not fit,
        // so all tools including later small ones are dropped, proving prefix-preserving
        // behaviour (not first-fit by size, which would keep the small ones).
        val largeFirst = listOf(
            ToolDeclaration("large_tool", 5000),
            ToolDeclaration("small_1", 100),
            ToolDeclaration("small_2", 100),
            ToolDeclaration("small_3", 100),
        )
        val plan = ContextPlanner.plan(smallModel(nCtxTrain = 4096), plentyOfRam, largeFirst, 500)
        assertTrue(
            "all tools must be dropped since first tool does not fit",
            plan.droppedToolNames.containsAll(listOf("large_tool", "small_1", "small_2", "small_3")),
        )
    }

    @Test
    fun `the prompt budget always fits the planned context`() {
        // The LiteRT regression in one assertion: what we budget must fit what the
        // engine is given. Assert independently: the input half of context must hold
        // both system prompt and all enabled tools, with headroom reserved for history.
        listOf(4096, 8192, 16384, 32768).forEach { trained ->
            val plan = ContextPlanner.plan(smallModel(nCtxTrain = trained), plentyOfRam, tools(40), 500)
            assertEquals("planner must use the trained context at trained=$trained", trained, plan.nCtx)
            val keptToolBytes = tools(40)
                .filterNot { plan.droppedToolNames.contains(it.name) }
                .sumOf { it.jsonBytes }
            val systemPromptBytes = 500
            // Independent assertion: budget must fit in the input half minus history headroom.
            val inputTokens = (plan.nCtx * 0.5).toInt() - 750
            val availableBytes = inputTokens * ContextPlanner.BYTES_PER_TOKEN
            assertTrue(
                "budget ${keptToolBytes + systemPromptBytes}b must fit ${availableBytes}b in ${plan.nCtx}t at trained=$trained",
                keptToolBytes + systemPromptBytes <= availableBytes,
            )
        }
    }

    @Test
    fun `incomplete metadata falls back to the smallest rung`() {
        val broken = smallModel().copy(nLayers = 0)
        val plan = ContextPlanner.plan(broken, plentyOfRam, tools(4), 500)
        assertEquals(4096, plan.nCtx)
    }
}
