package me.rerere.ai.agent

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.ZERO

class AgentLoopUsageTest {
    private val model = Model(modelId = "usage-test")
    private val setting = ProviderSetting.OpenAI()

    @Test
    fun `separate model requests sum usage and count steps once`() = runBlocking {
        var calls = 0
        val provider = TestProvider(
            generate = {
                calls++
                TextGenerationResult(
                    id = "response-$calls",
                    model = model.modelId,
                    message = UIMessage.assistant("step$calls"),
                    usage = if (calls == 1) {
                        TokenUsage(promptTokens = 100, completionTokens = 10, cachedTokens = 20, totalTokens = 110)
                    } else {
                        TokenUsage(promptTokens = 200, completionTokens = 20, cachedTokens = 30, totalTokens = 220)
                    },
                )
            },
        )
        var beforeStepCalls = 0
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>) = StepRequest(
                messages,
                TextGenerationParams(model),
            )

            override suspend fun beforeStep(step: StepContext): StepDecision =
                if (beforeStepCalls++ == 1) StepDecision.RetryCurrentStep() else StepDecision.Continue
        }
        val events = mutableListOf<AgentEvent>()

        runAgent(
            AgentRequest(provider, setting, listOf(UIMessage.user("go")), stream = false, retry = RetryPolicy.NONE),
            hooks,
        ).collect(events::add)

        val final = (events.last() as AgentEvent.Finished).message
        assertEquals(2, calls)
        assertEquals(2, final.stepCount)
        assertEquals(TokenUsage(promptTokens = 300, completionTokens = 30, cachedTokens = 50, totalTokens = 330), final.usage)
        assertEquals(TokenUsage(promptTokens = 200, completionTokens = 20, cachedTokens = 30, totalTokens = 220), final.lastUsage)
    }

    @Test
    fun `failed retry attempt usage remains preview only and successful request commits once`() = runBlocking {
        var attempts = 0
        val provider = TestProvider(
            stream = {
                attempts++
                flow {
                    emit(StreamChunk.Usage(if (attempts == 1) {
                        TokenUsage(promptTokens = 999, completionTokens = 999, totalTokens = 1998)
                    } else {
                        TokenUsage(promptTokens = 50, completionTokens = 5, totalTokens = 55)
                    }))
                    if (attempts == 1) throw IOException("broken stream")
                    emit(StreamChunk.Finish(finishReason = "stop"))
                }
            },
        )
        val events = mutableListOf<AgentEvent>()
        runAgent(
            AgentRequest(
                provider,
                setting,
                listOf(UIMessage.user("go")),
                retry = RetryPolicy(maxRetries = 1, delayFor = { ZERO }),
            ),
            hooks(),
        ).collect(events::add)

        val previews = events.filterIsInstance<AgentEvent.MessageUpdated>().map { it.message }
        val final = (events.last() as AgentEvent.Finished).message
        assertEquals(2, attempts)
        assertEquals(0, previews.dropLast(1).maxOf { it.stepCount })
        assertNull(previews.dropLast(1).last().usage)
        assertEquals(1, final.stepCount)
        assertEquals(50, final.usage?.promptTokens)
        assertEquals(5, final.usage?.completionTokens)
        assertEquals(final.usage, final.lastUsage)
    }

    @Test
    fun `cancelled partial stream does not commit latest usage or step count`() = runBlocking {
        val provider = TestProvider(
            stream = {
                flow {
                    emit(StreamChunk.Usage(TokenUsage(promptTokens = 123, completionTokens = 45, totalTokens = 168)))
                    throw CancellationException("user stopped")
                }
            },
        )
        val events = mutableListOf<AgentEvent>()

        try {
            runAgent(AgentRequest(provider, setting, listOf(UIMessage.user("go"))), hooks()).collect(events::add)
        } catch (_: CancellationException) {
            // Expected: cancellation propagates; only already-emitted preview snapshots are inspected.
        }

        val preview = events.filterIsInstance<AgentEvent.MessageUpdated>().last().message
        assertEquals(0, preview.stepCount)
        assertNull(preview.usage)
        assertNull(preview.lastUsage)
        assertNull(preview.finishedAt)
    }

    private fun hooks() = object : AgentHooks {
        override suspend fun prepareStep(messages: List<UIMessage>) = StepRequest(messages, TextGenerationParams(model))
    }

    private class TestProvider(
        private val generate: suspend () -> TextGenerationResult = { error("unexpected generateText") },
        private val stream: suspend () -> Flow<StreamChunk> = { error("unexpected streamText") },
    ) : Provider<ProviderSetting> {
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult = generate()

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> = stream()

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = flow { error("unexpected generateImage") }
    }
}
