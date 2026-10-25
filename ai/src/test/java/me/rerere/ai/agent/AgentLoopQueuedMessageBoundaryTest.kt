package me.rerere.ai.agent

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.ZERO

class AgentLoopQueuedMessageBoundaryTest {
    private val model = Model(modelId = "queued-boundary-test")
    private val setting = ProviderSetting.OpenAI()

    @Test
    fun `queued FIFO messages are visible to assistant after tool result without a refresh`() = runBlocking {
        val queued = mutableListOf<UIMessage>()
        val requests = mutableListOf<List<UIMessage>>()
        val timeline = mutableListOf<String>()
        var providerCalls = 0
        var afterToolCalls = 0
        var nextRequestHistory: List<UIMessage>? = null

        val tool = Tool(
            name = "synthetic_tool",
            description = "Synthetic only",
            execute = {
                queued += listOf(UIMessage.user("queued:first"), UIMessage.user("queued:second"))
                listOf(UIMessagePart.Text("synthetic tool result"))
            },
        )
        val provider = TestProvider { _, messages, _ ->
            providerCalls++
            requests += messages
            if (providerCalls == 1) {
                TextGenerationResult(
                    id = "response-1",
                    model = model.modelId,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "synthetic-call",
                                toolName = tool.name,
                                input = "{}",
                            ),
                        ),
                    ),
                    finishReason = "tool_calls",
                )
            } else {
                timeline += "next-model-request"
                val queuedText = messages
                    .filter { it.role == MessageRole.USER && it.toText().startsWith("queued:") }
                    .map { it.toText() }
                TextGenerationResult(
                    id = "response-$providerCalls",
                    model = model.modelId,
                    message = UIMessage.assistant("heard ${queuedText.joinToString(" and ")}"),
                    finishReason = "stop",
                )
            }
        }
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest = StepRequest(
                nextRequestHistory ?: messages,
                TextGenerationParams(model),
            )

            override suspend fun afterToolExecution(step: StepContext) {
                afterToolCalls++
                val toolOutput = step.messages.last().getTools().single().output
                assertEquals("synthetic tool result", (toolOutput.single() as UIMessagePart.Text).text)
                timeline += "after-tool-hook"
                nextRequestHistory = step.messages + queued.toList()
                queued.clear()
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
                maxSteps = 3,
                retry = RetryPolicy.NONE,
            ),
            hooks,
        ).collect { event ->
            events += event
            if (event is AgentEvent.MessageUpdated && event.message.getTools().any { it.output.isNotEmpty() }) {
                timeline += "tool-result-emitted"
            }
        }

        val requestMessages = requests[1]
        val injected = requestMessages
            .filter { it.role == MessageRole.USER && it.toText().startsWith("queued:") }
            .map { it.toText() }
        val final = events.last() as AgentEvent.Finished
        assertEquals(2, providerCalls)
        assertEquals(1, afterToolCalls)
        assertEquals(listOf("queued:first", "queued:second"), injected)
        assertEquals("heard queued:first and queued:second", final.message.toText().trim())
        assertTrue(queued.isEmpty())
        assertTrue(timeline.indexOf("tool-result-emitted") < timeline.indexOf("after-tool-hook"))
        assertTrue(timeline.indexOf("after-tool-hook") < timeline.indexOf("next-model-request"))
    }

    @Test
    fun `step limit after tool result leaves queued input for turn end`() = runBlocking {
        val queued = mutableListOf<UIMessage>()
        var providerCalls = 0
        var afterToolCalls = 0
        val tool = Tool(
            name = "limited_tool",
            description = "Synthetic only",
            execute = {
                queued += UIMessage.user("queued:at-turn-end")
                listOf(UIMessagePart.Text("tool complete"))
            },
        )
        val provider = TestProvider { _, _, _ ->
            providerCalls++
            TextGenerationResult(
                id = "limited-tool-call",
                model = model.modelId,
                message = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Tool("limited-call", tool.name, "{}")),
                ),
                finishReason = "tool_calls",
            )
        }
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>) = StepRequest(
                messages,
                TextGenerationParams(model),
            )

            override suspend fun afterToolExecution(step: StepContext) {
                afterToolCalls++
                queued.clear()
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
                maxSteps = 1,
                retry = RetryPolicy.NONE,
            ),
            hooks,
        ).collect(events::add)

        assertEquals(1, providerCalls)
        assertEquals(0, afterToolCalls)
        assertEquals(listOf("queued:at-turn-end"), queued.map { it.toText() })
        assertTrue((events.last() as AgentEvent.Finished).reason is AgentFinishReason.StepLimitReached)
    }

    @Test
    fun `tool result injection happens once and survives retry of the following model request`() = runBlocking {
        val queued = mutableListOf(UIMessage.user("queued:retry"))
        val requests = mutableListOf<List<UIMessage>>()
        var providerCalls = 0
        var afterToolCalls = 0
        var nextRequestHistory: List<UIMessage>? = null
        val tool = Tool("retry_tool", "Synthetic only", execute = {
            listOf(UIMessagePart.Text("tool complete"))
        })
        val provider = TestProvider { _, messages, _ ->
            providerCalls++
            requests += messages
            when (providerCalls) {
                1 -> TextGenerationResult(
                    id = "tool-call",
                    model = model.modelId,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Tool("retry-call", tool.name, "{}")),
                    ),
                    finishReason = "tool_calls",
                )
                2 -> throw IOException("synthetic transient failure")
                else -> TextGenerationResult(
                    id = "recovered",
                    model = model.modelId,
                    message = UIMessage.assistant("heard queued:retry"),
                    finishReason = "stop",
                )
            }
        }
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>) = StepRequest(
                nextRequestHistory ?: messages,
                TextGenerationParams(model),
            )

            override suspend fun afterToolExecution(step: StepContext) {
                afterToolCalls++
                nextRequestHistory = step.messages + queued.toList()
                queued.clear()
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
                maxSteps = 3,
                retry = RetryPolicy(maxRetries = 1, delayFor = { ZERO }),
            ),
            hooks,
        ).collect(events::add)

        val retriedRequests = requests.drop(1)
        assertEquals(3, providerCalls)
        assertEquals(1, afterToolCalls)
        assertTrue(retriedRequests.all { request -> request.any { it.role == MessageRole.USER && it.toText() == "queued:retry" } })
        assertEquals("heard queued:retry", (events.last() as AgentEvent.Finished).message.toText().trim())
        assertTrue(queued.isEmpty())
        assertEquals(1, events.count { it is AgentEvent.Retrying })
    }

    @Test
    fun `tool failure still reaches post-tool boundary and consumes queued input`() = runBlocking {
        val queued = mutableListOf(UIMessage.user("queued:after-failure"))
        var providerCalls = 0
        var afterToolCalls = 0
        var nextRequestHistory: List<UIMessage>? = null
        val tool = Tool("failing_tool", "Synthetic only", execute = { error("synthetic tool failure") })
        val provider = TestProvider { _, messages, _ ->
            providerCalls++
            if (providerCalls == 1) {
                TextGenerationResult(
                    id = "tool-call",
                    model = model.modelId,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Tool("failing-call", tool.name, "{}")),
                    ),
                    finishReason = "tool_calls",
                )
            } else {
                assertTrue(messages.any { it.role == MessageRole.USER && it.toText() == "queued:after-failure" })
                TextGenerationResult("answered", model.modelId, UIMessage.assistant("heard it"), "stop")
            }
        }
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>) = StepRequest(
                nextRequestHistory ?: messages,
                TextGenerationParams(model),
            )

            override suspend fun afterToolExecution(step: StepContext) {
                afterToolCalls++
                assertTrue(step.messages.last().getTools().single().output.isNotEmpty())
                nextRequestHistory = step.messages + queued.toList()
                queued.clear()
            }
        }
        val events = mutableListOf<AgentEvent>()
        runAgent(
            AgentRequest(provider, setting, listOf(UIMessage.user("start")), tools = listOf(tool), stream = false),
            hooks,
        ).collect(events::add)

        assertEquals(2, providerCalls)
        assertEquals(1, afterToolCalls)
        assertEquals("heard it", (events.last() as AgentEvent.Finished).message.toText().trim())
    }

    @Test
    fun `cancelled tool execution leaves queued input untouched`() = runBlocking {
        val queued = mutableListOf(UIMessage.user("queued:cancel"))
        var afterToolCalls = 0
        val tool = Tool("cancel_tool", "Synthetic only", execute = { throw CancellationException("synthetic cancel") })
        val provider = TestProvider { _, _, _ ->
            TextGenerationResult(
                id = "tool-call",
                model = model.modelId,
                message = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Tool("cancel-call", tool.name, "{}")),
                ),
                finishReason = "tool_calls",
            )
        }
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>) = StepRequest(messages, TextGenerationParams(model))
            override suspend fun afterToolExecution(step: StepContext) {
                afterToolCalls++
                queued.clear()
            }
        }

        var cancelled = false
        try {
            runAgent(
                AgentRequest(provider, setting, listOf(UIMessage.user("start")), tools = listOf(tool), stream = false),
                hooks,
            ).collect()
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(0, afterToolCalls)
        assertEquals(listOf("queued:cancel"), queued.map { it.toText() })
    }

    @Test
    fun `pending approval does not consume queued input before tool execution`() = runBlocking {
        val queued = mutableListOf(UIMessage.user("queued:approval"))
        var afterToolCalls = 0
        val tool = Tool(
            name = "approval_tool",
            description = "Synthetic only",
            needsApproval = { true },
            execute = { listOf(UIMessagePart.Text("must not run")) },
        )
        val provider = TestProvider { _, _, _ ->
            TextGenerationResult(
                id = "approval-call",
                model = model.modelId,
                message = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Tool("approval-call", tool.name, "{}")),
                ),
                finishReason = "tool_calls",
            )
        }
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>) = StepRequest(messages, TextGenerationParams(model))
            override suspend fun afterToolExecution(step: StepContext) {
                afterToolCalls++
                queued.clear()
            }
        }
        val events = mutableListOf<AgentEvent>()

        runAgent(
            AgentRequest(provider, setting, listOf(UIMessage.user("start")), tools = listOf(tool), stream = false),
            hooks,
        ).collect(events::add)

        assertEquals(0, afterToolCalls)
        assertEquals(listOf("queued:approval"), queued.map { it.toText() })
        assertTrue((events.last() as AgentEvent.Finished).reason is AgentFinishReason.AwaitingApproval)
    }

    private class TestProvider(
        private val generate: suspend (ProviderSetting, List<UIMessage>, TextGenerationParams) -> TextGenerationResult,
    ) : Provider<ProviderSetting> {
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult = generate(providerSetting, messages, params)

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<me.rerere.ai.ui.StreamChunk> = flow { error("streaming is not used in this synthetic test") }

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = flow { error("image generation is not used in this synthetic test") }
    }
}
