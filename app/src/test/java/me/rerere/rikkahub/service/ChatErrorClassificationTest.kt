package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 错误分类（provider 原文 → 可操作建议）的回归测试。
 *
 * 目标有两个：**常见错误都能认出来**，以及**无关错误不被误判**（误判会让用户看到不相关的建议，
 * 比不给建议更糟）。匹配规则是保守的"对象词 + 原因词同时命中"，本测试就是钉住这个边界。
 */
class ChatErrorClassificationTest {

    private fun classify(message: String) = classifyChatError(RuntimeException(message))

    @Test
    fun `工具调用配对破损`() =
        assertEquals(
            ChatErrorSolution.ConversationHistoryStructure,
            classify(
                "An assistant message with 'tool_calls' must be followed by tool messages " +
                    "responding to each 'tool_call_id'. (insufficient tool messages)",
            ),
        )

    @Test
    fun `当前模型不支持图片`() =
        assertEquals(ChatErrorSolution.ImageNotSupported, classify("This model does not support image input"))

    @Test
    fun `认证失败`() = assertEquals(ChatErrorSolution.AuthFailed, classify("401 Unauthorized: invalid api key"))

    @Test
    fun `限流`() = assertEquals(ChatErrorSolution.RateLimited, classify("429 Too Many Requests: rate limit reached"))

    @Test
    fun `配额或余额不足`() =
        assertEquals(
            ChatErrorSolution.QuotaExceeded,
            classify("You exceeded your current quota, please check your plan and billing"),
        )

    @Test
    fun `模型不可用`() =
        assertEquals(ChatErrorSolution.ModelUnavailable, classify("404 model not found: the model does not exist"))

    @Test
    fun `上下文超长`() =
        assertEquals(ChatErrorSolution.ContextTooLong, classify("This model maximum context length is 65536 tokens"))

    @Test
    fun `内容被安全策略拦截`() =
        assertEquals(
            ChatErrorSolution.ContentFiltered,
            classify("Your request was rejected as a result of our safety system (content policy)"),
        )

    @Test
    fun `服务端错误`() =
        assertEquals(ChatErrorSolution.ServerUnavailable, classify("503 Service Unavailable: upstream overloaded"))

    @Test
    fun `网络失败`() = assertEquals(ChatErrorSolution.NetworkError, classify("timeout: read timed out"))

    @Test
    fun `无关错误不误判`() {
        assertNull(classify("Something unrelated happened"))
        // 提到 tool 但不是"配对缺失" → 不应被判成历史结构问题
        assertNull(classify("Invalid JSON in tool arguments: unexpected end of input"))
    }
}
