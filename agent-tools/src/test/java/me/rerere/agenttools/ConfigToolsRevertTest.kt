package me.rerere.agenttools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * config_revert 语义：按审计里的 prior 恢复，而不是按 set 记录的新值。
 *
 * set() 在写新值前记录旧值（prior），revert() 只看最近一条 set 记录的
 * prior：prior 有值则写回，prior 为 null（set 前键不存在）则删键；没有
 * set 记录或 set 记录没有 prior 字段（老审计）时不动键。
 */
class ConfigToolsRevertTest {

    /** 基于内存 map 的 [AgentWorkspaceIO] fake: 缺失文件时抛异常，与真实实现一致。 */
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

    private fun auditEntries(io: InMemoryIO): List<JsonObject> =
        io.files.getValue("config/audit.jsonl").lines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }

    private fun setValue(io: InMemoryIO, key: String, jsonValue: String) {
        val setTool = createConfigTools(io, emptyMap()).first { it.name == "config_set" }
        val args = buildJsonObject {
            put("key", key)
            put("value", jsonValue)
        }
        execText(setTool, args.toString())
    }

    private fun revert(io: InMemoryIO, key: String): String {
        val revertTool = createConfigTools(io, emptyMap()).first { it.name == "config_revert" }
        val args = buildJsonObject { put("key", key) }
        return execText(revertTool, args.toString())
    }

    @Test
    fun `set records prior value in audit`() {
        val io = InMemoryIO()
        setValue(io, "k", "\"A\"")
        setValue(io, "k", "\"B\"")

        val entries = auditEntries(io)
        assertEquals(2, entries.size)
        // 第一次 set 时键不存在，prior 为 null。
        assertEquals("null", entries[0]["prior"].toString())
        // 第二次 set 的 prior 是 A，value 仍是新值 B。
        assertEquals("\"A\"", entries[1]["prior"].toString())
        assertEquals("\"B\"", entries[1]["value"].toString())
    }

    @Test
    fun `revert restores prior value and second revert keeps it`() {
        val io = InMemoryIO()
        setValue(io, "k", "\"A\"")
        setValue(io, "k", "\"B\"")
        assertEquals("\"B\"", storedSettings(io)["k"].toString())

        val first = revert(io, "k")
        assertTrue("first revert should restore A, got: $first", first.contains("restored prior value"))
        assertEquals("\"A\"", storedSettings(io)["k"].toString())

        // 第二次 revert 仍以最近一条 set 记录的 prior（A）恢复，不删键。
        val second = revert(io, "k")
        assertTrue("second revert should restore A again, got: $second", second.contains("restored prior value"))
        assertEquals("\"A\"", storedSettings(io)["k"].toString())
    }

    @Test
    fun `revert removes a key that did not exist before its only set`() {
        val io = InMemoryIO()
        setValue(io, "n", "\"C\"")
        assertTrue("n" in storedSettings(io))

        val out = revert(io, "n")
        assertTrue("revert should remove n, got: $out", out.contains("did not exist before the last set"))
        assertFalse("n" in storedSettings(io))
    }

    @Test
    fun `revert leaves key unchanged when last set record has no prior`() {
        val io = InMemoryIO()
        io.files["config/settings.json"] = """{"old":"v2"}"""
        // 老版本审计：set 记录没有 prior 字段。
        io.files["config/audit.jsonl"] =
            """{"id":1,"action":"set","key":"old","value":"v2","at":1}""" + "\n"

        val out = revert(io, "old")
        assertTrue("legacy set record must not revert, got: $out", out.contains("旧值未知"))
        assertEquals("\"v2\"", storedSettings(io)["old"].toString())
    }

    @Test
    fun `revert with no set history leaves key unchanged`() {
        val io = InMemoryIO()
        io.files["config/settings.json"] = """{"x":1}"""

        val out = revert(io, "x")
        assertTrue("no set history must not revert, got: $out", out.contains("无 set 历史"))
        assertEquals("1", storedSettings(io)["x"].toString())
    }
}
