package me.rerere.ai.ui

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalStateTest {
    @Test
    fun `approved denied and answered states can resume tool execution`() {
        assertTrue(ToolApprovalState.Approved().canResumeToolExecution())
        assertTrue(ToolApprovalState.Denied("no").canResumeToolExecution())
        assertTrue(ToolApprovalState.Answered("""{"answers":{"q1":"yes"}}""").canResumeToolExecution())
    }

    @Test
    fun `auto and pending states cannot resume tool execution`() {
        assertFalse(ToolApprovalState.Auto.canResumeToolExecution())
        assertFalse(ToolApprovalState.Pending.canResumeToolExecution())
    }

    @Test
    fun `approved without scope decodes to once`() {
        // 旧数据只有 {"type":"approved"}，缺 scope 字段时默认只对本次调用有效。
        val decoded = json.decodeFromString<ToolApprovalState>("""{"type":"approved"}""")
        assertEquals(ToolApprovalState.Approved("once"), decoded)
    }

    @Test
    fun `approved scope round-trips`() {
        val encoded = json.encodeToString<ToolApprovalState>(ToolApprovalState.Approved("always"))
        assertEquals(ToolApprovalState.Approved("always"), json.decodeFromString<ToolApprovalState>(encoded))
        assertEquals(
            ToolApprovalState.Approved("chat"),
            json.decodeFromString<ToolApprovalState>("""{"type":"approved","scope":"chat"}"""),
        )
    }
}
