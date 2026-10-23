package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamIdleWatchdogTest {

    private class FakeClock(var now: Long = 1_000_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    /** 轮询/阈值都取短值：真实等待毫秒级，空闲完全由手动时钟控制。 */
    private val pollMs = 5L
    private val idleMs = 60L

    @Test
    fun `passes chunks through when stream stays active`() = runBlocking {
        val clock = FakeClock()
        val chunks = flow {
            emit(1)
            delay(20)
            clock.advance(30)
            emit(2)
            delay(20)
            clock.advance(30)
            emit(3)
        }.withIdleWatchdog(idleMs = idleMs, clock = { clock.now }, pollMs = pollMs).toList()

        assertEquals(listOf(1, 2, 3), chunks)
    }

    @Test
    fun `aborts stream when idle exceeds threshold`() = runBlocking {
        val clock = FakeClock()
        val flow = flow {
            emit(1)
            clock.advance(100)
            delay(30)
            emit(2)
        }.withIdleWatchdog(idleMs = idleMs, clock = { clock.now }, pollMs = pollMs)

        val failure = runCatching { flow.toList() }.exceptionOrNull()

        assertTrue("expected StreamIdleTimeoutException but was $failure", failure is StreamIdleTimeoutException)
    }

    @Test
    fun `normal completion closes channel before watchdog fires`() = runBlocking {
        val clock = FakeClock()
        val chunks = flowOf(1, 2, 3)
            .withIdleWatchdog(idleMs = idleMs, clock = { clock.now }, pollMs = pollMs)
            .toList()

        assertEquals(listOf(1, 2, 3), chunks)
    }

    @Test
    fun `prefill longer than idle is tolerated before first chunk`() = runBlocking {
        val clock = FakeClock()
        // 首包前静默 200ms：超过 idleMs(60)，但首包预算 = idleMs × 4 = 240 → 不应被杀
        val chunks = flow {
            clock.advance(200)
            delay(30)
            emit(1)
            emit(2)
        }.withIdleWatchdog(idleMs = idleMs, clock = { clock.now }, pollMs = pollMs).toList()

        assertEquals(listOf(1, 2), chunks)
    }

    @Test
    fun `prefill beyond first-chunk budget still aborts`() = runBlocking {
        val clock = FakeClock()
        // 首包前静默 300ms：超过首包预算 240 → 仍要超时，不能退化成“永不超时”
        val flow = flow {
            clock.advance(300)
            delay(40)
            emit(1)
        }.withIdleWatchdog(idleMs = idleMs, clock = { clock.now }, pollMs = pollMs)

        val failure = runCatching { flow.toList() }.exceptionOrNull()

        assertTrue("expected StreamIdleTimeoutException but was $failure", failure is StreamIdleTimeoutException)
        assertTrue("phase should be first-chunk", (failure as StreamIdleTimeoutException).awaitingFirstChunk)
    }

    @Test
    fun `after first chunk the strict idle threshold applies again`() = runBlocking {
        val clock = FakeClock()
        // 出过流后静默 100ms > idleMs(60)：进准判据恢复，不应再享受首包预算
        val flow = flow {
            emit(1)
            clock.advance(100)
            delay(30)
            emit(2)
        }.withIdleWatchdog(idleMs = idleMs, clock = { clock.now }, pollMs = pollMs)

        val failure = runCatching { flow.toList() }.exceptionOrNull()

        assertTrue("expected StreamIdleTimeoutException but was $failure", failure is StreamIdleTimeoutException)
        assertTrue("phase should be mid-stream", !(failure as StreamIdleTimeoutException).awaitingFirstChunk)
    }
}
