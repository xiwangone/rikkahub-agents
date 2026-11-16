package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pendingToolSummaryLine 的回归测试。
 *
 * 背景：待审批调用尚无输出，渲染器的 Summary 读不到任何东西，审批卡上只剩标题和按钮，
 * 用户看不到这次调用要干什么（盲批）。该函数在可见区域补一行「工具名 + 入参 JSON」摘要。
 * 入参可能含凭证，必须先脱敏再展示；超长要截断。
 *
 * 脱敏是按键名递归做的（与自由文本脱敏共用同一张敏感词表）：裸 `token` 与短于 8 字符的
 * 凭证值也能掩住，嵌套对象里的凭证同样处理——只靠自由文本脱敏会漏掉这三类。
 */
class PendingToolSummaryTest {

    private fun tool(
        name: String = "run_shell",
        input: String,
    ) = UIMessagePart.Tool(toolCallId = "t-1", toolName = name, input = input)

    @Test
    fun `shows tool name and args`() {
        assertEquals(
            """run_shell: {"command":"ls -la"}""",
            pendingToolSummaryLine(tool(input = """{"command":"ls -la"}""")),
        )
    }

    @Test
    fun `empty args shows empty object`() {
        assertEquals("run_shell: {}", pendingToolSummaryLine(tool(input = "{}")))
    }

    @Test
    fun `invalid json falls back to empty object`() {
        assertEquals("run_shell: {}", pendingToolSummaryLine(tool(input = "not json")))
    }

    @Test
    fun `masks secret values`() {
        val line = pendingToolSummaryLine(tool(input = """{"token":"s3cret-value-123456"}"""))
        assertFalse("凭证值不得原样出现在摘要里", line.contains("s3cret-value-123456"))
        assertTrue("脱敏后应留掩码标记", line.contains("***"))
        assertTrue("工具名仍应保留", line.startsWith("run_shell: "))
    }

    @Test
    fun `masks short secret values`() {
        // 自由文本脱敏只掩 8 字符以上的值；按键名脱敏没有长度下限，短口令也不能漏。
        val line = pendingToolSummaryLine(tool(input = """{"password":"abc123"}"""))
        assertFalse("短凭证值不得原样出现在摘要里", line.contains("abc123"))
        assertTrue("脱敏后应留掩码标记", line.contains("***"))
    }

    @Test
    fun `masks nested secret values`() {
        // 嵌套对象里的短凭证：自由文本脱敏因长度下限漏掉，递归按键名脱敏才能掩住。
        val line = pendingToolSummaryLine(tool(input = """{"config":{"password":"abc123"}}"""))
        assertFalse("嵌套的短凭证值不得原样出现在摘要里", line.contains("abc123"))
        assertTrue("脱敏后应留掩码标记", line.contains("***"))
        assertTrue("非敏感键仍应保留", line.contains("config"))
    }

    @Test
    fun `truncates long args`() {
        val longCommand = "x".repeat(300)
        val line = pendingToolSummaryLine(tool(input = """{"command":"$longCommand"}"""))
        assertTrue("超长入参应以省略号结尾", line.endsWith("…"))
        assertTrue(
            "截断后长度应受控",
            line.length <= "run_shell: ".length + PENDING_TOOL_SUMMARY_MAX_CHARS + 1,
        )
    }
}
