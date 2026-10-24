package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.TokenUsage
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingOutputFlushTest {
    @Test
    fun completedToolSnapshotIsPublishedBeforeNextProviderRequestAndSurvivesStop() = runBlocking {
        val model = Model(modelId = "synthetic-model", displayName = "Synthetic")
        val setting = ProviderSetting.OpenAI(models = listOf(model), apiKey = "")
        val pending = PendingOutputFlush<UIMessage>()
        val timeline = mutableListOf<String>()
        var delivered: UIMessage? = null
        var providerCalls = 0
        var deliveredAtSecondRequest = false
        val secondRequest = CompletableDeferred<Unit>()
        val resultTool = Tool(
            name = "synthetic_tool",
            description = "test tool",
            execute = { listOf(UIMessagePart.Text("2")) },
        )
        val provider = object : Provider<ProviderSetting> {
            override suspend fun listModels(providerSetting: ProviderSetting) = listOf(model)
            override suspend fun generateText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): TextGenerationResult = error("unexpected generateText call")
            override suspend fun streamText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): Flow<StreamChunk> {
                providerCalls++
                timeline += "request-$providerCalls"
                if (providerCalls == 1) {
                    return flowOf(
                        StreamChunk.ToolCallStart("tool-1", "synthetic_tool"),
                        StreamChunk.ToolCallDelta("tool-1", inputDelta = "{}"),
                        StreamChunk.ToolCallEnd("tool-1"),
                        StreamChunk.Usage(TokenUsage(promptTokens = 11, completionTokens = 3, totalTokens = 14)),
                    )
                }
                deliveredAtSecondRequest = delivered?.let(::hasToolResult2) == true
                secondRequest.complete(Unit)
                return flow { awaitCancellation() }
            }
            override suspend fun generateImage(
                providerSetting: ProviderSetting,
                params: ImageGenerationParams,
            ): Flow<ImageGenerationItem> = error("unexpected generateImage call")
        }
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                pending.flush { snapshot ->
                    delivered = snapshot
                    timeline += "published-tool-result"
                }
                return StepRequest(messages, TextGenerationParams(model = model))
            }
        }

        val job = launch {
            runAgent(
                AgentRequest(
                    provider = provider,
                    providerSetting = setting,
                    messages = listOf(UIMessage.user("prompt")),
                    tools = listOf(resultTool),
                ),
                hooks,
            ).collect { event ->
                if (event is AgentEvent.MessageUpdated && hasToolResult2(event.message)) {
                    pending.set(event.message)
                }
            }
        }
        secondRequest.await()
        job.cancelAndJoin()

        assertEquals(2, providerCalls)
        assertTrue("Completed tool output must be delivered before request 2: $timeline", deliveredAtSecondRequest)
        assertTrue(timeline.indexOf("published-tool-result") < timeline.indexOf("request-2"))
        val tool = delivered?.parts?.filterIsInstance<UIMessagePart.Tool>()?.single()
        assertNotNull(tool)
        assertEquals(listOf("2"), tool?.output?.filterIsInstance<UIMessagePart.Text>()?.map { it.text })
        assertFalse(tool?.approvalState is ToolApprovalState.Denied)
        assertEquals(11, delivered?.usage?.promptTokens)
        assertEquals(3, delivered?.usage?.completionTokens)
    }

    @Test
    fun emptyBufferDoesNotInventOutputAndCancellationFromDeliveryIsPropagated() = runBlocking {
        val pending = PendingOutputFlush<String>()
        var delivered = false
        pending.flush { delivered = true }
        assertFalse(delivered)
        pending.set("result")
        val failure = kotlinx.coroutines.CancellationException("stop")
        try {
            pending.flush { throw failure }
            throw AssertionError("expected delivery cancellation")
        } catch (caught: kotlinx.coroutines.CancellationException) {
            assertEquals("stop", caught.message)
        }
        // A failed/cancelled delivery consumed its snapshot exactly as the prior production path did.
        pending.flush { delivered = true }
        assertFalse(delivered)
    }

    private fun hasToolResult2(message: UIMessage): Boolean = message.parts
        .filterIsInstance<UIMessagePart.Tool>()
        .any { tool -> tool.output.filterIsInstance<UIMessagePart.Text>().any { it.text == "2" } }
}
