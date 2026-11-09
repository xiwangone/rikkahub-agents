package me.rerere.agenttools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0: config/settings.json 损坏时, 一次 config_set 会静默丢掉全部已有配置键.
 *
 * 根因: [ConfigStore.load] 把解析异常整个吞掉返回空对象, 而 set() 是
 * "先 load 再全量 save", 于是损坏文件 + 一次 set() = 全量覆盖成只剩新键.
 *
 * 正确行为: set() 不得丢弃 load 失败前已持久化的键 —— 要么保留原有键
 * (并把损坏内容隔离/备份后继续写), 要么抛明确错误拒绝写入.
 * 本测试按"保留原有键"断言. bug 未修时, 该测试失败即证实 P0.
 */
class ConfigToolsCorruptTest {

    /** 基于内存 map 的 [AgentWorkspaceIO] fake: 缺失文件时抛异常, 与真实实现一致. */
    private class InMemoryIO : AgentWorkspaceIO {
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

    private fun execText(tool: me.rerere.ai.core.Tool, argsJson: String): String = runBlocking {
        val parts = tool.execute(Json.parseToJsonElement(argsJson))
        (parts.first { it is UIMessagePart.Text } as UIMessagePart.Text).text
    }

    private fun storedSettings(io: InMemoryIO): JsonObject =
        Json.parseToJsonElement(io.files.getValue("config/settings.json")).jsonObject

    @Test
    fun `set on corrupt settings preserves pre-existing keys`() {
        val io = InMemoryIO()
        // 先写入一份含两个键的有效配置, 再把它写坏 (截断成非法 JSON: 缺右括号).
        io.files["config/settings.json"] = """{"ui.lang":"zh","agent.verbosity":3}"""
        io.files["config/settings.json"] = """{"ui.lang":"zh","agent.verbosity":3"""

        val tools = createConfigTools(io, emptyMap())
        val setTool = tools.first { it.name == "config_set" }

        execText(setTool, """{"key":"agent.theme","value":"\"dark\""}""")

        val saved = storedSettings(io)
        assertTrue(
            "corrupt settings.json + one set() must not drop ui.lang, got: $saved",
            "ui.lang" in saved,
        )
        assertTrue(
            "corrupt settings.json + one set() must not drop agent.verbosity, got: $saved",
            "agent.verbosity" in saved,
        )
        assertEquals("\"dark\"", saved["agent.theme"].toString())
    }

    @Test
    fun `get_all on corrupt settings does not silently report empty config`() {
        val io = InMemoryIO()
        io.files["config/settings.json"] = """{"ui.lang":"zh","agent.verbosity":3"""

        val tools = createConfigTools(io, emptyMap())
        val getAllTool = tools.first { it.name == "config_get_all" }

        val out = execText(getAllTool, "{}")

        // 损坏文件不是空配置: 要么如实报错, 要么返回旧值, 但不能悄悄返回 {}.
        assertTrue("corrupt settings.json must not be silently reported as empty, got: $out", out != "{}")
    }
}
