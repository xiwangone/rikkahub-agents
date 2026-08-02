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
        // Enough RAM for a large context only if the cache is quantised.
        val tight = 3_400_000_000L
        val plan = ContextPlanner.plan(smallModel(), tight, tools(40), 500)
        assertEquals(
            "cache should quantise rather than shrink context first",
            KvCacheType.Q8_0, plan.cacheTypeK,
        )
    }

    @Test
    fun `drops the most recently enabled tools first and names them`() {
        // A 2048 context cannot hold 40 tools at 700 bytes each.
        val plan = ContextPlanner.plan(smallModel(nCtxTrain = 4096), plentyOfRam, tools(40), 500)
        assertTrue("some tools must be dropped", plan.droppedToolNames.isNotEmpty())
        assertTrue(
            "the last enabled tool goes first, the first enabled survives",
            plan.droppedToolNames.contains("tool_40") && !plan.droppedToolNames.contains("tool_1"),
        )
    }

    @Test
    fun `the prompt budget always fits the planned context`() {
        // The LiteRT regression in one assertion: what we budget must fit what the
        // engine is given. Checked across every ladder rung.
        listOf(4096, 8192, 16384, 32768).forEach { trained ->
            val plan = ContextPlanner.plan(smallModel(nCtxTrain = trained), plentyOfRam, tools(40), 500)
            val keptBytes = tools(40)
                .filterNot { plan.droppedToolNames.contains(it.name) }
                .sumOf { it.jsonBytes }
            val promptBudgetTokens = (keptBytes + 500) / ContextPlanner.BYTES_PER_TOKEN
            assertTrue(
                "budget ${promptBudgetTokens}t must fit ${plan.nCtx}t at trained=$trained",
                promptBudgetTokens <= plan.nCtx,
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
