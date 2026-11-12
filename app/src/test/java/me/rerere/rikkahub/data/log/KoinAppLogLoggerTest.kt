package me.rerere.rikkahub.data.log

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koin.core.logger.Level

/**
 * 批 2 桥接：Koin 日志经 KoinAppLogLogger 写进 AppLog。
 * AppLog 的缓冲受 enabled 开关门控（单元测试里 startIfEnabled 不跑），
 * 这里用反射开测试用开关；只影响测试进程内的全局单例，测完复原。
 * 另：批 1 引入最低级别阈值（默认 'I'，D 级会被挡在 buffer 外），
 * 有该字段时把它降到 'D' 再断言映射，测完恢复 'I'；干净基线无此字段时跳过。
 */
class KoinAppLogLoggerTest {

    @Before
    fun enableAppLog() {
        setAppLogEnabled(true)
        setMinLevelIfPresent('D')
        AppLog.clear()
    }

    @After
    fun disableAppLog() {
        AppLog.clear()
        setMinLevelIfPresent('I')
        setAppLogEnabled(false)
    }

    private fun setAppLogEnabled(value: Boolean) {
        val field = AppLog::class.java.getDeclaredField("enabled")
        field.isAccessible = true
        field.setBoolean(AppLog, value)
    }

    private fun setMinLevelIfPresent(value: Char) {
        runCatching {
            val field = AppLog::class.java.getDeclaredField("minLevel")
            field.isAccessible = true
            field.setChar(AppLog, value)
        }
    }

    @Test
    fun `display maps each Koin level to the matching AppLog level`() {
        val logger = KoinAppLogLogger()
        logger.display(Level.DEBUG, "debug-msg")
        logger.display(Level.INFO, "info-msg")
        logger.display(Level.WARNING, "warn-msg")
        logger.display(Level.ERROR, "error-msg")
        logger.display(Level.NONE, "none-msg")

        val logs = AppLog.getLogs()
        assertTrue(logs.any { it.level == 'D' && it.tag == "Koin" && it.message == "debug-msg" })
        assertTrue(logs.any { it.level == 'I' && it.tag == "Koin" && it.message == "info-msg" })
        assertTrue(logs.any { it.level == 'W' && it.tag == "Koin" && it.message == "warn-msg" })
        assertTrue(logs.any { it.level == 'E' && it.tag == "Koin" && it.message == "error-msg" })
        assertTrue(logs.none { it.message == "none-msg" })
    }
}
