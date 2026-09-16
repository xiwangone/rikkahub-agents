package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 审批状态单调合并（[mergeApprovalProgress]）的行为约束。
 *
 * 背景：流式生成期间会有多份「过期快照」回灌会话状态，若允许 `Auto` 逆向覆盖，
 * 就会出现「Pending 已置位 → 被旧快照打回 Auto → 审批按钮不渲染 → 用户发下一条消息
 * 时被冤判为 Denied」的连锁故障。
 */
class ToolApprovalMergeTest {
    private fun tool(
        callId: String = "call_1",
        name: String = "shizuku_exec",
        state: ToolApprovalState = ToolApprovalState.Auto,
        output: List<UIMessagePart> = emptyList(),
    ) = UIMessagePart.Tool(
        toolCallId = callId,
        toolName = name,
        input = "{}",
        output = output,
        approvalState = state,
    )

    private fun message(
        id: Uuid = Uuid.random(),
        parts: List<UIMessagePart>,
    ) = UIMessage(id = id, role = MessageRole.ASSISTANT, parts = parts)

    @Test
    fun `stale Auto snapshot must not regress Pending`() {
        val stale = tool(state = ToolApprovalState.Auto)
        val pending = tool(state = ToolApprovalState.Pending)

        assertEquals(ToolApprovalState.Pending, stale.mergeApprovalProgress(pending).approvalState)
    }

    @Test
    fun `progressing snapshot is accepted as is`() {
        val incoming = tool(state = ToolApprovalState.Pending)

        assertSame(incoming, incoming.mergeApprovalProgress(tool(state = ToolApprovalState.Auto)))
    }

    @Test
    fun `approved is not regressed by Auto`() {
        val stale = tool(state = ToolApprovalState.Auto)

        assertEquals(
            ToolApprovalState.Approved,
            stale.mergeApprovalProgress(tool(state = ToolApprovalState.Approved)).approvalState,
        )
    }

    @Test
    fun `terminal states are never overwritten`() {
        val terminals =
            listOf(
                ToolApprovalState.Denied("blocked by safety floor"),
                ToolApprovalState.Answered("""{"answers":{}}"""),
            )
        for (terminal in terminals) {
            val merged = tool(state = ToolApprovalState.Auto).mergeApprovalProgress(tool(state = terminal))
            assertEquals(terminal, merged.approvalState)
        }
    }

    @Test
    fun `different tool call ids are not merged`() {
        val merged =
            tool(callId = "call_2", state = ToolApprovalState.Auto)
                .mergeApprovalProgress(tool(callId = "call_1", state = ToolApprovalState.Pending))

        assertEquals(ToolApprovalState.Auto, merged.approvalState)
    }

    @Test
    fun `message merge preserves pending and stays reference equal when nothing to do`() {
        val id = Uuid.random()
        val stale = message(id, listOf(tool(state = ToolApprovalState.Auto)))
        val live = message(id, listOf(tool(state = ToolApprovalState.Pending)))

        val merged = stale.mergeApprovalProgress(live)
        assertEquals(
            ToolApprovalState.Pending,
            merged.parts.filterIsInstance<UIMessagePart.Tool>().first().approvalState,
        )
        // 没有回退可拦时保持引用相等，避免无谓重组与落盘
        assertSame(live, live.mergeApprovalProgress(stale))
    }

    @Test
    fun `message merge ignores different message ids`() {
        val stale = message(parts = listOf(tool(state = ToolApprovalState.Auto)))
        val other = message(parts = listOf(tool(state = ToolApprovalState.Pending)))

        assertSame(stale, stale.mergeApprovalProgress(other))
    }

    @Test
    fun `list merge only rewrites when a regression is blocked`() {
        val id = Uuid.random()
        val live = listOf(message(id, listOf(tool(state = ToolApprovalState.Pending))))
        val stale = listOf(message(id, listOf(tool(state = ToolApprovalState.Auto))))

        assertSame(live, live.mergeApprovalProgress(stale))
        assertNotSame(stale, stale.mergeApprovalProgress(live))
        assertEquals(
            ToolApprovalState.Pending,
            stale.mergeApprovalProgress(live).first()
                .parts.filterIsInstance<UIMessagePart.Tool>().first().approvalState,
        )
    }

    @Test
    fun `only the regressing tool part is rewritten`() {
        val id = Uuid.random()
        val stale =
            message(
                id,
                listOf(
                    tool(callId = "call_1", state = ToolApprovalState.Auto),
                    tool(callId = "call_2", state = ToolApprovalState.Pending),
                ),
            )
        val live =
            message(
                id,
                listOf(
                    tool(callId = "call_1", state = ToolApprovalState.Pending),
                    tool(callId = "call_2", state = ToolApprovalState.Auto),
                ),
            )

        val tools = stale.mergeApprovalProgress(live).parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(ToolApprovalState.Pending, tools[0].approvalState)
        assertEquals(ToolApprovalState.Pending, tools[1].approvalState)
    }
}
