package me.rerere.rikkahub.data.vault

import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditAnomalyDetectorTest {

    private val now = 1_700_000_000_000L

    private fun log(
        credentialName: String = "CRED_A",
        action: String = "local_use",
        minutesAgo: Long = 1,
    ) = VaultAuditLogEntity(
        credentialName = credentialName,
        caller = "ai-tool",
        action = action,
        tsMs = now - minutesAgo * 60_000L,
    )

    @Test
    fun `安静时不报异常`() {
        val logs = List(3) { log(minutesAgo = 1) }
        assertTrue(AuditAnomalyDetector.detect(logs, now = now).isEmpty())
    }

    @Test
    fun `窗口内次数超阈记为高频`() {
        val logs = List(AuditAnomalyDetector.BURST_CALLS) { log(minutesAgo = 1) }
        val anomalies = AuditAnomalyDetector.detect(logs, now = now)
        assertEquals(1, anomalies.size)
        assertTrue(anomalies[0].highFrequency)
        assertFalse(anomalies[0].wideSweep)
        assertEquals(AuditAnomalyDetector.BURST_CALLS, anomalies[0].calls)
    }

    @Test
    fun `窗口内用途过宽记为批量遍历`() {
        val logs = List(AuditAnomalyDetector.SWEEP_ACTIONS) { i -> log(action = "action_$i") }
        val anomalies = AuditAnomalyDetector.detect(logs, now = now)
        assertEquals(1, anomalies.size)
        assertTrue(anomalies[0].wideSweep)
        assertFalse(anomalies[0].highFrequency)
    }

    @Test
    fun `窗口之外的记录不计入`() {
        val logs = List(AuditAnomalyDetector.BURST_CALLS) { log(minutesAgo = AuditAnomalyDetector.WINDOW_MINUTES + 1L) }
        assertTrue(AuditAnomalyDetector.detect(logs, now = now).isEmpty())
    }

    @Test
    fun `按凭证分别判定并按次数降序`() {
        val logs = List(AuditAnomalyDetector.BURST_CALLS) { log(credentialName = "CRED_A", minutesAgo = 1) } +
            List(AuditAnomalyDetector.BURST_CALLS + 5) { log(credentialName = "CRED_B", minutesAgo = 1) } +
            List(2) { log(credentialName = "CRED_C", minutesAgo = 1) }
        val anomalies = AuditAnomalyDetector.detect(logs, now = now)
        assertEquals(listOf("CRED_B", "CRED_A"), anomalies.map { it.credentialName })
    }
}
