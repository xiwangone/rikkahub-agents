package me.rerere.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
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

/**
 * 步前注入的消息（预算/进度提醒）必须换来一次模型请求。
 *
 * 回归背景：注入了提醒、但当前没有待执行工具时，循环会把「没有待执行工具」当成「模型已说完」，
 * 直接收尾 → 提醒只进历史、不产生请求，用户看到「提醒之后直接断」。
 */
class AgentLoopInjectedNoticeTest {
    private val model = Model(modelId = "injected-notice-test")
    private val setting = ProviderSetting.OpenAI()

    @Test
    fun `injected notice at step boundary triggers another model request`() = runBlocking {
        val requests = mutableListOf<List<UIMessage>>()
        var providerCalls = 0
        var noticeInjected = false

        val provider = TestProvider { _, messages, _ ->
            providerCalls++
            requests += messages
            TextGenerationResult(
                id = "response-$providerCalls",
                model = model.modelId,
                message = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("turn $providerCalls")),
                ),
                finishReason = "stop",
            )
        }
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>) =
                StepRequest(messages, TextGenerationParams(model))

            override suspend fun beforeStep(step: StepContext): StepDecision {
                if (step.stepIndex >= 1 && !noticeInjected) {
                    noticeInjected = true
                    return StepDecision.Append(
                        UIMessage.user("PROGRESS NOTICE: report progress first").copy(isSynthetic = true),
                    )
                }
                return StepDecision.Continue
            }
        }
        val events = mutableListOf<AgentEvent>()

        runAgent(
            AgentRequest(
                provider = provider,
                providerSetting = setting,
                messages = listOf(UIMessage.user("start")),
                stream = false,
                maxSteps = 4,
                retry = RetryPolicy.NONE,
            ),
            hooks,
        ).collect(events::add)

        // 注入过的提醒必须进入某一次请求，并因此多出一次生成
        assertEquals(2, providerCalls)
        assertTrue(requests[1].any { it.toText().contains("PROGRESS NOTICE") })
        assertEquals("turn 2", (events.last() as AgentEvent.Finished).message.toText().trim())
    }

    @Test
    fun `no injection still finishes after a single request`() = runBlocking {
        var providerCalls = 0
        val provider = TestProvider { _, _, _ ->
            providerCalls++
            TextGenerationResult(
                id = "response-$providerCalls",
                model = model.modelId,
                message = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("done")),
                ),
                finishReason = "stop",
            )
        }
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>) =
                StepRequest(messages, TextGenerationParams(model))
        }
        val events = mutableListOf<AgentEvent>()

        runAgent(
            AgentRequest(
                provider = provider,
                providerSetting = setting,
                messages = listOf(UIMessage.user("start")),
                stream = false,
                maxSteps = 4,
                retry = RetryPolicy.NONE,
            ),
            hooks,
        ).collect(events::add)

        assertEquals(1, providerCalls)
        assertEquals("done", (events.last() as AgentEvent.Finished).message.toText().trim())
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
