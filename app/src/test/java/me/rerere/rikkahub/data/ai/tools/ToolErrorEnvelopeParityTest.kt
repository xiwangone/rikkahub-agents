package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.agent.ToolErrorEnvelope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 防退化：[ToolErrors.envelope] 只是 ai 内核层 [ToolErrorEnvelope] 的委托，
 * 两边对同一输入必须产出完全相同的 JSON（含 extra 保留键过滤）。
 */
class ToolErrorEnvelopeParityTest {

    @Test
    fun `ToolErrors envelope equals ToolErrorEnvelope envelope`() {
        val args = arrayOf(
            ToolErrorEnvelope.INVALID_PARAMS to "bad params",
            ToolErrorEnvelope.INTERNAL_ERROR to "boom",
        )
        for ((code, message) in args) {
            val viaFacade = ToolErrors.envelope(
                code = code,
                message = message,
                error = "invalid_argument",
                recovery = "fix_param",
                hint = "fix it",
                extra = mapOf(
                    "current" to JsonPrimitive("a"),
                    "error" to JsonPrimitive("spoofed"),
                ),
            )
            val viaKernel = ToolErrorEnvelope.envelope(
                code = code,
                message = message,
                error = "invalid_argument",
                recovery = "fix_param",
                hint = "fix it",
                extra = mapOf(
                    "current" to JsonPrimitive("a"),
                    "error" to JsonPrimitive("spoofed"),
                ),
            )
            assertEquals(viaKernel, viaFacade)
        }
    }

    @Test
    fun `ToolErrors envelopeFor still derives code and recovery from its maps`() {
        val viaFacade = ToolErrors.envelopeFor(error = "timeout", message = "timed out")
        val viaKernel = ToolErrorEnvelope.envelope(
            code = ToolErrors.categoryFor("timeout"),
            message = "timed out",
            error = "timeout",
            recovery = ToolErrors.recoveryFor("timeout"),
        )
        assertEquals(viaKernel, viaFacade)
    }
}
