package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentToolsEncodeRunTest {

    private fun makeRun(
        result: String? = "the answer",
        noResult: Boolean = false,
        tokensIn: Long = 0,
        tokensCached: Long = 0,
        tokensOut: Long = 0,
        tripCount: Int = 0,
        workspaceId: String? = null,
        toolScope: List<String>? = null,
    ): SubAgentRun = SubAgentRun(
        id = "r1",
        parentChatId = "chat-1",
        parentAssistantId = "asst-1",
        label = "label",
        task = "task",
        modelId = null,
        tools = null,
        runInBackground = false,
        noResult = noResult,
        timeoutSeconds = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
        maxTrips = SubAgentDefaults.DEFAULT_MAX_TRIPS,
        status = SubAgentStatus.SUCCEEDED,
        result = result,
        startedAtMs = System.currentTimeMillis(),
        tokensIn = tokensIn,
        tokensCached = tokensCached,
        tokensOut = tokensOut,
        tripCount = tripCount,
        workspaceId = workspaceId,
        toolScope = toolScope,
    )

    @Test fun `result present and result_suppressed absent when noResult is false`() {
        val json = encodeRun(makeRun(result = "the answer", noResult = false))
        assertEquals("the answer", json["result"]?.toString()?.trim('"'))
        assertFalse(json.containsKey("result_suppressed"))
    }

    @Test fun `result absent and result_suppressed present when noResult is true`() {
        val json = encodeRun(makeRun(result = "the answer", noResult = true))
        assertFalse(json.containsKey("result"))
        assertTrue(json["result_suppressed"]?.toString()?.toBoolean() == true)
    }

    @Test fun `result_suppressed present even when result is null and noResult is true`() {
        val json = encodeRun(makeRun(result = null, noResult = true))
        assertFalse(json.containsKey("result"))
        assertTrue(json["result_suppressed"]?.toString()?.toBoolean() == true)
    }

    @Test fun `neither key present when result is null and noResult is false`() {
        val json = encodeRun(makeRun(result = null, noResult = false))
        assertFalse(json.containsKey("result"))
        assertFalse(json.containsKey("result_suppressed"))
    }

    @Test fun `usage fields are echoed with cache hit percentage`() {
        val json = encodeRun(
            makeRun(tokensIn = 1000, tokensCached = 990, tokensOut = 20, tripCount = 3)
        )
        assertEquals(1000L, json.getValue("tokens_in").toString().toLong())
        assertEquals(990L, json.getValue("tokens_cached").toString().toLong())
        assertEquals(20L, json.getValue("tokens_out").toString().toLong())
        assertEquals(3, json.getValue("trip_count").toString().toInt())
        assertEquals(99.0, json.getValue("cache_hit_pct").toString().toDouble(), 0.01)
    }

    @Test fun `scope fields are echoed when set and omitted when unset`() {
        val scoped = encodeRun(
            makeRun(
                workspaceId = "ws-1",
                toolScope = listOf("workspace_read_file", "web_fetch"),
            )
        )
        assertEquals("ws-1", scoped.getValue("workspace_id").toString().trim('"'))
        assertTrue(scoped.getValue("tool_scope").toString().contains("workspace_read_file"))
        assertTrue(scoped.getValue("tool_scope").toString().contains("web_fetch"))

        val bare = encodeRun(makeRun())
        assertFalse(bare.containsKey("workspace_id"))
        assertFalse(bare.containsKey("tool_scope"))
        assertFalse(bare.containsKey("cache_hit_pct"))
    }
}
