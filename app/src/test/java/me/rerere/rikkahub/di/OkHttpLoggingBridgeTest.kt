package me.rerere.rikkahub.di

import me.rerere.common.android.Logging
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 批 2 桥接：HttpLoggingInterceptor 的输出经 OkHttpLoggingBridge 进 TextLog。
 * 开关（批 1 的请求日志开关）只管应用内记录：关时不写 TextLog，但 logcat
 * 仍保留（与今天默认 logger 的行为一致）。
 */
class OkHttpLoggingBridgeTest {

    @Before
    fun clearLogs() {
        Logging.clear()
    }

    @After
    fun reset() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(false)
    }

    @Test
    fun `logLine masks secrets and records to TextLog when request logging is on`() {
        Logging.setRequestLoggingEnabled(true)
        OkHttpLoggingBridge.logLine("Authorization: Bearer sk-secret-1234567890")

        val logs = Logging.getTextLogs()
        assertTrue(logs.any { it.tag == "OkHttp" && it.message.contains("Bearer") })
        assertTrue(logs.none { it.message.contains("sk-secret-1234567890") })
    }

    @Test
    fun `logLine records nothing to TextLog when request logging is off`() {
        Logging.setRequestLoggingEnabled(false)
        OkHttpLoggingBridge.logLine("--> GET https://example.com/api")

        assertTrue(Logging.getTextLogs().none { it.tag == "OkHttp" })
    }
}
