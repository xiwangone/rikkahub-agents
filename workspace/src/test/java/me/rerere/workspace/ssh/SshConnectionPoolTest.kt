package me.rerere.workspace.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the R4 contract for the landed lazy sweeper: constructing a pool must not
 * start the "ssh-idle-sweeper" thread — the sweeper is created on demand when the
 * first connection enters the pool, so 0 connections means 0 timer wakeups.
 *
 * (Batch A pinned the same contract via an isSweepScheduled() seam that the landed
 * implementation does not expose; the live sweeper-thread count is the observable
 * equivalent.)
 */
class SshConnectionPoolTest {

    @Test
    fun `empty pool starts no sweeper thread`() {
        val before = sweeperThreadCount()
        SshConnectionPool().use { pool ->
            assertEquals(before, sweeperThreadCount())
        }
        assertEquals(before, sweeperThreadCount())
    }

    private fun sweeperThreadCount(): Int =
        Thread.getAllStackTraces().keys.count { it.name == "ssh-idle-sweeper" }
}
