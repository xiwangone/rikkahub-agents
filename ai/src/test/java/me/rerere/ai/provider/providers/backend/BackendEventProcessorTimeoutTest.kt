package me.rerere.ai.provider.providers.backend

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超时 / 断流被伪装成正常结束。
 *
 * [BackendEventProcessor.process] 的事件循环把超时、远端流终止、远端抛错
 * 三种出口合并成同一条 `?: break`，随后只要没收到过 turn_done 就补发
 * `Finish("stop")`：调用方看到的是一次"正常结束"，实际是回复被截断、
 * 且没有任何用量——超时静默丢失。
 *
 * 正确行为：未收到 turn_done 就结束时，不发 `Finish("stop")`，而应以错误收尾。
 *
 * 注意：首内容超时常量是 300_000ms 且不可注入，ai 模块测试依赖里也没有
 * kotlinx-coroutines-test，真实等满 300s 不可行。这里用已终止的事件流
 * 触发同一条 `break` 出口（`events.first()` 在流耗尽时抛错，
 * 同样被 runCatching 吃掉后走 `?: break`），是同一行代码的等价探针。
 *
 * 注意 2：事件流必须用 Channel 搭——process() 在循环里反复调 `events.first()`，
 * 冷流（flowOf / flow { emit(..) }）每次 first() 都会从头重播，同一事件会被
 * 反复消费、循环永远不结束；Channel 保证每次 first() 取走下一个事件，
 * 取完即流终止。
 */
class BackendEventProcessorTimeoutTest {
    /** 按顺序逐个吐出事件、吐完即终止的事件流（对应服务端流结束 / 断连）。 */
    private fun eventsFlow(vararg events: SseEvent): Flow<SseEvent> {
        val channel = Channel<SseEvent>(Channel.UNLIMITED)
        events.forEach { channel.trySend(it) }
        channel.close()
        return channel.receiveAsFlow()
    }

    private fun processor(events: Flow<SseEvent>): BackendEventProcessor =
        BackendEventProcessor(
            providerSetting = ProviderSetting.Backend(),
            events = events,
            interactionHandler = BackendInteractionHandler.NOOP,
            sessionContext =
                BackendSessionContext(
                    sessionKey = null,
                    sessionPaths = ConcurrentHashMap(),
                    sessionPathStore = SessionPathStore.NOOP,
                    onSessionPath = {},
                ),
        )

    /** 收集全部 chunk，并捕获 process() 抛出的异常（无异常时为 null）。 */
    private suspend fun runProcessor(
        processor: BackendEventProcessor,
    ): Pair<List<StreamChunk>, Throwable?> {
        val chunks = mutableListOf<StreamChunk>()
        val thrown =
            runCatching {
                flow<StreamChunk> { processor.run { process() } }.collect { chunks += it }
            }.exceptionOrNull()
        return chunks to thrown
    }

    @Test
    fun streamEndsAfterPartialTextMustNotEmitFakeFinish() =
        runBlocking {
            // 已收到一段正文增量，随后事件流在 turn_done 之前终止
            // （生产上对应 SSE 超时 / 断连后无事件到达）。
            val (chunks, thrown) =
                runProcessor(processor(eventsFlow(SseEvent(kind = "text", text = "hello"))))

            assertTrue(
                "未收到 turn_done 就结束时，不应伪装成正常结束 Finish(stop)，实际收到：$chunks",
                chunks.none { it is StreamChunk.Finish && it.finishReason == "stop" },
            )
            // StreamChunk 目前没有 Error 变体，错误只能以 flow 抛异常的方式
            // 向上传播（BackendProvider.streamText 的 flow 会原样抛出）；
            // 若后续修复改为新增 Error chunk，本断言需同步放宽为接受 Error chunk。
            assertNotNull("未收到 turn_done 就结束时，process() 必须以错误收尾，而不是静默完成", thrown)
        }

    @Test
    fun streamEndsBeforeAnyContentMustNotEmitFakeFinish() =
        runBlocking {
            // 一条内容事件都没收到流就终止（连接建立后立即断连 / 首内容超时）。
            val (chunks, thrown) = runProcessor(processor(eventsFlow()))

            assertTrue(
                "一条内容都没收到就结束时，不应伪装成正常结束 Finish(stop)，实际收到：$chunks",
                chunks.none { it is StreamChunk.Finish && it.finishReason == "stop" },
            )
            assertNotNull("一条内容都没收到就结束时，process() 必须以错误收尾，而不是静默完成", thrown)
        }

    @Test
    fun turnDoneStillEmitsSingleFinish() =
        runBlocking {
            // 对照：收到 turn_done 才是真正的正常结束，Finish("stop") 应且仅应发一次。
            // 本测试在 bug 修复前后都应通过，防止修复把合法收尾也改成抛错。
            val (chunks, thrown) =
                runProcessor(
                    processor(
                        eventsFlow(
                            SseEvent(kind = "text", text = "hello"),
                            SseEvent(kind = "turn_done"),
                        )
                    )
                )

            assertNull("收到 turn_done 时 process() 不应抛错", thrown)
            val finishes = chunks.filterIsInstance<StreamChunk.Finish>()
            assertEquals("turn_done 后 Finish 应恰好发一次", 1, finishes.size)
            assertEquals("stop", finishes.single().finishReason)
        }
}
