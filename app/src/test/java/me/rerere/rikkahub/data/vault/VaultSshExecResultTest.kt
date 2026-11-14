package me.rerere.rikkahub.data.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vault_ssh_exec 成功输出格式单测（B-2 / T6）：成功分支必须与 ssh_exec_saved 一致，
 * 返回结构化 JSON（success/exit_code/stdout/stderr），不再是纯文本拼接
 *（exit=0\n--- stdout ---\n...）。超时分支本就返回 JSON 信封，不动。
 */
class VaultSshExecResultTest {

    @Test
    fun `exit 0 yields success true with all four fields`() {
        val json = buildVaultSshExecResult(0, "hello\n", "", false)
        assertEquals(JsonPrimitive(true), json["success"])
        assertEquals(JsonPrimitive(0), json["exit_code"])
        assertEquals(JsonPrimitive("hello\n"), json["stdout"])
        assertEquals(JsonPrimitive(""), json["stderr"])
        assertFalse(json.containsKey("note"))
    }

    @Test
    fun `non-zero exit yields success false`() {
        val json = buildVaultSshExecResult(3, "", "boom", false)
        assertEquals(JsonPrimitive(false), json["success"])
        assertEquals(JsonPrimitive(3), json["exit_code"])
        assertEquals(JsonPrimitive("boom"), json["stderr"])
    }

    @Test
    fun `first connect adds the fingerprint note, later connects do not`() {
        val first = buildVaultSshExecResult(0, "", "", true)
        assertTrue(first.containsKey("note"))
        assertEquals(
            "First connection; this host fingerprint was recorded and will be verified on later connections",
            first["note"]?.jsonPrimitive?.contentOrNull,
        )
        val later = buildVaultSshExecResult(0, "", "", false)
        assertFalse(later.containsKey("note"))
    }

    @Test
    fun `result serializes to valid JSON with the same fields`() {
        val json = buildVaultSshExecResult(0, "out", "err", false)
        val parsed = Json.parseToJsonElement(json.toString()).jsonObject
        assertEquals(true, parsed["success"]?.jsonPrimitive?.boolean)
        assertEquals(0, parsed["exit_code"]?.jsonPrimitive?.int)
        assertEquals("out", parsed["stdout"]?.jsonPrimitive?.contentOrNull)
        assertEquals("err", parsed["stderr"]?.jsonPrimitive?.contentOrNull)
    }
}
