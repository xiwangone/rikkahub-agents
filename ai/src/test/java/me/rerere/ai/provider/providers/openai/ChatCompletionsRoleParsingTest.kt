package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 中转可能返回空字符串或非标准 role：解析必须宽松匹配、未知值回退为 ASSISTANT，
 * 而不是让 MessageRole.valueOf 直接抛异常导致整轮生成失败。
 */
class ChatCompletionsRoleParsingTest {
    @Test
    fun `empty role falls back to assistant`() {
        val message = parseMessage(buildJsonObject {
            put("role", "")
            put("content", "hi")
        })
        assertEquals(MessageRole.ASSISTANT, message.role)
    }

    @Test
    fun `unknown role falls back to assistant`() {
        val message = parseMessage(buildJsonObject {
            put("role", "nonsense")
            put("content", "hi")
        })
        assertEquals(MessageRole.ASSISTANT, message.role)
    }

    @Test
    fun `missing role falls back to assistant`() {
        val message = parseMessage(buildJsonObject {
            put("content", "hi")
        })
        assertEquals(MessageRole.ASSISTANT, message.role)
    }

    @Test
    fun `known roles still parse case-insensitively`() {
        assertEquals(MessageRole.ASSISTANT, MessageRole.fromValueOrNull("assistant"))
        assertEquals(MessageRole.ASSISTANT, MessageRole.fromValueOrNull("ASSISTANT"))
        assertEquals(MessageRole.USER, MessageRole.fromValueOrNull("user"))
        assertEquals(MessageRole.TOOL, MessageRole.fromValueOrNull("tool"))
        assertEquals(null, MessageRole.fromValueOrNull(""))
        assertEquals(null, MessageRole.fromValueOrNull("nonsense"))
        assertEquals(null, MessageRole.fromValueOrNull(null))
    }
}
