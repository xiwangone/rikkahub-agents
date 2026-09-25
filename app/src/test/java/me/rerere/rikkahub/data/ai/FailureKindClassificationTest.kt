package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 失败分类新增分支的回归测试（工具配对破损 / 模型不支持图片）。
 *
 * 这两类都属"会话历史与协议/模型能力不匹配"，必须在**最前**判定 —— 否则图片类会被泛化的
 * `UNSUPPORTED` 吃掉、结构类会落到 `UNKNOWN`。同时它们都要求同时命中「对象词 + 原因词」，
 * 避免把无关错误误判成可操作提示。
 */
class FailureKindClassificationTest {

    private fun kind(message: String) = classifyFailureKind(RuntimeException(message), message)

    @Test
    fun `工具调用配对破损被单独识别`() =
        assertEquals(
            FailureKind.TOOL_PAIRING,
            kind(
                "An assistant message with 'tool_calls' must be followed by tool messages " +
                    "responding to each 'tool_call_id'. (insufficient tool messages)",
            ),
        )

    @Test
    fun `模型不支持图片被单独识别（不被 UNSUPPORTED 吃掉）`() =
        assertEquals(FailureKind.IMAGE_UNSUPPORTED, kind("This model does not support image input"))

    @Test
    fun `提到 tool 但不是配对问题时不误判`() =
        assertNotEquals(
            FailureKind.TOOL_PAIRING,
            kind("Invalid JSON in tool arguments: unexpected end of input"),
        )

    @Test
    fun `已有分类未被新分支影响`() {
        assertEquals(FailureKind.AUTH, kind("401 Unauthorized: invalid api key"))
        assertEquals(FailureKind.RATE_LIMIT, kind("429 Too Many Requests: rate limit reached"))
        assertEquals(FailureKind.CONTEXT_LENGTH, kind("This model maximum context length is 65536 tokens"))
        assertEquals(FailureKind.QUOTA, kind("402 Payment Required: insufficient balance"))
        // 回归：没有额度语义的 400 仍应归 BAD_REQUEST（别把 BAD_REQUEST 吃成 QUOTA）
        assertEquals(FailureKind.BAD_REQUEST, kind("400 Bad Request: invalid parameter"))
    }

    @Test
    fun `400 携带 insufficient credits 归为额度不足（不被 BAD_REQUEST 吃掉）`() =
        assertEquals(
            FailureKind.QUOTA,
            kind(
                "com · Chat Completions: 请求格式错误 (HTTP 400)：请求体被拒绝，通常是程序缺陷。若持续出现请反馈。 " +
                    "You have insufficient credits to make this request. Please purchase more credits to continue using the service.",
            ),
        )
}
