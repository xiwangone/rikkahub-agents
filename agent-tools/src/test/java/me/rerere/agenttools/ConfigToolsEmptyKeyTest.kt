package me.rerere.agenttools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.log.AppLogger
import me.rerere.workspace.WorkspaceFileEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `config_set` 空 key 拦截（`require(key.isNotBlank())`，[ConfigTools.kt][createSetTool]）。
 *
 * 显式传空串（或全空白串）必须被拦在写库之前；缺 key 是另一条路（`error("key is required")`），
 * 不在本用例内。合法 key 仍可正常写入，作为对照。
 */
class ConfigToolsEmptyKeyTest {

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
        AppLogger.install(noopSink)
    }

    @After
    fun tearDown() {
        AppLogger.install(noopSink)
    }

    /** 基于内存 map 的 [AgentWorkspaceIO] fake。 */
    private class MemoryIO : AgentWorkspaceIO {
        val files = mutableMapOf<String, String>()

        override suspend fun writeText(path: String, text: String, overwrite: Boolean) {
            files[path] = text
        }

        override suspend fun readText(path: String): String =
            files[path] ?: error("no such file: $path")

        override suspend fun listFiles(path: String): List<WorkspaceFileEntry> =
            files.keys.filter { it.startsWith(path) }.map {
                WorkspaceFileEntry(it, it.substringAfterLast('/'), false, 0L, 0L)
            }
    }

    private fun setTool(io: MemoryIO): me.rerere.ai.core.Tool {
        val tools = createConfigTools(io, emptyMap())
        return tools.first { it.name == "config_set" }
    }

    private fun execute(tool: me.rerere.ai.core.Tool, argsJson: String): List<UIMessagePart> =
        runBlocking { tool.execute(Json.parseToJsonElement(argsJson)) }

    private fun setFailsWithBlankKey(keyJson: String) {
        val io = MemoryIO()
        io.files["config/settings.json"] = "{}"
        val tool = setTool(io)
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            execute(tool, """{"key":$keyJson,"value":"\"dark\""}""")
        }
        assertEquals("key must not be blank", thrown.message)
        // 拦截发生在写库之前：配置文件原样未动。
        assertEquals("{}", io.files.getValue("config/settings.json"))
    }

    @Test
    fun `empty key is rejected before writing`() {
        setFailsWithBlankKey("\"\"")
    }

    @Test
    fun `blank-only key is rejected before writing`() {
        setFailsWithBlankKey("\"   \"")
    }

    @Test
    fun `non-blank key still writes`() {
        val io = MemoryIO()
        io.files["config/settings.json"] = "{}"
        val tool = setTool(io)

        val parts = execute(tool, """{"key":"agent.theme","value":"\"dark\""}""")
        assertEquals(1, parts.size)

        val saved = Json.parseToJsonElement(io.files.getValue("config/settings.json")).jsonObject
        assertTrue("agent.theme" in saved)
    }
}
