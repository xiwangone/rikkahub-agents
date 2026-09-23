package me.rerere.ai.ui

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「工具调用配对」发送前修复的回归测试。
 *
 * 背景：助手消息带着 **没有响应的 `tool_calls`** 发出去时，OpenAI 兼容端点会拒收整轮请求
 * （`insufficient tool messages following tool_calls message`），表现为该会话持续报错、只能
 * 新建会话绕过。两种成因都要被 [repairToolPairing] 兜住：
 *  1. 工具**未执行**（卡住 / 被中断 / 结果被删）→ 结果为空；
 *  2. 工具结果落在**独立的 `TOOL` 消息**里（旧格式，数据库迁移只跑过一次，之后新产生的不会合并）。
 */
class ToolPairingRepairTest {

    @Suppress("DEPRECATION")
    private fun toolResult(id: String) =
        UIMessagePart.ToolResult(
            toolCallId = id,
            toolName = "test_tool",
            content = JsonPrimitive("done"),
            arguments = JsonPrimitive("{}"),
        )

    @Test
    fun `未执行的工具会补上确定性结果`() {
        val messages =
            listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(toolCallId = "call1", toolName = "test_tool", input = "{}"),
                    ),
                ),
            )

        val repaired = messages.repairToolPairing("test reason")

        val tool = repaired[1].parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertTrue(
            "未执行的工具必须补出结果，否则请求里 tool_calls 没有对应响应",
            tool.output.isNotEmpty(),
        )
        assertTrue(tool.approvalState is ToolApprovalState.Denied)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `独立的 TOOL 消息会被合并进助手消息`() {
        val messages =
            listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.ToolCall(toolCallId = "call1", toolName = "test_tool", arguments = "{}"),
                    ),
                ),
                UIMessage(role = MessageRole.TOOL, parts = listOf(toolResult("call1"))),
            )

        val repaired = messages.repairToolPairing("test reason")

        assertFalse("独立的 TOOL 消息必须被合并掉", repaired.any { it.role == MessageRole.TOOL })
        val tool = repaired[1].parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertTrue("合并后应带上工具结果", tool.output.isNotEmpty())
    }

    @Test
    fun `已配对的正常历史不会被改动`() {
        val messages =
            listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "call1",
                            toolName = "test_tool",
                            input = "{}",
                            output = listOf(UIMessagePart.Text("{\"ok\":true}")),
                        ),
                    ),
                ),
            )

        val repaired = messages.repairToolPairing("test reason")

        assertEquals(messages.size, repaired.size)
        assertEquals(messages[1], repaired[1])
    }
}
