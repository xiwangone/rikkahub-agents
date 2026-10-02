package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 流式空闲看门狗（痛点 P117：长会话下出现"思考计时一直涨、token 不来"的滴流/卡死，
 * 如 549s @0.3 tok/s——SSE 连接活着但服务端不吐数据）。
 *
 * 包装被包裹的 Flow：每收到一个 chunk 刷新最近活动时间；哨兵协程周期检查，空闲超过
 * [idleMs] 即以 [StreamIdleTimeoutException] 关闭被包裹的 Flow。异常沿流传给调用方，由
 * GenerationLoop 既有的 retryWhen 重试判据决定重试或终止（不引入新的重试语义；
 * receivedMeaningfulOutput 门槛保持不变，避免部分输出后的重复生成）。
 */
class StreamIdleTimeoutException(
    val idleMs: Long,
) : IllegalStateException("Stream idle for more than ${idleMs}ms; aborted by watchdog")

/** 空闲阈值默认值：120s（助手基础设定可按助手覆盖；0 = 关闭）。 */
const val DEFAULT_STREAM_IDLE_MS = 120_000L

private const val IDLE_POLL_INTERVAL_MS = 5_000L

/**
 * 单 chunk 间隔超过 [idleMs] 时中断流；时钟与轮询间隔可注入（测试用虚拟时间）。
 *
 * [idleMs] <= 0 表示**关闭看门狗**：直接透传原流，不引入哨兵协程与轮询开销。
 */
fun <T> Flow<T>.withIdleWatchdog(
    idleMs: Long = DEFAULT_STREAM_IDLE_MS,
    clock: () -> Long = System::currentTimeMillis,
    pollMs: Long = IDLE_POLL_INTERVAL_MS,
): Flow<T> {
    if (idleMs <= 0L) return this
    return channelFlow {
        val lastChunkAt = AtomicLong(clock())
        val sentinel = launch {
            while (true) {
                delay(pollMs)
                val idle = clock() - lastChunkAt.get()
                if (idle > idleMs) {
                    close(StreamIdleTimeoutException(idle))
                    return@launch
                }
            }
        }
        try {
            collect { chunk ->
                lastChunkAt.set(clock())
                send(chunk)
            }
        } finally {
            // 被包裹的 Flow 完成/异常时取消定时检查协程：producer scope 完成会等待子协程，
            // while(true) 不退出则 collect 永远不结束，调用方 toList/collect 永挂
            sentinel.cancel()
        }
    }
}
