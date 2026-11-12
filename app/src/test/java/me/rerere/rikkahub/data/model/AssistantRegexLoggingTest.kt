package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.log.AppLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 批 2 框-P0-6：Assistant.compileRegexCached 的 printStackTrace 已改 AppLog.e。
 * 编译失败只记一条错误，不把 pattern 原文写进日志（pattern 可能含用户文本）。
 */
class AssistantRegexLoggingTest {

    @Before
    fun enableAppLog() {
        setAppLogEnabled(true)
        AppLog.clear()
    }

    @After
    fun disableAppLog() {
        AppLog.clear()
        setAppLogEnabled(false)
    }

    private fun setAppLogEnabled(value: Boolean) {
        val field = AppLog::class.java.getDeclaredField("enabled")
        field.isAccessible = true
        field.setBoolean(AppLog, value)
    }

    @Test
    fun `invalid regex pattern is logged as error without the pattern content`() {
        val assistant =
            Assistant(
                regexes =
                    listOf(
                        AssistantRegex(
                            id = Uuid.random(),
                            findRegex = "[invalid-batch2-test",
                            affectingScope = setOf(AssistantAffectScope.USER),
                        ),
                    ),
            )
        val result = "hello world".replaceRegexes(assistant, AssistantAffectScope.USER)

        // 编译失败的正则被跳过，原文本原样返回
        assertEquals("hello world", result)
        val logs = AppLog.getLogs()
        assertTrue(logs.any { it.level == 'E' && it.tag == "Assistant" })
        assertTrue(logs.none { it.message.contains("[invalid-batch2-test") })
    }
}
