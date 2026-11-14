package me.rerere.rikkahub.data.tunnel

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the R3 contract: [TsnetBridge.desiredRunning] is backed by a StateFlow, so the
 * TunnelAutoStart guardian can suspend on [TsnetBridge.desiredRunningFlow] instead of
 * waking every 30s to poll a plain boolean while the tunnel is off.
 */
class TsnetBridgeDesiredRunningTest {

    @After
    fun tearDown() {
        runBlocking { TsnetBridge.stop() }
    }

    @Test
    fun `desiredRunningFlow emits on markDesiredRunning`() = runTest {
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { TsnetBridge.desiredRunningFlow.collect { seen.add(it) } }
        runCurrent()
        assertEquals(listOf(false), seen)
        TsnetBridge.markDesiredRunning()
        runCurrent()
        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun `first true resumes a suspended waiter`() = runTest {
        val waiter = async { TsnetBridge.desiredRunningFlow.first { it } }
        runCurrent()
        assertTrue(waiter.isActive)
        TsnetBridge.markDesiredRunning()
        assertTrue(waiter.await())
    }
}
