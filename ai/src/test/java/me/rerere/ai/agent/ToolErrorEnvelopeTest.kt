package me.rerere.ai.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [ToolErrorEnvelope] 是 ai 内核层工具错误信封的唯一实现（JSON-RPC 2.0 形状）；
 * app 侧 [me.rerere.rikkahub.data.ai.tools.ToolErrors] 只是它的门面。
 */
class ToolErrorEnvelopeTest {

    @Test
    fun `envelope has JSON-RPC shape`() {
        val env = ToolErrorEnvelope.envelope(
            code = ToolErrorEnvelope.INVALID_PARAMS,
            message = "x and y are required",
            error = "invalid_argument",
            recovery = ToolErrorEnvelope.RECOVERY_FIX_PARAM,
            hint = "Provide both x and y.",
        )
        assertEquals(-32602, env["code"]!!.jsonPrimitive.content.toInt())
        assertEquals("x and y are required", env["message"]!!.jsonPrimitive.content)
        val data = env["data"]!!.jsonObject
        assertEquals("invalid_argument", data["error"]!!.jsonPrimitive.content)
        assertEquals("fix_param", data["recovery"]!!.jsonPrimitive.content)
        assertEquals("Provide both x and y.", data["hint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `envelope omits absent optional fields`() {
        val env = ToolErrorEnvelope.envelope(
            code = ToolErrorEnvelope.INTERNAL_ERROR,
            message = "boom",
        )
        val data = env["data"]!!.jsonObject
        assertFalse("error must be omitted when null", "error" in data)
        assertFalse("recovery must be omitted when null", "recovery" in data)
        assertFalse("hint must be omitted when blank", "hint" in data)
    }

    @Test
    fun `envelope keeps extra but never lets it overwrite reserved keys`() {
        val env = ToolErrorEnvelope.envelope(
            code = ToolErrorEnvelope.SERVER_ERROR,
            message = "m",
            error = "tool_failed",
            recovery = ToolErrorEnvelope.RECOVERY_ABORT,
            extra = mapOf(
                "current" to JsonPrimitive("a"),
                "error" to JsonPrimitive("spoofed"),
                "recovery" to JsonPrimitive("retry"),
                "hint" to JsonPrimitive("spoofed hint"),
            ),
        )
        val data = env["data"]!!.jsonObject
        assertEquals("a", data["current"]!!.jsonPrimitive.content)
        assertEquals("tool_failed", data["error"]!!.jsonPrimitive.content)
        assertEquals("abort", data["recovery"]!!.jsonPrimitive.content)
        assertFalse("extra hint must not overwrite the real hint slot", "hint" in data)
    }

    @Test
    fun `partsFor uses server-error abort by default`() {
        val parts = ToolErrorEnvelope.partsFor(error = "tool_failed", message = "boom")
        assertEquals(1, parts.size)
        val text = (parts[0] as UIMessagePart.Text).text
        val env: JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
        assertEquals(-32000, env["code"]!!.jsonPrimitive.content.toInt())
        val data = env["data"]!!.jsonObject
        assertEquals("tool_failed", data["error"]!!.jsonPrimitive.content)
        assertEquals("abort", data["recovery"]!!.jsonPrimitive.content)
    }

    @Test
    fun `partsFor honors explicit code and recovery`() {
        val parts = ToolErrorEnvelope.partsFor(
            error = "not_found",
            message = "Tool x not found",
            code = ToolErrorEnvelope.METHOD_NOT_FOUND,
            recovery = ToolErrorEnvelope.RECOVERY_FIX_PARAM,
            hint = "Check available tools.",
        )
        val text = (parts[0] as UIMessagePart.Text).text
        val env: JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
        assertEquals(-32601, env["code"]!!.jsonPrimitive.content.toInt())
        val data = env["data"]!!.jsonObject
        assertEquals("not_found", data["error"]!!.jsonPrimitive.content)
        assertEquals("fix_param", data["recovery"]!!.jsonPrimitive.content)
        assertEquals("Check available tools.", data["hint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `partsFor carries extra through`() {
        val parts = ToolErrorEnvelope.partsFor(
            error = "tool_failed",
            message = "boom",
            extra = mapOf("exception" to JsonPrimitive("RuntimeException")),
        )
        val text = (parts[0] as UIMessagePart.Text).text
        val env: JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
        assertEquals(
            "RuntimeException",
            env["data"]!!.jsonObject["exception"]!!.jsonPrimitive.content,
        )
    }
}
