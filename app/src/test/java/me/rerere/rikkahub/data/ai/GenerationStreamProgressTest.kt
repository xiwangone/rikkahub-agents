package me.rerere.rikkahub.data.ai

import java.io.IOException
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
        assertSame(failure,cleanup?.cause)
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

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            generateCalls++
            return generate(providerSetting, messages, params)
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> {
            streamCalls++
            return stream(streamCalls, providerSetting, messages, params)
        }

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = error("Synthetic test provider does not generate images")

        override suspend fun editImage(
            providerSetting: ProviderSetting,
            params: ImageEditParams,
        ): Flow<ImageGenerationItem> = error("Synthetic test provider does not edit images")

        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting,
            params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = error("Synthetic test provider does not generate embeddings")
    }
}
