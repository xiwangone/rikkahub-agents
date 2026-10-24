package me.rerere.rikkahub.data.ai

import java.io.IOException
<<<<<<< HEAD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.rerere.ai.agent.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.ai.util.HttpException
import org.junit.Assert.*
import org.junit.Test

class GenerationStreamProgressTest {
    private val model = Model(modelId="synthetic-model", displayName="Synthetic")
    private data class Result(val calls: Int, val retries: List<Int>, val outputs: Int, val failure: Throwable?, val any: Boolean, val meaningful: Boolean)

    /**
     * 跨协程边界的异常实例不保证同一（agent / 看门狗 / withRetry 各自有作用域）：
     * 真正要守的契约是「类型不变 + 消息不变」；实例相同也接受。
     */
    private fun assertSameFailure(expected: Throwable, actual: Throwable?) {
        if (actual === expected) return
        assertNotNull("expected a failure but got null", actual)
        assertEquals("failure type changed", expected.javaClass, actual!!.javaClass)
        assertEquals("failure message changed", expected.message, actual.message)
    }

    private suspend fun exercise(maxRetries: Int=6, onEvent: (AgentEvent) -> Unit = {}, stream: suspend () -> Flow<StreamChunk>): Result {
        val provider=FakeProvider(stream={_,_,_,_->stream()})
        val hooks=ProgressHooks(model,maxRetries)
        val retries=mutableListOf<Int>()
        var outputs=0
        var failure: Throwable?=null
        try {
            runAgent(AgentRequest(provider=provider, providerSetting=ProviderSetting.OpenAI(models=listOf(model),apiKey=""), messages=listOf(UIMessage.user("synthetic prompt")), stream=true,retry=hooks.instantRetryPolicy()),hooks).collect { event ->
                onEvent(event)
                if(event is AgentEvent.Retrying) retries+=event.attempt.retry
                if(event is AgentEvent.MessageUpdated && event.message.parts.isNotEmpty()) outputs++
            }
        } catch(t: Throwable) { failure=t }
        val r=Result(provider.streamCalls,retries,outputs,failure,hooks.progress.receivedAnyChunk,hooks.progress.receivedMeaningfulOutput)
        println("SYNTHETIC_RESULT calls=${r.calls} retries=${r.retries} outputs=${r.outputs} any=${r.any} meaningful=${r.meaningful} failure=${r.failure?.javaClass?.simpleName}:${r.failure?.message}")
        return r
    }

    @Test fun actualEmptyCloseRetriesSixTimesWithinOneCollectionThenFails() { runBlocking {
        val r=exercise { emptyFlow() }
        assertEquals(7,r.calls)
        assertEquals((1..6).toList(),r.retries)
        assertTrue(r.failure is IOException)
        assertEquals("Model stream closed without meaningful output",r.failure?.message)
    } }

    @Test fun healthyTextThenCleanCloseMustSucceedWithoutReplay() { runBlocking {
        val r=exercise(maxRetries=2) { flow { emit(StreamChunk.TextDelta("text","synthetic successful answer"));delay(30) } }
        assertTrue("Synthetic text was actually delivered",r.outputs>0)
        assertNull("Valid upstream output must not become an empty-stream failure",r.failure)
        assertEquals(1,r.calls)
        assertTrue(r.any)
        assertTrue(r.meaningful)
    } }

    @Test fun partialTextThenIOExceptionMustNotReplayModelRequest() { runBlocking {
        val failure=IOException("synthetic upstream disconnect after partial output")
        val r=exercise(maxRetries=2) { flow { emit(StreamChunk.TextDelta("text","synthetic partial answer"));delay(30);throw failure } }
        assertTrue(r.outputs>0)
        // 异常实例跨协程边界（agent / watchdog 各有作用域）不保证同一；契约是类型与消息不丢
        assertEquals(failure.javaClass, r.failure?.javaClass)
        assertEquals(failure.message, r.failure?.message)
        assertEquals("Meaningful output must prevent replay",1,r.calls)
        assertTrue(r.retries.isEmpty())
    } }

    @Test fun markerOnlyStreamMustNotBeMistakenForZeroChunks() { runBlocking {
        val r=exercise(maxRetries=2) { flow { emit(StreamChunk.TextStart("text"));delay(30);emit(StreamChunk.TextEnd("text"));delay(30) } }
        assertNull("Current policy ends marker-only streams without retry",r.failure)
        assertEquals(1,r.calls)
        assertTrue(r.any)
        assertFalse(r.meaningful)
    } }

    @Test fun providerHttp400IsNotHiddenOrRetriedAsEmptyStream() { runBlocking {
        val failure=HttpException("synthetic provider rejection",statusCode=400)
        val r=exercise { flow { throw failure } }
        assertSameFailure(failure,r.failure)
        assertEquals(1,r.calls)
        assertTrue(r.retries.isEmpty())
    } }

    @Test fun scopeCancellationMayHaveIOExceptionCauseWithoutUserCancellation() { runBlocking {
        val failure=IOException("Model stream closed without meaningful output")
        var cleanup: CancellationException?=null
        var terminal: Throwable?=null
        try {
            flow<Int> {
                coroutineScope {
                    launch { yield();throw failure }
                    try { awaitCancellation() } catch(t: CancellationException) {cleanup=t;throw t}
                }
            }.collect()
        } catch(t: Throwable) {terminal=t}
        // 父作用域可能拿到：原实例 / 包裹它的取消异常 / 同类型同消息的副本（协程边界行为）
        val followedFailure = terminal === failure ||
            (terminal is CancellationException && terminal.cause === failure) ||
            (terminal != null && terminal.javaClass == failure.javaClass && terminal.message == failure.message)
        assertTrue(
            "terminal 应为原异常、包裹它的取消异常，或同类型同消息的失败，实际: ${terminal?.javaClass?.name}",
            followedFailure,
        )
        assertNotNull(cleanup)
        // 原失败可能不在 cleanup 的直接 cause 上，但必须能从 terminal/cleanup 的 cause 链追溯（类型+消息）
        fun chainOf(t: Throwable?): List<Throwable> = generateSequence(t) { it.cause }.toList()
        val traceable = (chainOf(terminal) + chainOf(cleanup)).any {
            it.javaClass == failure.javaClass && it.message == failure.message
        }
        assertTrue(
            "失败应可从 terminal 或 cleanup 的 cause 链追溯，实际 cleanup.cause=${cleanup?.cause?.javaClass?.simpleName}",
            traceable,
        )
        println("SYNTHETIC_SCOPE cleanup=${cleanup?.javaClass?.name}:${cleanup?.message} cause=${cleanup?.cause?.javaClass?.simpleName} terminal=${terminal?.javaClass?.simpleName}")
    } }

    @Test fun genuineWrappedCancellationStillExitsWithoutRetry() { runBlocking {
        val cancellation=CancellationException("synthetic stop")
        val wrapped=IOException("synthetic wrapper",cancellation)
        val r=exercise { flow { throw wrapped } }
        // 同上：实例可能被协程边界替换，但包装类型与消息（含“被包取消”语义）不得变
        assertEquals(wrapped.javaClass, r.failure?.javaClass)
        assertEquals(wrapped.message, r.failure?.message)
        assertEquals(1,r.calls)
        assertTrue(r.retries.isEmpty())
    } }

    @Test fun rawAgentWithoutAppInterceptCompletesHealthyFakeProviderNormally() { runBlocking {
        val provider=FakeProvider(stream={_,_,_,_->flow {emit(StreamChunk.TextDelta("text","synthetic good answer"))}})
        val hooks=object : AgentHooks { override suspend fun prepareStep(messages: List<UIMessage>)=StepRequest(messages,TextGenerationParams(model=model)) }
        val events=runAgent(AgentRequest(provider=provider,providerSetting=ProviderSetting.OpenAI(models=listOf(model),apiKey=""),messages=listOf(UIMessage.user("synthetic prompt")),stream=true),hooks).toList()
        assertEquals(1,provider.streamCalls)
        assertTrue(events.none { it is AgentEvent.Retrying })
        println("SYNTHETIC_CONTROL healthy_raw_agent_calls=1 retries=0 success=true")
    } }

    @Test fun emptyAttemptThenSuccessfulOutputRecoversOnceWithoutRepeatingContent() { runBlocking {
        var attempt=0
        val r=exercise { flow {
            if(attempt++>0) { emit(StreamChunk.TextDelta("text","synthetic recovered output"));delay(30) }
        } }
        assertNull(r.failure)
        assertEquals(2,r.calls)
        assertEquals(listOf(1),r.retries)
        assertTrue(r.meaningful)
    } }

    @Test fun downstreamCancellationIsOriginalAndNeverRetried() { runBlocking {
        val cancellation=CancellationException("synthetic downstream stop")
        val r=exercise(onEvent={ event ->
            if(event is AgentEvent.MessageUpdated && event.message.parts.isNotEmpty()) throw cancellation
        }) { flow { emit(StreamChunk.TextDelta("text","synthetic partial answer"));delay(30) } }
        assertSameFailure(cancellation,r.failure)
        assertEquals(1,r.calls)
        assertTrue(r.retries.isEmpty())
    } }

    @Test fun downstreamIOExceptionNeverBecomesTransparencyOrReplaysProvider() { runBlocking {
        val failure=IOException("synthetic failed message delivery")
        val r=exercise(onEvent={event ->
            if(event is AgentEvent.MessageUpdated && event.message.parts.isNotEmpty()) throw failure
        }) { flow {emit(StreamChunk.TextDelta("text","synthetic partial answer"));delay(30)} }
        assertSameFailure(failure,r.failure)
        assertEquals(1,r.calls)
        assertTrue(r.retries.isEmpty())
        assertFalse(r.failure!!.isFlowTransparencyViolation())
    } }

    @Test fun progressResetsOnlyBetweenAttemptsAndMeaningfulOutputClearsRetryStatus() { runBlocking {
        val hooks=ProgressHooks(model,2)
        hooks.onStreamAttemptReset(0)
        hooks.interceptStream(flowOf(StreamChunk.TextDelta("text","synthetic output"))).toList()
        assertTrue(hooks.progress.receivedAnyChunk)
        assertTrue(hooks.progress.receivedMeaningfulOutput)
        assertNull(hooks.retryStatus)
        hooks.onStreamAttemptReset(1)
        assertFalse(hooks.progress.receivedAnyChunk)
        assertFalse(hooks.progress.receivedMeaningfulOutput)
    } }

    @Test fun disabledRetriesMakesOneEmptyRequestAndSurfacesFailure() { runBlocking {
        val r=exercise(maxRetries=0) { emptyFlow() }
        assertTrue(r.failure is IOException)
        assertEquals(1,r.calls)
        assertTrue(r.retries.isEmpty())
    } }

    @Test fun partialToolCallThenIOExceptionNeverReplaysModelRequest() { runBlocking {
        val failure=IOException("synthetic disconnect after tool arguments")
        val r=exercise { flow {
            emit(StreamChunk.ToolCallStart("call", "synthetic_tool"))
            emit(StreamChunk.ToolCallDelta("call", inputDelta="{}"))
            delay(30)
            throw failure
        } }
        assertSameFailure(failure,r.failure)
        assertEquals(1,r.calls)
        assertTrue(r.retries.isEmpty())
        assertTrue(r.meaningful)
    } }

    private class ProgressHooks(private val model: Model, private val maxRetries: Int) : AgentHooks {
        val progress = GenerationStreamProgress()
        private var attempt = 0
        var retryStatus: String? = "synthetic retry status"
        override suspend fun prepareStep(messages: List<UIMessage>) = StepRequest(messages, TextGenerationParams(model=model))
        override suspend fun onStreamAttemptReset(attempt: Int) {
            this.attempt = attempt
            progress.reset()
        }
        override fun interceptStream(chunks: Flow<StreamChunk>): Flow<StreamChunk> = chunks
            .withIdleWatchdog(idleMs=120_000L)
            .trackGenerationStreamProgress(progress, onMeaningfulOutput={ retryStatus=null })
        fun instantRetryPolicy() = RetryPolicy(maxRetries=maxRetries, retryOn={
            shouldRetryGenerationStreamFailure(it,attempt.toLong(),maxRetries,progress.receivedMeaningfulOutput)
        },delayFor={ kotlin.time.Duration.ZERO })
    }

    private class FakeProvider(
        private val stream: suspend (Int, ProviderSetting, List<UIMessage>, TextGenerationParams) -> Flow<StreamChunk> =
            { _, _, _, _ -> flowOf(StreamChunk.TextDelta("synthetic-text", "synthetic response")) },
        private val generate: suspend (ProviderSetting, List<UIMessage>, TextGenerationParams) -> TextGenerationResult =
            { _, _, params ->
                TextGenerationResult("synthetic-response", params.model.modelId, UIMessage.assistant("synthetic response"))
            },
    ) : Provider<ProviderSetting> {
        var streamCalls = 0
            private set
        var generateCalls = 0
            private set

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = listOf(
            Model(modelId = "synthetic-model", displayName = "Synthetic"),
        )
=======
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
>>>>>>> github/fix/queue-lifecycle-usage-combo-20261024

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
<<<<<<< HEAD
        ): TextGenerationResult {
            generateCalls++
            return generate(providerSetting, messages, params)
        }
=======
        ): TextGenerationResult = error("unexpected generateText call")
>>>>>>> github/fix/queue-lifecycle-usage-combo-20261024

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> {
            streamCalls++
<<<<<<< HEAD
            return stream(streamCalls, providerSetting, messages, params)
=======
            return stream(streamCalls)
>>>>>>> github/fix/queue-lifecycle-usage-combo-20261024
        }

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
<<<<<<< HEAD
        ): Flow<ImageGenerationItem> = error("Synthetic test provider does not generate images")

        override suspend fun editImage(
            providerSetting: ProviderSetting,
            params: ImageEditParams,
        ): Flow<ImageGenerationItem> = error("Synthetic test provider does not edit images")

        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting,
            params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = error("Synthetic test provider does not generate embeddings")
=======
        ): Flow<ImageGenerationItem> = error("unexpected generateImage call")
>>>>>>> github/fix/queue-lifecycle-usage-combo-20261024
    }
}
