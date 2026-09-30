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
}
