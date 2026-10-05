package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * parseToolOutputContent 的回归测试。
 *
 * 背景：该函数把工具输出的 Text 部件拼成一段字符串后**整体当 JSON 解析**。
 * 工具结果里可能附着「审批来源标记」行（`[approval: …]`）—— 若解析前不先剥离，
 * 整段就不是合法 JSON → 返回 null → **所有已注册渲染器的 Preview / Summary 一起失效**
 * （表现：一律回落 DefaultToolPreview 的裸 JSON 详情）。
 * 这类问题只在运行链路上暴露、静态检查器抓不到，因此用单测钉住。
 */
class ParseToolOutputContentTest {

    private fun toolWithOutput(text: String) =
        UIMessagePart.Tool(
            toolCallId = "t-1",
            toolName = "workspace_shell",
            input = "{}",
            output = listOf(UIMessagePart.Text(text)),
        )

    @Test
    fun `plain json output parses`() {
        val parsed = parseToolOutputContent(toolWithOutput("""{"stdout":"ok","exit_code":0}"""))
        assertNotNull("纯 JSON 输出应能解析", parsed)
    }

    @Test
    fun `output with approval marker line still parses`() {
        // 回归用例：带审批标记行时必须先剥离再解析，否则 content 为 null → 全部渲染器失效
        val parsed =
            parseToolOutputContent(
                toolWithOutput(
                    """
                    {"stdout":"ok","exit_code":0}
                    [approval: auto]
                    """.trimIndent(),
                ),
            )
        assertNotNull("带审批标记行的输出仍应解析出 JSON", parsed)
    }

    @Test
    fun `tool without output returns null`() {
        val tool = UIMessagePart.Tool(toolCallId = "t-2", toolName = "x", input = "{}")
        assertNull("未执行的工具（无输出）应返回 null", parseToolOutputContent(tool))
    }

    @Test
    fun `non json output returns null`() {
        assertNull(parseToolOutputContent(toolWithOutput("just plain text")))
    }
}
