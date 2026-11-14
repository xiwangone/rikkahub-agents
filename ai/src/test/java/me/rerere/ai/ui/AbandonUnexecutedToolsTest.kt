package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.agent.ToolErrorEnvelope
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UIMessage.abandonUnexecutedTools] 的改写范围与「保留消息」语义。
 *
 * 这条路径在生成开始前的 `checkInvalidMessages` 里被调用：此前它会**整条删除**含未执行工具的消息，
 * 连带丢掉本轮已生成的 AI 文本与工具调用记录（用户与模型都失去上下文）。改为补一个「未执行（中断）」
 * 的合成结果后保留消息。
 *
 * 与 [FinishPendingToolsTest] 的分工：`finishPendingTools` 只改写用户已表态的 Pending/Approved 并
 * 刻意放过 Auto；本函数覆盖**所有未执行工具**（含自动批准后被中断的 Auto）。
 *
 * 理由按 `executionStartedAt` 分叉（`interruptedAttemptReason` 参数）：从未开始的告知「未执行」；
 * 开始过但结果没回来的告知「状态未知、先核实」——后者副作用可能已发生（shell 跑完才被杀），
 * 误告「未执行」会诱导模型重跑同一条命令。两类都要测，且 Approved / Auto 两条审批态各覆盖
 * （Auto 曾漏出 needsImmediatePersist 的面包屑落盘，进程被杀后磁盘上无从识别）。
 */
class AbandonUnexecutedToolsTest {
    // startedAt == null（从未开始）一路的理由：保持仓内既有文案不动。
    private val reason =
        "interrupted_before_execution: the turn was interrupted before this tool ran; it did NOT execute."

    // startedAt != null（执行中结果丢失）一路的理由：与 GenerationLoop.INTERRUPTED_UNKNOWN_OUTCOME_REASON 同文。
    private val unknownReason =
        "interrupted_unknown_outcome: a previous attempt to execute this tool started but did not " +
            "complete (process killed mid-execute). The side effect MAY OR MAY NOT have happened. " +
            "Verify the target state before retrying — do not blindly re-run the same call."

    private fun tool(
        state: ToolApprovalState,
        executed: Boolean = false,
        startedAt: Long? = null,
    ) = UIMessagePart.Tool(
        toolCallId = "call_1",
        toolName = "shizuku_exec",
        input = "{}",
        output = if (executed) listOf(UIMessagePart.Text(text = "ok")) else emptyList(),
        executionStartedAt = startedAt,
        approvalState = state,
    )

    private fun after(tool: UIMessagePart.Tool): UIMessagePart.Tool = after(tool, reason)

    private fun after(
        tool: UIMessagePart.Tool,
        interruptedAttemptReason: String,
    ): UIMessagePart.Tool {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val updated = message.abandonUnexecutedTools(reason, interruptedAttemptReason)
        return updated.parts.filterIsInstance<UIMessagePart.Tool>().single()
    }

    @Test
    fun `auto unexecuted tool is terminated`() {
        val out = after(tool(ToolApprovalState.Auto))
        assertEquals(ToolApprovalState.Denied(reason), out.approvalState)
        assertTrue("synthetic output must mark the tool as executed", out.isExecuted)
    }

    @Test
    fun `pending unexecuted tool is terminated`() {
        val out = after(tool(ToolApprovalState.Pending))
        assertEquals(ToolApprovalState.Denied(reason), out.approvalState)
        assertTrue(out.isExecuted)
    }

    @Test
    fun `approved unexecuted tool is terminated`() {
        val out = after(tool(ToolApprovalState.Approved))
        assertEquals(ToolApprovalState.Denied(reason), out.approvalState)
        assertTrue(out.isExecuted)
    }

    @Test
    fun `executed tool is left untouched`() {
        val executed = tool(ToolApprovalState.Auto, executed = true)
        val updated = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(executed)).abandonUnexecutedTools(reason)
        assertSame(executed, updated.parts.filterIsInstance<UIMessagePart.Tool>().single())
    }

    @Test
    fun `message without tools is returned unchanged`() {
        val message =
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hello")))
        assertSame(message, message.abandonUnexecutedTools(reason))
    }

    @Test
    fun `text parts survive and only unexecuted tools are terminated`() {
        val text = UIMessagePart.Text("I will run the command now")
        val running = tool(ToolApprovalState.Auto)
        val done = tool(ToolApprovalState.Approved, executed = true)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(text, running, done))

        val updated = message.abandonUnexecutedTools(reason)

        assertEquals(3, updated.parts.size)
        assertSame(text, updated.parts[0])
        val tools = updated.parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(ToolApprovalState.Denied(reason), tools[0].approvalState)
        assertTrue(tools[0].isExecuted)
        assertSame(done, tools[1])
    }

    // ===== 中断二态分叉：executionStartedAt 有值 → 「状态未知、先核实」 =====

    @Test
    fun `approved interrupted attempt gets unknown-outcome reason`() {
        val out = after(tool(ToolApprovalState.Approved, startedAt = 1_700_000_000_000L), unknownReason)
        assertEquals(ToolApprovalState.Denied(unknownReason), out.approvalState)
        assertTrue(out.isExecuted)
        val error = out.output.single() as UIMessagePart.Text
        assertTrue(error.text.contains("interrupted_unknown_outcome"))
    }

    @Test
    fun `auto interrupted attempt gets unknown-outcome reason`() {
        val out = after(tool(ToolApprovalState.Auto, startedAt = 1_700_000_000_000L), unknownReason)
        assertEquals(ToolApprovalState.Denied(unknownReason), out.approvalState)
        assertTrue(out.isExecuted)
        val error = out.output.single() as UIMessagePart.Text
        assertTrue(error.text.contains("interrupted_unknown_outcome"))
    }

    @Test
    fun `unstarted auto tool keeps not-executed reason`() {
        // startedAt == null：确实从未派发，「未执行」是事实，不得误升级为「状态未知」。
        val out = after(tool(ToolApprovalState.Auto))
        assertEquals(ToolApprovalState.Denied(reason), out.approvalState)
        assertTrue(out.isExecuted)
        val error = out.output.single() as UIMessagePart.Text
        assertTrue(error.text.contains("interrupted_before_execution"))
    }

    @Test
    fun `mixed interrupted and unstarted tools get respective reasons`() {
        val started = tool(ToolApprovalState.Auto, startedAt = 1_700_000_000_000L)
        val unstarted = tool(ToolApprovalState.Approved)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(started, unstarted))

        val updated = message.abandonUnexecutedTools(reason, unknownReason)

        val tools = updated.parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals(ToolApprovalState.Denied(unknownReason), tools[0].approvalState)
        assertEquals(ToolApprovalState.Denied(reason), tools[1].approvalState)
    }

    // ===== v2 信封形状：合成输出必须走 ToolErrorEnvelope，不再产扁平形状 =====

    private fun envelopeOf(tool: UIMessagePart.Tool): JsonObject {
        val text = (tool.output.single() as UIMessagePart.Text).text
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun assertInterruptedEnvelope(
        env: JsonObject,
        recovery: String,
        messageContains: String,
    ) {
        assertEquals(ToolErrorEnvelope.REQUEST_CANCELLED, env["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(env["message"]!!.jsonPrimitive.content.contains(messageContains))
        val data = env["data"]!!.jsonObject
        assertEquals("interrupted", data["error"]!!.jsonPrimitive.content)
        assertEquals(recovery, data["recovery"]!!.jsonPrimitive.content)
        assertEquals("interrupted", data["status"]!!.jsonPrimitive.content)
        assertFalse("must not fall back to the flat shape", "error" in env)
    }

    @Test
    fun `unstarted tool output is interrupted envelope with retry recovery`() {
        val out = after(tool(ToolApprovalState.Auto))
        assertInterruptedEnvelope(envelopeOf(out), ToolErrorEnvelope.RECOVERY_RETRY, "interrupted_before_execution")
    }

    @Test
    fun `interrupted attempt output is interrupted envelope with abort recovery`() {
        val out = after(tool(ToolApprovalState.Approved, startedAt = 1_700_000_000_000L), unknownReason)
        assertInterruptedEnvelope(envelopeOf(out), ToolErrorEnvelope.RECOVERY_ABORT, "interrupted_unknown_outcome")
    }

    // ===== 已是终态 Denied：保留原理由，只补拒绝信封 =====

    @Test
    fun `already denied tool keeps its reason and gets permission_denied envelope`() {
        val out = after(tool(ToolApprovalState.Denied("user said no")))
        assertEquals(ToolApprovalState.Denied("user said no"), out.approvalState)
        assertTrue(out.isExecuted)
        val env = envelopeOf(out)
        assertEquals(ToolErrorEnvelope.SERVER_ERROR, env["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(env["message"]!!.jsonPrimitive.content.contains("denied by user"))
        assertTrue(env["message"]!!.jsonPrimitive.content.contains("user said no"))
        val data = env["data"]!!.jsonObject
        assertEquals("permission_denied", data["error"]!!.jsonPrimitive.content)
        assertEquals(ToolErrorEnvelope.RECOVERY_ABORT, data["recovery"]!!.jsonPrimitive.content)
        assertFalse("denied fork must not carry the interrupted status", "status" in data)
    }

    @Test
    fun `already denied tool with blank reason gets default reason text`() {
        val out = after(tool(ToolApprovalState.Denied("")))
        assertEquals(ToolApprovalState.Denied(""), out.approvalState)
        val env = envelopeOf(out)
        assertTrue(env["message"]!!.jsonPrimitive.content.contains("No reason provided"))
    }

    @Test
    fun `already denied interrupted attempt keeps unknown-outcome envelope`() {
        val out = after(tool(ToolApprovalState.Denied(unknownReason), startedAt = 1_700_000_000_000L), unknownReason)
        assertEquals(ToolApprovalState.Denied(unknownReason), out.approvalState)
        assertInterruptedEnvelope(envelopeOf(out), ToolErrorEnvelope.RECOVERY_ABORT, "interrupted_unknown_outcome")
    }

    @Test
    fun `already denied hardline tool gets hardline_blocked envelope without marker`() {
        // 去标记变换由调用方（app 侧）传入；此处用与 HardlineCommandGuard.modelFacingReason
        // 同契约的 lambda：只去认得的标记，其余原样返回。
        val stripMarker: (String) -> String = { text ->
            if (text.startsWith("hardline-ui:v1:")) text.substringAfter('|') else text
        }
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                tool(ToolApprovalState.Denied("hardline-ui:v1:root_filesystem_delete|blocked by safety floor")),
            ),
        )
        val updated = message.abandonUnexecutedTools(reason, unknownReason, stripMarker)
        val out = updated.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(
            ToolApprovalState.Denied("hardline-ui:v1:root_filesystem_delete|blocked by safety floor"),
            out.approvalState,
        )
        val env = envelopeOf(out)
        assertEquals(ToolErrorEnvelope.SERVER_ERROR, env["code"]!!.jsonPrimitive.content.toInt())
        assertEquals("blocked by safety floor", env["message"]!!.jsonPrimitive.content)
        assertFalse(env["message"]!!.jsonPrimitive.content.contains("hardline-ui:v1:"))
        val data = env["data"]!!.jsonObject
        assertEquals("hardline_blocked", data["error"]!!.jsonPrimitive.content)
        assertEquals(ToolErrorEnvelope.RECOVERY_ABORT, data["recovery"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hardline marker stays a plain denial without the transform`() {
        // 默认变换是 identity：ai 侧不知道标记格式，不去也不认，退回 permission_denied。
        val out = after(tool(ToolApprovalState.Denied("hardline-ui:v1:root_filesystem_delete|blocked by safety floor")))
        val env = envelopeOf(out)
        assertEquals("permission_denied", env["data"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        assertTrue(env["message"]!!.jsonPrimitive.content.contains("hardline-ui:v1:"))
    }
}
