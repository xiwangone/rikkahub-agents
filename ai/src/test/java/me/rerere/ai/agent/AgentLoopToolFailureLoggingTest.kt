package me.rerere.ai.agent

import android.util.Log
import kotlinx.coroutines.flow.Flow
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
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.log.AppLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// 生产侧的 TAG 是 file-private, 测试文件不可见: 此处自备同值常量。
private const val TAG = "AgentLoop"

/**
 * 批 5 (级别与 tag) 级-P1-1: 工具调用本身失败是本次操作失败, 记 e。
 *
 * [AgentLoop] 的 `runTool` 把单个工具的异常转成 error 包络回灌给模型、循环继续,
 * 完整堆栈只记日志。工具抛错时应恰好留 1 条 e, 且循环仍能走到最终总结。
 */
class AgentLoopToolFailureLoggingTest {

    private val recorded = mutableListOf<Triple<Char, String, String>>()

    private val recordingSink =
        object : AppLogger.Sink {
            override fun d(tag: String, message: String) {
                recorded += Triple('D', tag, message)
            }

            override fun i(tag: String, message: String) {
                recorded += Triple('I', tag, message)
            }

            override fun w(tag: String, message: String) {
                recorded += Triple('W', tag, message)
            }

            override fun w(tag: String, message: String, tr: Throwable?) {
                recorded += Triple('W', tag, message)
            }

            override fun e(tag: String, message: String) {
                recorded += Triple('E', tag, message)
            }

            override fun e(tag: String, message: String, tr: Throwable?) {
                recorded += Triple('E', tag, message)
            }
        }

    // AppLogger 没有卸载接口; 测试后装一个直通 logcat 的 sink, 让同 JVM 内
    // 后续测试的 AppLogger 调用恢复默认行为 (只记录、不干预)。
    private val logcatSink =
        object : AppLogger.Sink {
            override fun d(tag: String, message: String) {
                Log.d(tag, message)
            }

            override fun i(tag: String, message: String) {
                Log.i(tag, message)
            }

            override fun w(tag: String, message: String) {
                Log.w(tag, message)
            }

            override fun w(tag: String, message: String, tr: Throwable?) {
                Log.w(tag, message, tr)
            }

            override fun e(tag: String, message: String) {
                Log.e(tag, message)
            }

            override fun e(tag: String, message: String, tr: Throwable?) {
                Log.e(tag, message, tr)
            }
        }

    @Before
    fun installRecordingSink() {
        recorded.clear()
        AppLogger.install(recordingSink)
    }

    @After
    fun restoreLogcatSink() {
        AppLogger.install(logcatSink)
    }

    private val model = Model(modelId = "tool-failure-test")
    private val setting = ProviderSetting.OpenAI()

    @Test
    fun `failing tool logs exactly one e line and the loop still finishes`() = runBlocking {
        var toolExecutions = 0
        var providerCalls = 0

        val tool = Tool("work", "Synthetic failing tool", execute = {
            toolExecutions++
            throw RuntimeException("boom-test-marker")
        })
        val provider = object : Provider<ProviderSetting> {
            override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

            override suspend fun generateText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): TextGenerationResult {
                providerCalls++
                if (providerCalls == 1) {
                    return TextGenerationResult(
                        id = "tool-call",
                        model = model.modelId,
                        message = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Tool("work-call", tool.name, "{}")),
                        ),
                        finishReason = "tool_calls",
                    )
                }
                return TextGenerationResult(
                    id = "summary",
                    model = model.modelId,
                    message = UIMessage.assistant("Finished work; summary delivered."),
                    finishReason = "stop",
                )
            }

            override suspend fun streamText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): Flow<StreamChunk> = flow { error("stream not used") }

            override suspend fun generateImage(
                providerSetting: ProviderSetting,
                params: ImageGenerationParams,
            ): Flow<ImageGenerationItem> = flow { error("image not used") }
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
                tools = listOf(tool),
                stream = false,
                maxSteps = 2,
                retry = RetryPolicy.NONE,
            ),
            hooks,
        ).collect(events::add)

        assertEquals(1, toolExecutions)
        assertEquals(2, providerCalls)

        val lines = recorded.filter { it.second == TAG && it.third.startsWith("Tool work failed") }
        assertEquals("one tool failure must log exactly one e line, got $recorded", 1, lines.size)
        assertEquals('E', lines[0].first)

        assertTrue((events.last() as AgentEvent.Finished).message.toText().contains("Finished work"))
    }
}
