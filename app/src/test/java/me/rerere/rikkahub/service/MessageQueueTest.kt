package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MessageQueueTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test
    fun `dispatches in submission order and preserves send without answer`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"), answer = false)

        assertEquals(text("first"), queue.takeNext()!!.parts)
        val second = queue.takeNext()!!
        assertEquals(text("second"), second.parts)
        assertFalse(second.answer)
        assertNull(queue.takeNext())
    }

    @Test
    fun `blank input is ignored`() {
        val queue = MessageQueue()
        queue.enqueue(listOf(UIMessagePart.Text("   ")))
        queue.enqueue(emptyList())

        assertEquals(0, queue.size)
    }

    @Test
    fun `pausing stops dispatch but keeps queued content for resume`() {
        val queue = MessageQueue()
        queue.enqueue(text("keep me"))
        queue.pause()

        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        assertEquals(text("keep me"), queue.state.value.messages.single().parts)

        queue.resume()
        assertFalse(queue.state.value.paused)
        assertEquals(text("keep me"), queue.takeNext()!!.parts)
    }

    @Test
    fun `editing the head blocks later messages and keeps its position`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id

        assertNotNull(queue.beginEdit(id))
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))

        val first = queue.takeNext()!!
        assertEquals(id, first.id)
        assertEquals(text("edited"), first.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `cancelled edit only releases the placeholder`() {
        val queue = MessageQueue()
        queue.enqueue(text("original"))
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)

        // 放弃编辑：不返回待清理条目，内容保持原样
        assertNull(queue.finishEdit(id))
        assertFalse(queue.state.value.messages.single().isEditing)
        assertEquals(text("original"), queue.takeNext()!!.parts)
    }

    @Test
    fun `removing a queued message returns it and keeps the rest in order`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))

        val removed = queue.remove(queue.state.value.messages.first().id)
        assertEquals(text("first"), removed!!.parts)
        assertEquals(1, queue.size)
        assertNull(queue.remove(Uuid.random()))
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `late enqueue is dispatched after generation final queue check`() = runBlocking {
        val queue = MessageQueue()
        val generation = Job()
        // Model the finishing generation checking an empty queue before this send arrives.
        assertNull(queue.takeNext())
        queue.enqueue(text("late message"))

        var dispatched: QueuedMessage? = null
        var dispatchAttempts = 0
        scheduleQueueDispatchAfter(generation, this) {
            dispatchAttempts++
            dispatched = queue.takeNext()
        }
        generation.complete()
        yield()

        assertEquals(1, dispatchAttempts)
        assertEquals(text("late message"), dispatched?.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `queue dispatch is retried when completion beats callback registration`() = runBlocking {
        val queue = MessageQueue()
        val generation = Job().apply { complete() }
        queue.enqueue(text("message"))

        var dispatched: QueuedMessage? = null
        scheduleQueueDispatchAfter(generation, this) { dispatched = queue.takeNext() }
        yield()

        assertEquals(text("message"), dispatched?.parts)
    }

    @Test
    fun `queued messages at turn end start follow-up turns automatically in FIFO order`() = runBlocking {
        val queue = MessageQueue()
        queue.enqueue(text("first follow-up"))
        queue.enqueue(text("second follow-up"))
        val sent = mutableListOf<String>()
        var activeGeneration: CompletableJob = Job()

        fun dispatchAtTurnEnd(generation: Job) {
            scheduleQueueDispatchAfter(generation, this) {
                if (activeGeneration.isActive) return@scheduleQueueDispatchAfter
                val next = queue.takeNext() ?: return@scheduleQueueDispatchAfter
                sent += (next.parts.single() as UIMessagePart.Text).text
                activeGeneration = Job()
                dispatchAtTurnEnd(activeGeneration)
            }
        }

        val firstTurn = activeGeneration
        dispatchAtTurnEnd(firstTurn)
        firstTurn.complete()
        yield()
        assertEquals(listOf("first follow-up"), sent)

        val followUpTurn = activeGeneration
        followUpTurn.complete()
        yield()
        assertEquals(listOf("first follow-up", "second follow-up"), sent)
        assertNull(queue.takeNext())
    }

    @Test
    fun `turn-end dispatch starts assistant follow-up without manual refresh`() = runBlocking {
        val queue = MessageQueue()
        val queuedText = "queued turn-end input"
        queue.enqueue(text(queuedText))
        val generation = Job()
        val model = Model(modelId = "synthetic-queue-follow-up")
        val providerMessages = CompletableDeferred<List<UIMessage>>()
        val dispatchedAnswer = CompletableDeferred<Boolean>()
        val assistantReply = CompletableDeferred<String>()
        val followUpFinished = CompletableDeferred<Unit>()
        val scope = this

        scheduleQueueDispatchAfter(generation, scope) {
            val next = queue.takeNext() ?: return@scheduleQueueDispatchAfter
            dispatchedAnswer.complete(next.answer)
            scope.launch {
                try {
                    val provider = object : Provider<ProviderSetting> {
                        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

                        override suspend fun generateText(
                            providerSetting: ProviderSetting,
                            messages: List<UIMessage>,
                            params: TextGenerationParams,
                        ): TextGenerationResult {
                            providerMessages.complete(messages)
                            val userText = messages.last { it.role == MessageRole.USER }.toText()
                            return TextGenerationResult(
                                id = "synthetic-follow-up",
                                model = model.modelId,
                                message = UIMessage.assistant("heard: $userText"),
                                finishReason = "stop",
                            )
                        }

                        override suspend fun streamText(
                            providerSetting: ProviderSetting,
                            messages: List<UIMessage>,
                            params: TextGenerationParams,
                        ): Flow<StreamChunk> = flow { error("streaming is unused in this test") }

                        override suspend fun generateImage(
                            providerSetting: ProviderSetting,
                            params: ImageGenerationParams,
                        ): Flow<ImageGenerationItem> = flow { error("image generation is unused in this test") }
                    }
                    val hooks = object : AgentHooks {
                        override suspend fun prepareStep(messages: List<UIMessage>) = StepRequest(
                            messages,
                            TextGenerationParams(model),
                        )
                    }
                    val queuedUserMessage = UIMessage(role = MessageRole.USER, parts = next.parts)
                    runAgent(
                        AgentRequest(
                            provider = provider,
                            providerSetting = ProviderSetting.OpenAI(),
                            messages = listOf(queuedUserMessage),
                            stream = false,
                            maxSteps = 2,
                            retry = RetryPolicy.NONE,
                        ),
                        hooks,
                    ).collect { event ->
                        if (event is AgentEvent.Finished) assistantReply.complete(event.message.toText())
                    }
                } finally {
                    followUpFinished.complete(Unit)
                }
            }
        }

        // The current generation completes; the registered completion callback sends the
        // queued user input as a new assistant turn, just as ChatService does at turn end.
        generation.complete()

        assertTrue(withTimeout(5_000) { dispatchedAnswer.await() })
        val actualMessages = withTimeout(5_000) { providerMessages.await() }
        assertTrue(actualMessages.any { it.role == MessageRole.USER && it.toText() == queuedText })
        assertEquals("heard: $queuedText", withTimeout(5_000) { assistantReply.await() })
        followUpFinished.await()
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `failed or cancelled generation leaves queue paused until explicit retry`() = runBlocking {
        for (failGeneration in listOf(false, true)) {
            val queue = MessageQueue()
            queue.enqueue(text("retry head"))
            queue.enqueue(text("retry tail"))
            queue.pause()
            val generation = Job()
            var dispatchAttempts = 0
            var dispatched: QueuedMessage? = null
            scheduleQueueDispatchAfter(generation, this) {
                dispatchAttempts++
                dispatched = queue.takeNext()
            }

            if (failGeneration) generation.completeExceptionally(IllegalStateException("synthetic failure"))
            else generation.cancel()
            yield()

            assertEquals(1, dispatchAttempts)
            assertNull(dispatched)
            assertEquals(listOf(text("retry head"), text("retry tail")), queue.state.value.messages.map { it.parts })

            queue.resume()
            assertEquals(text("retry head"), queue.takeNext()!!.parts)
            assertEquals(text("retry tail"), queue.takeNext()!!.parts)
        }
    }

    @Test
    fun `recreated queue observer immediately receives current pending state`() = runBlocking {
        val queue = MessageQueue()
        queue.enqueue(text("survives observer recreation"))

        // A recreated ViewModel subscribes to the same service-owned StateFlow and gets its current value.
        val recreatedObserverState = queue.state.first()
        assertEquals(text("survives observer recreation"), recreatedObserverState.messages.single().parts)
    }

    @Test
    fun `clear empties messages and resets paused`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.pause()

        queue.clear()

        assertTrue(queue.state.value.messages.isEmpty())
        assertFalse(queue.state.value.paused)
    }
}
