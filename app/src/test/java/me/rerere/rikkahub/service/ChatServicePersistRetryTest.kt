package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServicePersistRetryTest {

    @Test
    fun `persistConversationWithRetry succeeds after transient failure`() = runBlocking {
        var calls = 0
        val attemptErrors = mutableListOf<Int>()
        var finalFailureCalled = false

        val ok = persistConversationWithRetry(
            conversationId = Uuid.random(),
            maxAttempts = 2,
            retryDelayMs = 0L,
            onAttemptFailure = { attempt, _ -> attemptErrors.add(attempt) },
            onFinalFailure = { finalFailureCalled = true },
        ) {
            calls++
            if (calls == 1) error("transient io error")
        }

        assertTrue(ok)
        assertEquals(2, calls)
        assertEquals(listOf(1), attemptErrors)
        assertFalse(finalFailureCalled)
    }

    @Test
    fun `persistConversationWithRetry reports final failure when retries exhausted`() = runBlocking {
        var calls = 0
        val attemptErrors = mutableListOf<Int>()
        var finalError: Throwable? = null

        val ok = persistConversationWithRetry(
            conversationId = Uuid.random(),
            maxAttempts = 2,
            retryDelayMs = 0L,
            onAttemptFailure = { attempt, _ -> attemptErrors.add(attempt) },
            onFinalFailure = { finalError = it },
        ) {
            calls++
            throw OutOfMemoryError("simulated oom")
        }

        assertFalse(ok)
        assertEquals(2, calls)
        assertEquals(listOf(1, 2), attemptErrors)
        assertEquals("simulated oom", finalError?.message)
    }

    @Test(expected = CancellationException::class)
    fun `persistConversationWithRetry rethrows CancellationException immediately`(): Unit = runBlocking {
        persistConversationWithRetry(
            conversationId = Uuid.random(),
            maxAttempts = 3,
            retryDelayMs = 0L,
        ) {
            throw CancellationException("cancelled")
        }
    }
}
