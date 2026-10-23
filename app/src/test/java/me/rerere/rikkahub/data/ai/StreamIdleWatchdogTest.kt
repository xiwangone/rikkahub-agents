package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
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
    fun `delivers buffered burst in order before original upstream failure`() = runBlocking {
        val expectedFailure = IOException("synthetic provider disconnect")
        val received = mutableListOf<Int>()
        val upstream = flow {
            emit(1)
            emit(2)
            emit(3)
            emit(4)
            throw expectedFailure
        }.withIdleWatchdog(idleMs = idleMs, pollMs = pollMs)

        val failure = withTimeout(2_000) {
            runCatching {
                upstream.collect {
                    received += it
                    delay(15)
                }
            }.exceptionOrNull()
        }

        assertEquals(listOf(1, 2, 3, 4), received)
        assertTrue("expected IOException but was $failure", failure is IOException)
        assertEquals(expectedFailure.message, failure?.message)
    }

    @Test
    fun `delivers queued partial before provider cancellation while consumer remains active`() = runBlocking {
        val providerCancellation = CancellationException("provider stopped after partial")
        val received = mutableListOf<Int>()
        val upstream = flow {
            emit(7)
            emit(8)
            throw providerCancellation
        }.withIdleWatchdog(idleMs = idleMs, pollMs = pollMs)

        val failure = withTimeout(2_000) {
            runCatching {
                upstream.collect {
                    received += it
                    delay(15)
                }
            }.exceptionOrNull()
        }

        assertEquals(listOf(7, 8), received)
        assertTrue("expected provider CancellationException but was $failure", failure is CancellationException)
        assertEquals(providerCancellation.message, failure?.message)
        assertTrue("consumer scope should remain active", coroutineContext.isActive)
    }

    @Test
    fun `downstream cancellation cancels upstream promptly without more output`() = runBlocking {
        val firstChunkReceived = CompletableDeferred<Unit>()
        val upstreamFinalized = CompletableDeferred<Unit>()
        var providerAttempts = 0
        val received = mutableListOf<Int>()
        val upstream = flow {
            providerAttempts++
            try {
                emit(1)
                awaitCancellation()
                @Suppress("UNREACHABLE_CODE")
                emit(2)
            } finally {
                upstreamFinalized.complete(Unit)
            }
        }.withIdleWatchdog(idleMs = idleMs, pollMs = pollMs)

        val collector = launch {
            upstream.collect {
                received += it
                firstChunkReceived.complete(Unit)
            }
        }
        withTimeout(2_000) { firstChunkReceived.await() }
        withTimeout(2_000) { collector.cancelAndJoin() }
        withTimeout(2_000) { upstreamFinalized.await() }

        assertEquals(listOf(1), received)
        assertEquals("downstream cancellation must not restart the provider", 1, providerAttempts)
    }

    @Test
    fun `idle timeout cancels endless upstream after draining queued chunks`() = runBlocking {
        val clock = AtomicLong(1_000L)
        val firstChunkReceived = CompletableDeferred<Unit>()
        val upstreamWaiting = CompletableDeferred<Unit>()
        val upstreamFinalized = CompletableDeferred<Unit>()
        val collectedFailure = CompletableDeferred<Throwable?>()
        val received = mutableListOf<Int>()
        val upstream = flow {
            try {
                emit(10)
                emit(20)
                emit(30)
                upstreamWaiting.complete(Unit)
                awaitCancellation()
            } finally {
                upstreamFinalized.complete(Unit)
            }
        }.withIdleWatchdog(idleMs = 50, clock = clock::get, pollMs = 5)

        val collector = launch {
            val failure = runCatching {
                upstream.collect {
                    received += it
                    if (it == 10) {
                        firstChunkReceived.complete(Unit)
                        delay(40)
                    }
                }
            }.exceptionOrNull()
            collectedFailure.complete(failure)
        }
        withTimeout(2_000) { firstChunkReceived.await() }
        withTimeout(2_000) { upstreamWaiting.await() }
        clock.addAndGet(51L)
        withTimeout(2_000) { collector.join() }
        withTimeout(2_000) { upstreamFinalized.await() }
        val failure = withTimeout(2_000) { collectedFailure.await() }

        assertEquals(listOf(10, 20, 30), received)
        assertTrue("expected StreamIdleTimeoutException but was $failure", failure is StreamIdleTimeoutException)
        assertTrue("timeout should retain idle threshold", (failure as StreamIdleTimeoutException).idleMs >= 50)
    }
}
