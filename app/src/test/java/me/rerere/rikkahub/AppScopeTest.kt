package me.rerere.rikkahub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

class AppScopeTest {
    @Test
    fun `app scope default dispatcher is default, not main`() {
        val scope = AppScope()
        try {
            val dispatcher = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
            assertEquals(Dispatchers.Default, dispatcher)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `app scope launch executes on a default dispatcher worker`() = runBlocking {
        val scope = AppScope()
        try {
            val threadName = CompletableDeferred<String>()
            scope.launch { threadName.complete(Thread.currentThread().name) }
            val name = withTimeout(5_000) { threadName.await() }
            assertTrue(name.startsWith("DefaultDispatcher"))
        } finally {
            scope.cancel()
        }
    }
}
