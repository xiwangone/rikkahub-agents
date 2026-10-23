package me.rerere.rikkahub.data.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.ZERO
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryPolicy
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
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationStreamProgressTest {
    private val model = Model(modelId = "synthetic-model", displayName = "Synthetic")
    private val setting = ProviderSetting.OpenAI(models = listOf(model), apiKey = "")

    @Test
    fun slowTextChunksThenCleanCloseAreTrackedBeforeCoalescingAndNeverRetried() = runBlocking {
        val result = exercise(maxRetries = 2) {
            flow {
                emit(StreamChunk.TextDelta("answer", "slow "))
                delay(150)
                emit(StreamChunk.TextDelta("answer", "chunk response"))
                delay(150)
            }
        }

        assertNull(result.failure)
        assertEquals(1, result.provider.streamCalls)
        assertTrue(result.retries.isEmpty())
        assertTrue(result.progress.receivedAnyChunk)
        assertTrue(result.progress.receivedMeaningfulOutput)
        assertEquals("slow chunk response", result.finalMessage?.toText())
    }

    @Test
    fun fastBurstCompletionFlushesCoalescedTextWithoutLosingTheFinalMessage() = runBlocking {
        val result = exercise(maxRetries = 2, coalescerClock = { 0L }) {
            flow {
                emit(StreamChunk.TextDelta("answer", "fast "))
                emit(StreamChunk.TextDelta("answer", "burst "))
                emit(StreamChunk.TextDelta("answer", "response"))
            }
        }

        assertNull(result.failure)
        assertEquals(1, result.provider.streamCalls)
        assertTrue(result.retries.isEmpty())
        assertTrue(result.progress.receivedMeaningfulOutput)
        assertEquals("fast burst response", result.finalMessage?.toText())
    }

    @Test
    fun singleBufferedChunkAtCleanCloseIsNotClearedWithItsBackingList() = runBlocking {
        val result = exercise(maxRetries = 2, coalescerClock = { 0L }) {
            flowOf(StreamChunk.TextDelta("answer", "single tail"))
        }

        assertNull(result.failure)
        assertEquals(1, result.provider.streamCalls)
        assertTrue(result.retries.isEmpty())
        assertEquals("single tail", result.finalMessage?.toText())
    }

    @Test
    fun toolCallExecutesThenFinalTextAndUsageAccumulateAcrossSteps() = runBlocking {
        val toolExecutions = mutableListOf<String>()
        val tool = Tool(
            name = "synthetic_tool",
            description = "test tool",
            execute = { args ->
                toolExecutions += args.toString()
                listOf(UIMessagePart.Text("tool-result"))
            },
        )
        val result = exercise(
            maxRetries = 1,
            tools = listOf(tool),
            stream = { call ->
                when (call) {
                    1 -> flow {
                        emit(StreamChunk.ToolCallStart("call-1", "synthetic_tool"))
                        delay(150)
                        emit(StreamChunk.ToolCallDelta("call-1", inputDelta = "{\"value\":\"x\"}"))
                        emit(StreamChunk.ToolCallEnd("call-1"))
                        emit(StreamChunk.Usage(TokenUsage(promptTokens = 8, completionTokens = 2, totalTokens = 10)))
                    }
                    2 -> flow {
                        emit(StreamChunk.TextDelta("answer", "final answer"))
                        emit(StreamChunk.Usage(TokenUsage(promptTokens = 9, completionTokens = 3, totalTokens = 12)))
                    }
                    else -> error("unexpected provider stream call $call")
                }
            },
        )

        assertNull(result.failure)
        assertEquals(2, result.provider.streamCalls)
        assertEquals(listOf("{\"value\":\"x\"}"), toolExecutions)
        assertEquals(listOf("final answer"), result.finalMessage?.parts?.filterIsInstance<UIMessagePart.Text>()?.map { it.text })
        assertEquals(TokenUsage(promptTokens = 17, completionTokens = 5, totalTokens = 22), result.finalMessage?.usage)
        assertEquals(listOf(0, 0), result.hooks.resetAttempts)
        assertTrue(result.retries.isEmpty())
    }

    @Test
    fun partialTextThenIoFailureIsPropagatedWithoutReplay() = runBlocking {
        val failure = IOException("synthetic disconnect after partial text")
        val result = exercise(maxRetries = 3) {
            flow {
                emit(StreamChunk.TextDelta("answer", "partial output"))
                delay(150)
                throw failure
            }
        }

        assertFailurePreserved(failure, result.failure)
        assertEquals(1, result.provider.streamCalls)
        assertTrue(result.retries.isEmpty())
        assertTrue(result.progress.receivedMeaningfulOutput)
        assertTrue(
            "Partial text should be visible before the provider failure; events=${result.events}",
            result.events.filterIsInstance<AgentEvent.MessageUpdated>().any { it.message.toText() == "partial output" },
        )
    }

    @Test
    fun partialToolNameOrArgumentsThenIoFailureIsNeverReplayed() = runBlocking {
        val failure = IOException("synthetic disconnect after partial tool call")
        val partialToolStreams = listOf<Flow<StreamChunk>>(
            flow {
                emit(StreamChunk.ToolCallStart("call-name", ""))
                emit(StreamChunk.ToolCallDelta("call-name", toolNameDelta = "synthetic_"))
                throw failure
            },
            flow {
                emit(StreamChunk.ToolCallStart("call-args", "synthetic_tool"))
                delay(150)
                emit(StreamChunk.ToolCallDelta("call-args", inputDelta = "{\"value\":"))
                throw failure
            },
        )

        for (stream in partialToolStreams) {
            val result = exercise(maxRetries = 3) { stream }
            assertFailurePreserved(failure, result.failure)
            assertEquals(1, result.provider.streamCalls)
            assertTrue(result.retries.isEmpty())
            assertTrue(result.progress.receivedMeaningfulOutput)
        }
    }

    @Test
    fun userCancellationBeforeAnyOutputIsNotConvertedToEmptyOrRetried() = runBlocking {
        val cancellation = CancellationException("synthetic user stop before output")
        val result = exercise(maxRetries = 3) { flow { throw cancellation } }

        assertFailurePreserved(cancellation, result.failure)
        assertEquals(1, result.provider.streamCalls)
        assertTrue(result.retries.isEmpty())
        assertFalse(result.progress.receivedAnyChunk)
    }

    @Test
    fun userCancellationAfterPartialOutputIsNotReplayed() = runBlocking {
        val cancellation = CancellationException("synthetic user stop after partial output")
        val result = exercise(maxRetries = 3) {
            flow {
                emit(StreamChunk.TextDelta("answer", "already shown"))
                delay(150)
                throw cancellation
            }
        }

        assertFailurePreserved(cancellation, result.failure)
        assertEquals(1, result.provider.streamCalls)
        assertTrue(result.retries.isEmpty())
        assertTrue(result.progress.receivedMeaningfulOutput)
        assertTrue(result.events.filterIsInstance<AgentEvent.MessageUpdated>().any {
            it.message.toText() == "already shown"
        })
    }

    @Test
    fun downstreamCancellationDuringCoalescedDeliveryKeepsOriginalCancellation() = runBlocking {
        val cancellation = CancellationException("synthetic downstream stop")
        val result = exercise(
            maxRetries = 3,
            onEvent = { event ->
                if (event is AgentEvent.MessageUpdated && event.message.toText() == "downstream partial") {
                    throw cancellation
                }
            },
        ) {
            flow {
                emit(StreamChunk.TextDelta("answer", "downstream "))
                delay(150)
                emit(StreamChunk.TextDelta("answer", "partial"))
            }
        }

        assertFailurePreserved(cancellation, result.failure)
        assertEquals(1, result.provider.streamCalls)
        assertTrue(result.retries.isEmpty())
        assertTrue(result.progress.receivedMeaningfulOutput)
    }

    @Test
    fun usageOnlyAndMarkerOnlyStreamsAreNonEmptyButNotMeaningful() = runBlocking {
        val usageOnly = exercise(maxRetries = 2) {
            flowOf(StreamChunk.Usage(TokenUsage(promptTokens = 4, completionTokens = 1, totalTokens = 5)))
        }
        val markerOnly = exercise(maxRetries = 2) {
            flow {
                emit(StreamChunk.TextStart("marker"))
                delay(150)
                emit(StreamChunk.TextEnd("marker"))
            }
        }

        for (result in listOf(usageOnly, markerOnly)) {
            assertNull(result.failure)
            assertEquals(1, result.provider.streamCalls)
            assertTrue(result.retries.isEmpty())
            assertTrue(result.progress.receivedAnyChunk)
            assertFalse(result.progress.receivedMeaningfulOutput)
        }
    }

    @Test
    fun upstreamFailureDuringSlowMarkerConsumerRetriesWithoutFlowTransparencyViolation() = runBlocking {
        var request = 0
        var delayedMarker = false
        val result = exercise(
            maxRetries = 1,
            onEvent = { event ->
                if (!delayedMarker && event is AgentEvent.MessageUpdated &&
                    event.message.parts.any { part -> part is UIMessagePart.Text && part.text.isEmpty() }
                ) {
                    delayedMarker = true
                    delay(200)
                }
            },
        ) {
            flow {
                if (request++ == 0) {
                    emit(StreamChunk.TextStart("empty-marker"))
                    delay(50)
                    throw IOException("synthetic disconnect while marker consumer is slow")
                }
                emit(StreamChunk.TextDelta("answer", "recovered after slow marker"))
            }
        }

        assertTrue("The marker event should have held the downstream collector", delayedMarker)
        assertNull("The upstream failure should retry, not become a flow transparency error", result.failure)
        assertEquals(2, result.provider.streamCalls)
        assertEquals(listOf(1), result.retries)
        assertEquals("recovered after slow marker", result.finalMessage?.toText())
    }

    @Test
    fun trueEmptyStreamRetriesAndRecoversOnce() = runBlocking {
        var attempt = 0
        val result = exercise(maxRetries = 1) {
            flow {
                if (attempt++ == 0) return@flow
                emit(StreamChunk.TextDelta("answer", "recovered"))
            }
        }

        assertNull(result.failure)
        assertEquals(2, result.provider.streamCalls)
        assertEquals(listOf(1), result.retries)
        assertEquals("Finished stream lost retry output; events=${result.events}", "recovered", result.finalMessage?.toText())
    }

    @Test
    fun emptyTextAndToolDeltasDoNotHideARealEmptyPayloadFailure() = runBlocking {
        var attempt = 0
        val result = exercise(maxRetries = 1) {
            flow {
                if (attempt++ == 0) {
                    emit(StreamChunk.TextDelta("empty-text", ""))
                    emit(StreamChunk.ToolCallDelta("empty-tool", toolNameDelta = "", inputDelta = ""))
                    throw IOException("synthetic failure after empty deltas")
                }
                emit(StreamChunk.TextDelta("answer", "retried"))
            }
        }

        assertNull(result.failure)
        assertEquals(2, result.provider.streamCalls)
        assertEquals(listOf(1), result.retries)
        assertEquals("Finished stream lost retry output; events=${result.events}", "retried", result.finalMessage?.toText())
    }

    @Test
    fun progressResetsBetweenToolStepAndRetryAndDoesNotBleedAcrossSteps() = runBlocking {
        val tool = Tool(
            name = "synthetic_tool",
            description = "test tool",
            execute = { listOf(UIMessagePart.Text("done")) },
        )
        var call = 0
        val result = exercise(
            maxRetries = 1,
            tools = listOf(tool),
            stream = {
                when (call++) {
                    0 -> flow {
                        emit(StreamChunk.ToolCallStart("call-1", "synthetic_tool"))
                        emit(StreamChunk.ToolCallDelta("call-1", inputDelta = "{}"))
                        emit(StreamChunk.ToolCallEnd("call-1"))
                    }
                    1 -> emptyFlowForTest()
                    else -> flowOf(StreamChunk.TextDelta("answer", "after retry"))
                }
            },
        )

        assertNull(result.failure)
        assertEquals(3, result.provider.streamCalls)
        assertEquals(listOf(1), result.retries)
        assertEquals(listOf(0, 0, 1), result.hooks.resetAttempts)
        assertEquals("Finished stream lost retry output; events=${result.events}", listOf("after retry"), result.finalMessage?.parts?.filterIsInstance<UIMessagePart.Text>()?.map { it.text })
    }

    private fun emptyFlowForTest(): Flow<StreamChunk> = flow { }

    private fun assertFailurePreserved(expected: Throwable, actual: Throwable?) {
        assertNotNull("The stream failure or cancellation must propagate", actual)
        val observed = checkNotNull(actual)
        assertEquals(expected.javaClass, observed.javaClass)
        assertEquals(expected.message, observed.message)
        assertTrue(
            "The original failure must be preserved directly or in the recovered cause chain",
            generateSequence(observed) { it.cause }.take(8).any { cause ->
                cause === expected || (cause.javaClass == expected.javaClass && cause.message == expected.message)
            },
        )
    }

    private suspend fun exercise(
        maxRetries: Int,
        tools: List<Tool> = emptyList(),
        onEvent: suspend (AgentEvent) -> Unit = {},
        coalescerClock: () -> Long = { System.nanoTime() / 1_000_000L },
        stream: suspend (call: Int) -> Flow<StreamChunk>,
    ): Result {
        val provider = SyntheticProvider(stream)
        val hooks = ProgressHooks(model, maxRetries, coalescerClock)
        val events = mutableListOf<AgentEvent>()
        val retries = mutableListOf<Int>()
        var failure: Throwable? = null
        try {
            runAgent(
                AgentRequest(
                    provider = provider,
                    providerSetting = setting,
                    messages = listOf(UIMessage.user("synthetic prompt")),
                    tools = tools,
                    stream = true,
                    retry = hooks.retryPolicy(),
                ),
                hooks,
            ).collect { event ->
                events += event
                if (event is AgentEvent.Retrying) retries += event.attempt.retry
                onEvent(event)
            }
        } catch (caught: Throwable) {
            failure = caught
        }
        val finished = events.filterIsInstance<AgentEvent.Finished>().lastOrNull()?.message
        return Result(provider, hooks, events, retries, failure, finished)
    }

    private data class Result(
        val provider: SyntheticProvider,
        val hooks: ProgressHooks,
        val events: List<AgentEvent>,
        val retries: List<Int>,
        val failure: Throwable?,
        val finalMessage: UIMessage?,
    ) {
        val progress: GenerationStreamProgress get() = hooks.progress
    }

    private class ProgressHooks(
        private val model: Model,
        private val maxRetries: Int,
        private val coalescerClock: () -> Long,
    ) : AgentHooks {
        val progress = GenerationStreamProgress()
        val resetAttempts = mutableListOf<Int>()
        private var currentAttempt = 0

        override suspend fun prepareStep(messages: List<UIMessage>) =
            StepRequest(messages, TextGenerationParams(model = model))

        override suspend fun onStreamAttemptReset(attempt: Int) {
            currentAttempt = attempt
            resetAttempts += attempt
            progress.reset()
        }

        override fun interceptStream(chunks: Flow<StreamChunk>): Flow<StreamChunk> =
            chunks.withIdleWatchdog(idleMs = 120_000L)
                .trackGenerationStreamProgress(
                    progress = progress,
                    onMeaningfulOutput = {},
                )
                .coalesceStreamDeltas(windowMs = 300L, clock = coalescerClock)

        fun retryPolicy() = RetryPolicy(
            maxRetries = maxRetries,
            retryOn = { failure ->
                shouldRetryGenerationStreamFailure(
                    failure = failure,
                    retryAttempt = currentAttempt.toLong(),
                    maxRetries = maxRetries,
                    receivedMeaningfulOutput = progress.receivedMeaningfulOutput,
                )
            },
            delayFor = { ZERO },
        )
    }

    private class SyntheticProvider(
        private val stream: suspend (call: Int) -> Flow<StreamChunk>,
    ) : Provider<ProviderSetting> {
        var streamCalls = 0
            private set

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> =
            listOf(Model(modelId = "synthetic-model", displayName = "Synthetic"))

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
            streamCalls++
            return stream(streamCalls)
        }

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = error("unexpected generateImage call")
    }
}
