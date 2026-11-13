package me.rerere.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentLoop] 的失败回灌必须走 v2 信封（[ToolErrorEnvelope]，JSON-RPC 2.0 形状），
 * 不得再产旧扁平形状 `{"error": "..."}`。
 *
 * 三路失败各有自己的业务码 / 分类码 / recovery：
 * 拒绝 → permission_denied / -32000 / abort；
 * 未知工具 → not_found / -32601 / fix_param；
 * 执行抛错 → tool_failed / -32603 / abort。
 */
class AgentLoopErrorEnvelopeTest {

    private val model = Model(modelId = "error-envelope-test")
    private val setting = ProviderSetting.OpenAI()

    /** 跑一轮循环：首步返回 [toolPart]，次步收工具输出并返回总结；返回工具输出的 JSON。 */
    private fun runLoopAndCaptureToolOutput(
        toolPart: UIMessagePart.Tool,
        tools: List<Tool>,
    ): JsonObject = runBlocking {
        var providerCalls = 0
        var captured: JsonObject? = null

        val provider = object : Provider<ProviderSetting> {
            override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

            override suspend fun generateText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): TextGenerationResult {
                providerCalls++
                if (providerCalls == 1) {
                    return TextGenerationResult(
                        id = "tool-call",
                        model = model.modelId,
                        message = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(toolPart),
                        ),
                        finishReason = "tool_calls",
                    )
                }
                // 次步收到的消息里带有工具输出（已由 AgentLoop 写回）。
                val tool = messages.flatMap { it.parts }
                    .filterIsInstance<UIMessagePart.Tool>()
                    .first { it.output.isNotEmpty() }
                val text = (tool.output[0] as UIMessagePart.Text).text
                captured = Json.parseToJsonElement(text).jsonObject
                return TextGenerationResult(
                    id = "summary",
                    model = model.modelId,
                    message = UIMessage.assistant("done"),
                    finishReason = "stop",
                )
            }

            override suspend fun streamText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): Flow<StreamChunk> = flow { error("stream not used") }

            override suspend fun generateImage(
                providerSetting: ProviderSetting,
                params: ImageGenerationParams,
            ): Flow<ImageGenerationItem> = flow { error("image not used") }
        }

        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>) =
                StepRequest(messages, TextGenerationParams(model))
        }

        runAgent(
            AgentRequest(
                provider = provider,
                providerSetting = setting,
                messages = listOf(UIMessage.user("start")),
                tools = tools,
                stream = false,
                maxSteps = 2,
                retry = RetryPolicy.NONE,
            ),
            hooks,
        ).collect { /* 事件不断言，只关心工具输出 */ }

        assertEquals(2, providerCalls)
        return@runBlocking captured!!
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
        assertTrue("must not fall back to the flat v1 shape", "error" !in env)
    }

    @Test
    fun `denied tool produces permission_denied envelope`() {
        val env = runLoopAndCaptureToolOutput(
            toolPart = UIMessagePart.Tool(
                toolCallId = "denied-call",
                toolName = "work",
                input = "{}",
                approvalState = ToolApprovalState.Denied("user said no"),
            ),
            tools = listOf(Tool("work", "no-op", execute = { emptyList() })),
        )
        assertEnvelope(env, ToolErrorEnvelope.SERVER_ERROR, "permission_denied", "abort")
        assertTrue(env["message"]!!.jsonPrimitive.content.contains("denied by user"))
    }

    @Test
    fun `unknown tool produces not_found envelope with hint`() {
        val env = runLoopAndCaptureToolOutput(
            toolPart = UIMessagePart.Tool("nf-call", "no-such-tool", "{}"),
            tools = emptyList(),
        )
        assertEnvelope(env, ToolErrorEnvelope.METHOD_NOT_FOUND, "not_found", "fix_param")
        assertTrue(env["message"]!!.jsonPrimitive.content.contains("no-such-tool"))
        assertTrue(env["data"]!!.jsonObject["hint"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `failing tool produces tool_failed envelope with exception extra`() {
        val env = runLoopAndCaptureToolOutput(
            toolPart = UIMessagePart.Tool("fail-call", "work", "{}"),
            tools = listOf(
                Tool("work", "Synthetic failing tool", execute = {
                    throw RuntimeException("boom-test-marker")
                }),
            ),
        )
        assertEnvelope(env, ToolErrorEnvelope.INTERNAL_ERROR, "tool_failed", "abort")
        val data = env["data"]!!.jsonObject
        assertEquals("RuntimeException", data["exception"]!!.jsonPrimitive.content)
        assertTrue(data["error"]!!.jsonPrimitive.content.isNotBlank())
    }
}
