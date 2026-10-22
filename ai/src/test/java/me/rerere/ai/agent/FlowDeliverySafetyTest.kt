package me.rerere.ai.agent

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.ZERO

class FlowDeliverySafetyTest {
    private val model = Model(modelId = "synthetic-model", displayName = "Synthetic")
    private val retryIo = RetryPolicy(
        maxRetries = 3,
        initialDelay = ZERO,
        retryOn = { it is IOException },
        delayFor = { ZERO },
    )
    private val retryEveryFailure = RetryPolicy(
        maxRetries = 3,
        initialDelay = ZERO,
        retryOn = { true },
        delayFor = { ZERO },
    )

    @Test
    fun previewStepIOExceptionIsDeliveredUnchangedWithoutRetry() {
        runBlocking {
            val failure = IOException("preview rejected")
            val provider = FakeProvider()
            val events = mutableListOf<AgentEvent>()
            val hooks = hooks { throw failure }

            val actual = captureFailure {
                request(provider, retry = retryIo).let { request ->
                    runAgent(request, hooks).collect { events += it }
                }
            }

            assertSame(failure, actual)
            assertEquals(1, provider.streamCalls)
            assertFalse(events.any { it is AgentEvent.Retrying })
        }
    }

    @Test
    fun downstreamIOExceptionAfterInitialEmptyAssistantUpdateIsNotRetried() {
        runBlocking {
            val failure = IOException("consumer stopped accepting updates")
            val provider = FakeProvider()
            val events = mutableListOf<AgentEvent>()

            val actual = captureFailure {
                runAgent(request(provider, retry = retryIo), hooks()).collect { event ->
                    events += event
                    if (event is AgentEvent.MessageUpdated && event.message.parts.isNotEmpty()) throw failure
                }
            }

            assertSame(failure, actual)
            assertEquals(1, provider.streamCalls)
            assertFalse(events.any { it is AgentEvent.Retrying })
        }
    }

    @Test
    fun downstreamCancellationConvertedByProviderToIOExceptionIsRestored() {
        runBlocking {
            val cancellation = CancellationException("consumer cancelled")
            val provider = FakeProvider(stream = { _, _, _, _ ->
                flow {
                    try {
                        emit(textChunk())
                    } catch (_: CancellationException) {
                        throw IOException("stream was reset: CANCEL")
                    }
                }
            })
            val events = mutableListOf<AgentEvent>()

            val actual = captureFailure {
                runAgent(request(provider, retry = retryEveryFailure), hooks()).collect { event ->
                    events += event
                    if (event is AgentEvent.MessageUpdated && event.message.parts.isNotEmpty()) throw cancellation
                }
            }

            assertSame(cancellation, actual)
            assertEquals(1, provider.streamCalls)
            assertEquals(0, events.count { it is AgentEvent.Retrying })
        }
    }

    @Test
    fun wrappedDownstreamCancellationConvertedByProviderPreservesWrapper() {
        runBlocking {
            val wrapped = IllegalStateException("delivery wrapper", CancellationException("cancelled"))
            val provider = FakeProvider(stream = { _, _, _, _ ->
                flow {
                    try {
                        emit(textChunk())
                    } catch (error: Throwable) {
                        throw IOException("adapter replaced delivery failure", error)
                    }
                }
            })
            val events = mutableListOf<AgentEvent>()

            val actual = captureFailure {
                runAgent(request(provider, retry = retryEveryFailure), hooks()).collect { event ->
                    events += event
                    if (event is AgentEvent.MessageUpdated && event.message.parts.isNotEmpty()) throw wrapped
                }
            }

            assertSame(wrapped, actual)
            assertEquals(1, provider.streamCalls)
            assertEquals(0, events.count { it is AgentEvent.Retrying })
        }
    }

    @Test
    fun retryNotificationLinkageErrorPropagatesUnchanged() {
        runBlocking {
            val failure = LinkageError("retry event consumer failed")
            val provider = FakeProvider(stream = { _, _, _, _ ->
                flow { throw IOException("temporary transport failure") }
            })
            var retryEvents = 0

            val actual = captureFailure {
                runAgent(request(provider, retry = retryIo), hooks()).collect { event ->
                    if (event is AgentEvent.Retrying) {
                        retryEvents++
                        throw failure
                    }
                }
            }

            assertSame(failure, actual)
            assertEquals(1, provider.streamCalls)
            assertEquals(1, retryEvents)
        }
    }

    @Test
    fun retryNotificationWrappedCancellationPropagatesUnchanged() {
        runBlocking {
            val failure = IllegalArgumentException("notification wrapper", CancellationException("cancel"))
            val provider = FakeProvider(stream = { _, _, _, _ ->
                flow { throw IOException("temporary transport failure") }
            })
            var retryEvents = 0

            val actual = captureFailure {
                runAgent(request(provider, retry = retryIo), hooks()).collect { event ->
                    if (event is AgentEvent.Retrying) {
                        retryEvents++
                        throw failure
                    }
                }
            }

            assertSame(failure, actual)
            assertEquals(1, provider.streamCalls)
            assertEquals(1, retryEvents)
        }
    }

    @Test
    fun freshSubscriptionRecoversAfterFirstTransportIOException() {
        runBlocking {
            val transportFailure = IOException("first subscription failed")
            val provider = FakeProvider(stream = { call, _, _, _ ->
                if (call == 1) flow { throw transportFailure } else flowOf(textChunk())
            })

            val secondEvents = runAgent(request(provider, retry = retryIo), hooks()).toList()
            assertTrue(secondEvents.any { it is AgentEvent.Finished })
            assertEquals(1, secondEvents.count { it is AgentEvent.Retrying })
            assertEquals(2, provider.streamCalls)
        }
    }

    @Test
    fun nonStreamingRetryNotificationLinkageErrorPropagatesUnchanged() {
        runBlocking {
            val failure = LinkageError("non-stream retry observer failed")
            val provider = FakeProvider(generate = { _, _, _ -> throw IOException("temporary failure") })
            var retryEvents = 0

            val actual = captureFailure {
                runAgent(request(provider, stream = false, retry = retryIo), hooks()).collect { event ->
                    if (event is AgentEvent.Retrying) {
                        retryEvents++
                        throw failure
                    }
                }
            }

            assertSame(failure, actual)
            assertEquals(1, provider.generateCalls)
            assertEquals(1, retryEvents)
        }
    }

    @Test
    fun nonStreamingRetryNotificationCancellationPropagatesUnchanged() {
        runBlocking {
            val failure = CancellationException("non-stream notification cancelled")
            val provider = FakeProvider(generate = { _, _, _ -> throw IOException("temporary failure") })
            var retryEvents = 0

            val actual = captureFailure {
                runAgent(request(provider, stream = false, retry = retryIo), hooks()).collect { event ->
                    if (event is AgentEvent.Retrying) {
                        retryEvents++
                        throw failure
                    }
                }
            }

            assertSame(failure, actual)
            assertEquals(1, provider.generateCalls)
            assertEquals(1, retryEvents)
        }
    }

    @Test
    fun withRetryDoesNotRetryBlockFailureWrappedAroundCancellation() {
        runBlocking {
            val failure = RuntimeException("wrapper", IllegalStateException("nested", CancellationException("stop")))
            var calls = 0

            val actual = captureFailure {
                withRetry(retryEveryFailure) {
                    calls++
                    throw failure
                }
            }

            assertSame(failure, actual)
            assertEquals(1, calls)
        }
    }

    @Test
    fun flowCatchDoesNotCaptureDownstreamForeignCancellation() {
        runBlocking {
            val foreign = CancellationException("downstream cancellation")
            var caughtUpstream: Throwable? = null

            val actual = captureFailure {
                flow { emit(1) }
                    .catch { error ->
                        caughtUpstream = error
                        emit(2)
                    }
                    .collect { throw foreign }
            }

            assertSame(foreign, actual)
            assertNull(caughtUpstream)
        }
    }

    @Test
    fun flowExceptionTransparencyViolationHasNoSyntheticCause() {
        runBlocking {
            val downstreamFailure = IOException("downstream failed")
            val actual = captureFailure {
                flow {
                    try {
                        emit(1)
                    } catch (_: IOException) {
                        emit(2)
                    }
                }.collect { throw downstreamFailure }
            }

            assertTrue(actual is IllegalStateException)
            assertNull(actual.cause)
        }
    }

    @Test
    fun cancelledDeliveryThenEmptyStreamCloseDoesNotNotifyOrReplay() {
        runBlocking {
            val cancellation = CancellationException("synthetic scope cancellation")
            val provider = FakeProvider(stream = { _, _, _, _ ->
                flow {
                    try {
                        emit(textChunk())
                    } catch (_: CancellationException) {
                        // Simulate an adapter swallowing HTTP/2 stream cancellation.
                    }
                }
            })
            val baseHooks = hooks()
            val interceptingHooks = object : AgentHooks by baseHooks {
                override fun interceptStream(chunks: Flow<StreamChunk>): Flow<StreamChunk> =
                    chunks.onCompletion { cause ->
                        if (cause == null) throw IOException("Model stream closed without meaningful output")
                    }
            }
            val events = mutableListOf<AgentEvent>()
            val actual = captureFailure {
                runAgent(request(provider, retry = retryEveryFailure), interceptingHooks).collect { event ->
                    events += event
                    if (event is AgentEvent.MessageUpdated && event.message.parts.isNotEmpty()) throw cancellation
                }
            }
            assertSame(cancellation, actual)
            assertEquals(1, provider.streamCalls)
            assertEquals(0, events.count { it is AgentEvent.Retrying })
        }
    }

    @Test
    fun seriousProviderErrorIsNeverRetried() {
        runBlocking {
            val failure = LinkageError("synthetic fatal error")
            var calls = 0
            val actual = captureFailure {
                withRetry(retryEveryFailure) {
                    calls++
                    throw failure
                }
            }
            assertSame(failure, actual)
            assertEquals(1, calls)
        }
    }

    @Test
    fun cancelledCoroutineWithTransportIOExceptionDoesNotNotifyOrReplay() {
        runBlocking {
            var calls = 0
            var notices = 0
            var actual: Throwable? = null
            val child = launch {
                actual = captureFailure {
                    withRetry(retryEveryFailure, onRetry = { notices++ }) {
                        calls++
                        currentCoroutineContext().cancel(CancellationException("synthetic parent cancellation"))
                        throw IOException("stream was reset: CANCEL")
                    }
                }
            }
            child.join()
            assertTrue(actual is CancellationException)
            assertEquals(1, calls)
            assertEquals(0, notices)
        }
    }

    private fun request(
        provider: FakeProvider,
        stream: Boolean = true,
        retry: RetryPolicy = RetryPolicy.NONE,
    ) = AgentRequest(
        provider = provider,
        providerSetting = ProviderSetting.OpenAI(
            models = listOf(model),
            apiKey = "",
        ),
        messages = listOf(UIMessage.user("synthetic prompt")),
        stream = stream,
        retry = retry,
    )

    private fun hooks(preview: suspend (UIMessage) -> Unit = {}) = object : AgentHooks {
        override suspend fun prepareStep(messages: List<UIMessage>) =
            StepRequest(messages, TextGenerationParams(model = model))

        override suspend fun previewStep(message: UIMessage, newPartsFrom: Int): UIMessage {
            preview(message)
            return message
        }
    }

    private fun textChunk() = StreamChunk.TextDelta(id = "synthetic-text", text = "synthetic response")

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        AssertionError("Expected operation to fail")
    } catch (error: Throwable) {
        error
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
