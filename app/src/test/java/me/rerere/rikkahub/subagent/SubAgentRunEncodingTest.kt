package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理结果回显：同工作区时不仅要给机器可读的布尔标记，还要给**人能看懂的提示**
 * （风险 + 怎么办）—— 否则父子互踩只能靠读布尔值自己悟。
 */
class SubAgentRunEncodingTest {

    private fun run(sameWorkspace: Boolean) =
        SubAgentRun(
            id = "r1",
            parentChatId = null,
            parentAssistantId = "a1",
            label = "task",
            task = "do something",
            modelId = null,
            tools = null,
            runInBackground = false,
            timeoutSeconds = 60,
            maxTrips = 3,
            status = SubAgentStatus.SUCCEEDED,
            startedAtMs = 1L,
            sameWorkspaceAsParent = sameWorkspace,
        )

    @Test
    fun `shared workspace adds a note explaining the risk and what to do`() {
        val encoded = encodeRun(run(sameWorkspace = true))

        assertEquals(true, encoded["workspace_shared_with_parent"]?.jsonPrimitive?.boolean)
        val note = encoded["workspace_note"]?.jsonPrimitive?.contentOrNull
        assertTrue(
            "提示必须说明同区影响与处置建议，不能只是重复布尔值",
            !note.isNullOrBlank() && note.contains("工作区") && note.contains("覆盖"),
        )
    }

    @Test
    fun `isolated workspace carries no sharing fields`() {
        val encoded = encodeRun(run(sameWorkspace = false))

        assertFalse(encoded.containsKey("workspace_shared_with_parent"))
        assertFalse(encoded.containsKey("workspace_note"))
    }
}
