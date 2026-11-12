package me.rerere.agenttools

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.log.AppLogger
import me.rerere.workspace.WorkspaceFileEntry
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 批 3 静-P1-9：ConfigTools 的静默点补日志。
 *
 * 用录制 sink 证明损坏配置 / 备份跳过真的走了 AppLogger。agent-tools 模块
 * 的单测没有 `isReturnDefaultValues`，android.util.Log 是 stub，测试后
 * 装空 sink 而不是 logcat sink，避免后续测试的 AppLogger 调用撞上 stub。
 */
class ConfigToolsLoggingTest {

    private val recorded = mutableListOf<Triple<Char, String, String>>()

    private val recordingSink =
        object : AppLogger.Sink {
            override fun d(tag: String, message: String) {
                recorded += Triple('D', tag, message)
            }

            override fun i(tag: String, message: String) {
                recorded += Triple('I', tag, message)
            }

            override fun w(tag: String, message: String) {
                recorded += Triple('W', tag, message)
            }

            override fun w(tag: String, message: String, tr: Throwable?) {
                recorded += Triple('W', tag, message)
            }

            override fun e(tag: String, message: String) {
                recorded += Triple('E', tag, message)
            }

            override fun e(tag: String, message: String, tr: Throwable?) {
                recorded += Triple('E', tag, message)
            }
        }

    private val noopSink =
        object : AppLogger.Sink {
            override fun d(tag: String, message: String) = Unit

            override fun i(tag: String, message: String) = Unit

            override fun w(tag: String, message: String) = Unit

            override fun w(tag: String, message: String, tr: Throwable?) = Unit

            override fun e(tag: String, message: String) = Unit

            override fun e(tag: String, message: String, tr: Throwable?) = Unit
        }

    @Before
    fun setUp() {
        recorded.clear()
        AppLogger.install(recordingSink)
    }

    @After
    fun tearDown() {
        AppLogger.install(noopSink)
    }

    /** 基于内存 map 的 [AgentWorkspaceIO] fake；readText 可按调用次数模拟读失败。 */
    private class CountingIO : AgentWorkspaceIO {
        val files = mutableMapOf<String, String>()

        /** 第几次 readText 调用开始抛 IOException（0 = 不抛）。 */
        var failReadFromCall: Int = 0
        private var readCalls: Int = 0

        override suspend fun writeText(path: String, text: String, overwrite: Boolean) {
            files[path] = text
        }

        override suspend fun readText(path: String): String {
            readCalls++
            if (failReadFromCall > 0 && readCalls >= failReadFromCall) {
                throw IOException("simulated read failure: $path")
            }
            return files[path] ?: error("no such file: $path")
        }

        override suspend fun listFiles(path: String): List<WorkspaceFileEntry> =
            files.keys.filter { it.startsWith(path) }.map {
                WorkspaceFileEntry(it, it.substringAfterLast('/'), false, 0L, 0L)
            }
    }

    private fun execText(tool: me.rerere.ai.core.Tool, argsJson: String): String = runBlocking {
        val parts = tool.execute(Json.parseToJsonElement(argsJson))
        (parts.first { it is UIMessagePart.Text } as UIMessagePart.Text).text
    }

    @Test
    fun `corrupt settings logs w on parse failure`() {
        val io = CountingIO()
        // 无法解析、截断补齐也救不回来的损坏内容
        io.files["config/settings.json"] = """{"ui.lang": }"""
        val tools = createConfigTools(io, emptyMap())
        val getAllTool = tools.first { it.name == "config_get_all" }

        // 两次解析都失败：生产行为是记一条 w 再抛 IllegalStateException
        //（ConfigStore.parseSettings），而不是返回输出；抛异常本身就是
        //「不悄悄报空配置」的保证。
        assertThrows(IllegalStateException::class.java) {
            execText(getAllTool, "{}")
        }
        assertTrue(
            "expected a W log for the corrupt settings file, got: $recorded",
            recorded.any { (level, tag, message) ->
                level == 'W' && tag == "ConfigTools" && "parseSettings" in message
            },
        )
    }

    @Test
    fun `save with unreadable existing config logs w for skipped backup`() {
        val io = CountingIO()
        io.files["config/settings.json"] = """{"ui.lang":"zh"}"""
        // 第 1 次读（load）成功，第 2 次读（save 前的备份读取）失败；
        // listFiles 仍能看到文件，于是备份跳过分支被触发。
        io.failReadFromCall = 2
        val tools = createConfigTools(io, emptyMap())
        val setTool = tools.first { it.name == "config_set" }

        execText(setTool, """{"key":"agent.theme","value":"\"dark\""}""")

        assertTrue(
            "expected a W log for the skipped backup, got: $recorded",
            recorded.any { (level, tag, message) ->
                level == 'W' && tag == "ConfigTools" && "skipping backup" in message
            },
        )
        // 覆盖本身照常完成
        val saved = Json.parseToJsonElement(io.files.getValue("config/settings.json")).jsonObject
        assertTrue("agent.theme" in saved)
    }
}
