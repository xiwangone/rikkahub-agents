package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信封统一批（1-5 / 1-6 / 2-8）从 [GenerationAgentRun] 抽出的纯函数。
 *
 * 驱动完整 `generateText` 需要 Android Context + Koin 图（见
 * GenerationLoopResumeToolSelectionTest 的同款说明），故只测抽出的纯函数；
 * 调用点（catch 块 / beforeToolCall / toolFailureOutput 覆写 / deniedToolOutput 覆写）
 * 是单行委托，行为由这里覆盖。
 */
class EnvelopeUnifyTest {

    private fun tool(
        id: String,
        state: ToolApprovalState,
        executed: Boolean = false,
    ) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = "tool_$id",
        input = "{}",
        output = if (executed) listOf(UIMessagePart.Text("done")) else emptyList(),
        approvalState = state,
    )

    private fun envelopeOf(part: UIMessagePart.Tool): JsonObject {
        val text = (part.output.single() as UIMessagePart.Text).text
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun envelopeOfParts(parts: List<UIMessagePart>): JsonObject {
        val text = (parts.single() as UIMessagePart.Text).text
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun assertEnvelope(
        env: JsonObject,
        code: Int,
        error: String,
        recovery: String,
    ) {
        assertEquals(code, env["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(env["message"]!!.jsonPrimitive.content.isNotBlank())
        val data = env["data"]!!.jsonObject
        assertEquals(error, data["error"]!!.jsonPrimitive.content)
        assertEquals(recovery, data["recovery"]!!.jsonPrimitive.content)
        assertFalse("must not fall back to the flat shape", "error" in env)
    }

    // ===== 1-5：生成失败落 generation_failed 信封、保留原审批态 =====

    @Test
    fun `generation failure settles auto tool with envelope and keeps auto state`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(tool("t1", ToolApprovalState.Auto)),
        )
        val settled = settleGenerationFailedTools(message, RuntimeException("boom-500"))
        val out = settled.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(ToolApprovalState.Auto, out.approvalState)
        assertTrue(out.isExecuted)
        val env = envelopeOf(out)
        assertEnvelope(env, ToolErrors.SERVER_ERROR, "generation_failed", "retry")
        assertTrue(env["message"]!!.jsonPrimitive.content.contains("RuntimeException"))
        assertTrue(env["message"]!!.jsonPrimitive.content.contains("boom-500"))
        assertTrue(env["message"]!!.jsonPrimitive.content.contains("not executed"))
        assertTrue(env["data"]!!.jsonObject["hint"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `generation failure settles pending tool and keeps pending state`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(tool("t1", ToolApprovalState.Pending)),
        )
        val settled = settleGenerationFailedTools(message, IllegalStateException("parse failed"))
        val out = settled.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(ToolApprovalState.Pending, out.approvalState)
        assertEnvelope(envelopeOf(out), ToolErrors.SERVER_ERROR, "generation_failed", "retry")
    }

    @Test
    fun `generation failure leaves executed auto tool untouched`() {
        // 已执行完的 Auto 工具不能再被翻成失败：它的结果是真的。
        val executed = tool("t1", ToolApprovalState.Auto, executed = true)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(executed))
        val settled = settleGenerationFailedTools(message, RuntimeException("boom-500"))
        assertSame(message, settled)
        assertSame(executed, settled.parts.single())
    }

    @Test
    fun `generation failure leaves denied and answered tools untouched`() {
        val denied = tool("t1", ToolApprovalState.Denied("user said no"))
        val answered = tool("t2", ToolApprovalState.Answered("yes"))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(denied, answered))
        assertSame(message, settleGenerationFailedTools(message, RuntimeException("boom-500")))
    }

    @Test
    fun `generation failure with no matching tools returns same message`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("hello")),
        )
        assertSame(message, settleGenerationFailedTools(message, RuntimeException("boom-500")))
    }

    // ===== 1-6 A：拒绝分流，去标记后走 hardline_blocked =====

    @Test
    fun `hardline denial returns hardline_blocked envelope without marker`() {
        val out = deniedToolOutputFor(
            "hardline-ui:v1:root_filesystem_delete|blocked by safety floor (hardline): recursive delete of root filesystem.",
        )
        assertTrue(out != null)
        val env = envelopeOfParts(out!!)
        assertEnvelope(env, ToolErrors.SERVER_ERROR, "hardline_blocked", "abort")
        assertTrue(env["message"]!!.jsonPrimitive.content.contains("blocked by safety floor"))
        assertFalse(env["message"]!!.jsonPrimitive.content.contains("hardline-ui:v1:"))
        assertEquals(
            "root_filesystem_delete",
            env["data"]!!.jsonObject["hardline_reason_code"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `plain denial returns null for the default permission_denied envelope`() {
        assertNull(deniedToolOutputFor("user said no"))
        assertNull(deniedToolOutputFor(""))
    }

    @Test
    fun `unknown marker code returns null and stays raw`() {
        assertNull(deniedToolOutputFor("hardline-ui:v1:not_a_real_code|some text"))
    }

    // ===== 1-6 B：续跑路径硬线拒绝同一结论 =====

    @Test
    fun `resume-path hardline rejection is hardline_blocked with no retry hint`() {
        val out = hardlineRejectOutput("recursive delete of root filesystem")
        val env = envelopeOfParts(out)
        assertEnvelope(env, ToolErrors.SERVER_ERROR, "hardline_blocked", "abort")
        assertTrue(env["message"]!!.jsonPrimitive.content.contains("blocked by safety floor"))
        assertTrue(env["message"]!!.jsonPrimitive.content.contains("cannot run via the agent"))
        val hint = env["data"]!!.jsonObject["hint"]!!.jsonPrimitive.content
        assertTrue(hint.contains("Do not retry"))
        assertFalse("must not teach the model to retry with corrected arguments", hint.contains("retry with corrected"))
        assertEquals(
            "root_filesystem_delete",
            env["data"]!!.jsonObject["hardline_reason_code"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `resume-path hardline rejection without known code has no reason code extra`() {
        val out = hardlineRejectOutput("some dynamic reason without a stable code")
        val env = envelopeOfParts(out)
        assertEnvelope(env, ToolErrors.SERVER_ERROR, "hardline_blocked", "abort")
        assertFalse("hardline_reason_code" in env["data"]!!.jsonObject)
    }

    // ===== 2-8：墙钟超时分流到 tool_cancelled_wall_clock =====

    @Test
    fun `wall-clock timeout gets tool_cancelled_wall_clock envelope`() {
        // TimeoutCancellationException 的 (message) 构造器是 internal：用 withTimeout 真实掐出一个。
        val timeout = runBlocking {
            try {
                withTimeout(1) { delay(100) }
                error("should have timed out")
            } catch (e: TimeoutCancellationException) {
                e
            }
        }
        val out = toolFailureParts(timeout)
        val env = envelopeOfParts(out)
        assertEnvelope(env, ToolErrors.REQUEST_CANCELLED, "tool_cancelled_wall_clock", "abort")
        assertTrue(env["message"]!!.jsonPrimitive.content.contains("wall-clock"))
        assertEquals(
            "TimeoutCancellationException",
            env["data"]!!.jsonObject["exception"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `ordinary failure still gets tool_failed envelope`() {
        val out = toolFailureParts(RuntimeException("boom-test-marker"))
        val env = envelopeOfParts(out)
        assertEnvelope(env, ToolErrors.INTERNAL_ERROR, "tool_failed", "abort")
        assertTrue(env["message"]!!.jsonPrimitive.content.contains("boom-test-marker"))
        assertEquals(
            "RuntimeException",
            env["data"]!!.jsonObject["exception"]!!.jsonPrimitive.content,
        )
    }
}
