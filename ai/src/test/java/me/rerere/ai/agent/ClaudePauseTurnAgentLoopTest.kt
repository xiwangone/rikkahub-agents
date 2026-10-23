package me.rerere.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.claude.generateClaudeWithPauseTurn
import me.rerere.ai.provider.providers.claude.streamClaudeWithPauseTurn
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudePauseTurnAgentLoopTest {
    private val model = Model(modelId = "claude-pause-test")
    private val setting = ProviderSetting.Claude()

    @Test
    fun `two pause turns retain cumulative usage latest request and actual request count`() = runBlocking {
        for (stream in listOf(false, true)) {
            val provider = PauseTurnProvider()
            val observedContexts = mutableListOf<StepContext>()
            val hooks = object : AgentHooks {
                override suspend fun prepareStep(messages: List<UIMessage>) =
                    StepRequest(messages, TextGenerationParams(model))

                override suspend fun beforeStep(step: StepContext): StepDecision {
                    observedContexts += step
                    return StepDecision.Continue
                }
            }
            val events = mutableListOf<AgentEvent>()
            runAgent(
                AgentRequest(
                    provider = provider,
                    providerSetting = setting,
                    messages = listOf(UIMessage.user("go")),
                    stream = stream,
                    maxSteps = 3,
                    retry = RetryPolicy.NONE,
                ),
                hooks,
            ).collect(events::add)

            val final = (events.last() as AgentEvent.Finished).message
            assertEquals("stream=$stream requests", 3, provider.requestCount)
            assertEquals("stream=$stream stepCount", 3, final.stepCount)
            assertEquals("stream=$stream cumulative usage", TokenUsage(60, 12, 0, 72), final.usage)
            assertEquals("stream=$stream last request usage", TokenUsage(30, 6, 0, 36), final.lastUsage)
            // Context-budget semantics use the latest request, not the cumulative billable usage.
            assertEquals("stream=$stream context tokens", 36, final.contextTokens())
            assertEquals("stream=$stream next step index", 3, observedContexts.last().stepIndex)
            assertEquals("stream=$stream hook cumulative usage", final.usage, observedContexts.last().usage)
            if (stream) {
                val previews = events.filterIsInstance<AgentEvent.MessageUpdated>().map { it.message }
                val intermediate = previews.dropLast(1)
                assertEquals("stream=$stream preview counters remain uncommitted", 0, intermediate.maxOf { it.stepCount })
                assertNull("stream=$stream preview usage remains uncommitted", intermediate.last().usage)
            }
        }
    }

    @Test
    fun `missing final pause-turn usage never falls back to cumulative usage`() = runBlocking {
        for (stream in listOf(false, true)) {
            val provider = PauseTurnProvider(omitFinalUsage = true)
            val events = mutableListOf<AgentEvent>()
            runAgent(
                AgentRequest(
                    provider = provider,
                    providerSetting = setting,
                    messages = listOf(UIMessage.user("go")),
                    stream = stream,
                    maxSteps = 3,
                    retry = RetryPolicy.NONE,
                ),
                object : AgentHooks {
                    override suspend fun prepareStep(messages: List<UIMessage>) =
                        StepRequest(messages, TextGenerationParams(model))
                },
            ).collect(events::add)

            val final = (events.last() as AgentEvent.Finished).message
            assertEquals("stream=$stream requests", 3, provider.requestCount)
            assertEquals("stream=$stream stepCount", 3, final.stepCount)
            assertEquals("stream=$stream billable usage", TokenUsage(30, 6, 0, 36), final.usage)
            assertNull("stream=$stream absent latest request usage", final.lastUsage)
            assertNull("stream=$stream absent context usage", final.contextTokens())
        }
    }

    private inner class PauseTurnProvider(
        private val omitFinalUsage: Boolean = false,
    ) : Provider<ProviderSetting> {
        var requestCount = 0
        private val usage = listOf(TokenUsage(10, 2, 0, 12), TokenUsage(20, 4, 0, 24), TokenUsage(30, 6, 0, 36))
        private val reasons = listOf("pause_turn", "pause_turn", "end_turn")

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult = generateClaudeWithPauseTurn(messages, params.model) {
            val index = requestCount++
            val requestUsage = usage[index].takeUnless { omitFinalUsage && index == 2 }
            TextGenerationResult(
                id = "response-$index",
                model = model.modelId,
                message = UIMessage.assistant("part-$index"),
                finishReason = reasons[index],
                usage = requestUsage,
            )
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> = streamClaudeWithPauseTurn(messages, params.model) {
            val index = requestCount++
            val chunks = mutableListOf<StreamChunk>(
                StreamChunk.TextStart("text-$index"),
                StreamChunk.TextDelta("text-$index", "part-$index"),
                StreamChunk.TextEnd("text-$index"),
            )
            usage[index].takeUnless { omitFinalUsage && index == 2 }
                ?.let { chunks += StreamChunk.Usage(it) }
            chunks += StreamChunk.Finish(reasons[index], "response-$index", model.modelId)
            flow { chunks.forEach { emit(it) } }
        }

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = flow { error("unexpected image generation") }
    }
}
