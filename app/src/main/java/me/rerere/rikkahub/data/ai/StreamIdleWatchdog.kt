package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicBoolean
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
    /** true = 首包前的等待超限；false = 已出流后静默超限。 */
    val awaitingFirstChunk: Boolean = false,
) : IllegalStateException(
    if (awaitingFirstChunk) {
        "Stream produced no first chunk within ${idleMs}ms; aborted by watchdog"
    } else {
        "Stream idle for more than ${idleMs}ms; aborted by watchdog"
    },
)

/** 空闲阈值默认值：120s（助手基础设定可按助手覆盖；0 = 关闭）。 */
const val DEFAULT_STREAM_IDLE_MS = 120_000L

/**
 * 首包阶段（首 token 之前）的等待上限。
 *
 * prefill 与本机/网关排队不产生任何 chunk，但它不是"卡死"：长上下文下首包耗时可
 * 达数分钟，远超 [DEFAULT_STREAM_IDLE_MS]。因此首包阶段改用独立上限——取
 * `idleMs × 4`，夹在 `idleMs` 与 [DEFAULT_STREAM_FIRST_CHUNK_MS] 之间；收到首个
 * chunk 后立刻切回 `idleMs` 判据。**上限依然存在**：超过它照样按超时中断，避免把
 * 真正卡在首包的请求永久挂住。
 */
const val DEFAULT_STREAM_FIRST_CHUNK_MS = 600_000L

/** 首包阶段相对 idleMs 的放宽倍数（配合上限使用，见上）。 */
private const val FIRST_CHUNK_BUDGET_FACTOR = 4L

private const val IDLE_POLL_INTERVAL_MS = 5_000L

/**
 * 单 chunk 间隔超过 [idleMs] 时中断流；时钟与轮询间隔可注入（测试用虚拟时间）。
 *
 * [idleMs] <= 0 表示**关闭看门狗**：直接透传原流，不引入哨兵协程与轮询开销。
 */
fun <T> Flow<T>.withIdleWatchdog(
    idleMs: Long = DEFAULT_STREAM_IDLE_MS,
    firstChunkMs: Long = DEFAULT_STREAM_FIRST_CHUNK_MS,
    clock: () -> Long = System::currentTimeMillis,
    pollMs: Long = IDLE_POLL_INTERVAL_MS,
): Flow<T> {
    if (idleMs <= 0L) return this
    // 首包阶段放宽，但不超过 idleMs × 4，也不低于 idleMs（用户设得更大时尊重其设置）
    val firstChunkLimitMs = (idleMs * FIRST_CHUNK_BUDGET_FACTOR)
        .coerceAtMost(maxOf(idleMs, firstChunkMs))
        .coerceAtLeast(idleMs)
    return channelFlow {
        val lastChunkAt = AtomicLong(clock())
        val seenFirstChunk = AtomicBoolean(false)
        val sentinel = launch {
            while (true) {
                delay(pollMs)
                val awaitingFirstChunk = !seenFirstChunk.get()
                val limit = if (awaitingFirstChunk) firstChunkLimitMs else idleMs
                val idle = clock() - lastChunkAt.get()
                if (idle > limit) {
                    close(StreamIdleTimeoutException(idle, awaitingFirstChunk))
                    return@launch
                }
            }
        }
        try {
            supervisorScope {
                // Isolate provider failure from the channel producer. Awaiting the
                // child surfaces its original failure here without canceling the
                // producer, so close(failure) can drain already-buffered chunks.
                async {
                    this@withIdleWatchdog.collect { chunk ->
                        if (!seenFirstChunk.get()) seenFirstChunk.set(true)
                        lastChunkAt.set(clock())
                        send(chunk)
                    }
                }.await()
            }
        } catch (failure: Throwable) {
            // Fail the channel rather than its producer scope. Throwing here cancels the
            // outer collector while it may be delivering a MessageUpdated event, drops
            // queued chunks, and makes a subsequent Retrying emit violate Flow transparency.
            // Parent/downstream cancellation must still cancel the provider immediately.
            if (failure is CancellationException && !currentCoroutineContext().isActive) {
                throw failure
            }
            close(failure)
        } finally {
            // 被包裹的 Flow 完成/异常时取消定时检查协程：producer scope 完成会等待子协程，
            // while(true) 不退出则 collect 永远不结束，调用方 toList/collect 永挂
            sentinel.cancel()
        }
    }
}
