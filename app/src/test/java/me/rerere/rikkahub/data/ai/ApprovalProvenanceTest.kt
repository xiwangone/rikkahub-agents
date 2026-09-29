package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具结果的审批来源标记。
 *
 * 模型只能读到结果的 output 文本，读不到 part 上的 approvalState；不加标记时无法区分
 * 「用户点了批准」与「自动批准」。
 */
class ApprovalProvenanceTest {
    private fun text(s: String) = UIMessagePart.Text(text = s)

    @Test
    fun autoApprovalIsMarked() {
        val out = appendApprovalProvenance(listOf(text("done")), ToolApprovalState.Auto)
        assertEquals("done\n[approval: auto]", (out.single() as UIMessagePart.Text).text)
    }

    @Test
    fun userApprovalIsMarkedDistinctly() {
        val out = appendApprovalProvenance(listOf(text("done")), ToolApprovalState.Approved)
        assertEquals("done\n[approval: approved-by-user]", (out.single() as UIMessagePart.Text).text)
    }

    @Test
    fun deniedAndAnsweredHaveOwnMarkers() {
        val denied = appendApprovalProvenance(listOf(text("x")), ToolApprovalState.Denied("no"))
        assertEquals("x\n[approval: denied-by-user]", (denied.single() as UIMessagePart.Text).text)
        val answered = appendApprovalProvenance(listOf(text("x")), ToolApprovalState.Answered("{}"))
        assertEquals("x\n[approval: answered-by-user]", (answered.single() as UIMessagePart.Text).text)
    }

    @Test
    fun markerGoesToLastTextBlockAndPreservesOthers() {
        val parts =
            listOf(
                text("first"),
                text("second"),
            )
        val out = appendApprovalProvenance(parts, ToolApprovalState.Auto)
        assertEquals("first", (out[0] as UIMessagePart.Text).text)
        assertEquals("second\n[approval: auto]", (out[1] as UIMessagePart.Text).text)
        assertEquals(2, out.size)
    }

    @Test
    fun textEndingWithNewlineDoesNotGainBlankLine() {
        val out = appendApprovalProvenance(listOf(text("done\n")), ToolApprovalState.Auto)
        assertEquals("done\n[approval: auto]", (out.single() as UIMessagePart.Text).text)
    }

    @Test
    fun noTextBlockLeavesPartsUntouched() {
        val image =
            UIMessagePart.Image(
                url = "file:///tmp/a.png",
            )
        val out = appendApprovalProvenance(listOf(image), ToolApprovalState.Auto)
        assertEquals(1, out.size)
        assertTrue(out.single() is UIMessagePart.Image)
    }
}
