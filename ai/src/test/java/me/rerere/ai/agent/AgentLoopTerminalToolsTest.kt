package me.rerere.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTerminalToolsTest {
    private val model = Model(modelId = "terminal-tools-test")
    private val setting = ProviderSetting.OpenAI()

    @Test
    fun `after tool result reminder final retry has no tools and returns assistant summary`() = runBlocking {
        val seenToolCounts = mutableListOf<Int>()
        val seenMessages = mutableListOf<List<UIMessage>>()
        var toolExecutions = 0
        var providerCalls = 0

        val tool = Tool("work", "Synthetic test tool", execute = {
            toolExecutions++
            listOf(UIMessagePart.Text("work finished"))
        })
        val provider = object : Provider<ProviderSetting> {
            override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

            override suspend fun generateText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): TextGenerationResult {
                providerCalls++
                seenToolCounts += params.tools.size
                seenMessages += messages
                if (providerCalls == 1) {
                    return TextGenerationResult(
                        id = "tool-call",
                        model = model.modelId,
                        message = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Tool("work-call", tool.name, "{}")),
                        ),
                        finishReason = "tool_calls",
                    )
                }
                return TextGenerationResult(
                    id = "summary",
                    model = model.modelId,
                    message = UIMessage.assistant("Finished work; summary delivered."),
                    finishReason = "stop",
                )
            }

            override suspend fun streamText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): Flow<me.rerere.ai.ui.StreamChunk> = flow { error("stream not used") }

            override suspend fun generateImage(
                providerSetting: ProviderSetting,
                params: ImageGenerationParams,
            ): Flow<ImageGenerationItem> = flow { error("image not used") }
        }

        val hooks = object : AgentHooks {
            private var terminalSummary = false
            private var terminalPending = false
            private var terminalInFlight = false
            private var terminalHistory: List<UIMessage>? = null

            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest =
                StepRequest(
                    terminalHistory ?: messages,
                    TextGenerationParams(model),
                    toolsEnabled = !terminalSummary,
                )

            override suspend fun afterToolExecution(step: StepContext) {
                val assistant = step.messages.last()
                val updatedParts = assistant.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == "work-call") {
                        part.copy(output = part.output + UIMessagePart.Text("TIME BUDGET NOTICE"))
                    } else part
                }
                // Mirror GenerationAgentRun: the reminder is included in the completed tool result.
                terminalHistory = step.messages.dropLast(1) + assistant.copy(parts = updatedParts)
                terminalSummary = true
                terminalPending = true
            }

            override fun deferGeneration(): Boolean = terminalPending

            override suspend fun beforeStep(step: StepContext): StepDecision = when {
                // 与 GenerationAgentRun 同构：最终总结已发出→结束；否则只重发一次。
                terminalInFlight -> StepDecision.Stop(AgentFinishReason.Completed)
                terminalPending -> {
                    terminalPending = false
                    terminalInFlight = true
                    StepDecision.RetryCurrentStep(terminalSummary = true)
                }
                else -> StepDecision.Continue
            }
        }

        val events = mutableListOf<AgentEvent>()
        runAgent(
            AgentRequest(
                provider = provider,
                providerSetting = setting,
                messages = listOf(UIMessage.user("start")),
                tools = listOf(tool),
                stream = false,
                maxSteps = 2,
                retry = RetryPolicy.NONE,
            ),
            hooks,
        ).collect(events::add)

        assertEquals(1, toolExecutions)
        // ①工具调用 → ②唯一一次无工具总结（afterToolExecution 返回 true 后本轮不再生成）
        assertEquals(2, providerCalls)
        assertEquals(listOf(1, 0), seenToolCounts)
        assertTrue(seenMessages.last().any { it.getTools().any { t -> t.output.any { p -> p is UIMessagePart.Text && p.text == "TIME BUDGET NOTICE" } } })
        assertTrue((events.last() as AgentEvent.Finished).message.toText().contains("Finished work"))
        assertFalse((events.last() as AgentEvent.Finished).reason is AgentFinishReason.StepLimitReached)
    }
}
