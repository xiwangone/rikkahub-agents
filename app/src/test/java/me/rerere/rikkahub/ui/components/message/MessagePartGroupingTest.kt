package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * chart_display 输出解析与消息分组回归。
 *
 * 背景：工具输出尾部会被追加审批来源标记行（如 `[approval: auto]`，生成端
 * GenerationLoop.appendApprovalProvenance）。图表判定曾因把标记行一起送去解析而失败，
 * 表现为工具卡里只有 JSON、不渲染图表。
 */
class MessagePartGroupingTest {
    private fun text(s: String) = UIMessagePart.Text(text = s)

    private fun tool(
        name: String = "chart_display",
        output: List<UIMessagePart> = listOf(text("""{"success":true}""")),
    ) = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = name,
        input = """{"style":"line","series":[{"name":"s","values":[1,2]}]}""",
        output = output,
    )

    @Test
    fun stripApprovalProvenance_removesMarkerLine() {
        assertEquals("""{"a":1}""", stripApprovalProvenance("{\"a\":1}\n[approval: auto]"))
    }

    @Test
    fun stripApprovalProvenance_keepsPayloadWithoutMarker() {
        assertEquals("""{"a":1}""", stripApprovalProvenance("""{"a":1}"""))
    }

    @Test
    fun stripApprovalProvenance_keepsMultilinePayload() {
        val payload = "{\n  \"a\": 1\n}"
        assertEquals(payload, stripApprovalProvenance("$payload\n[approval: approved-by-user]"))
    }

    @Test
    fun chartDisplayWithApprovalMarkerBecomesChartBlock() {
        val blocks =
            listOf(
                tool(output = listOf(text("""{"success":true,"message":"ok"}""" + "\n[approval: auto]"))),
            ).groupMessageParts()
        assertTrue("带审批标记的成功调用应渲染为图表卡", blocks.single() is MessagePartBlock.ChartBlock)
    }

    @Test
    fun chartDisplayWithoutMarkerStillBecomesChartBlock() {
        val blocks = listOf(tool()).groupMessageParts()
        assertTrue(blocks.single() is MessagePartBlock.ChartBlock)
    }

    @Test
    fun failedChartDisplayStaysAsToolStep() {
        val blocks =
            listOf(tool(output = listOf(text("""{"success":false}""" + "\n[approval: auto]"))))
                .groupMessageParts()
        assertFalse(blocks.single() is MessagePartBlock.ChartBlock)
    }

    @Test
    fun otherToolWithSameOutputIsNotChartBlock() {
        val blocks =
            listOf(
                tool(
                    name = "web_fetch",
                    output = listOf(text("""{"success":true}""" + "\n[approval: auto]")),
                ),
            ).groupMessageParts()
        assertFalse(blocks.single() is MessagePartBlock.ChartBlock)
    }

    @Test
    fun unexecutedChartDisplayIsNotChartBlock() {
        val blocks = listOf(tool(output = emptyList())).groupMessageParts()
        assertFalse(blocks.single() is MessagePartBlock.ChartBlock)
    }
}
