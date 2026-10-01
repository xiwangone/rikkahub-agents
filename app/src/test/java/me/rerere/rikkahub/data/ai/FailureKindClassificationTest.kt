package me.rerere.rikkahub.data.ai

import me.rerere.ai.util.HttpException
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

    // 以下三类报错同样含 image + unsupported 字样，但根因不是模型能力：
    // 若误判成 IMAGE_UNSUPPORTED，会触发「剥图重试」把用户的图丢掉。
    @Test
    fun `图片格式类报错不误判为模型不支持图片`() =
        assertNotEquals(
            FailureKind.IMAGE_UNSUPPORTED,
            kind("Unsupported image format: image/webp is not supported"),
        )

    @Test
    fun `图片尺寸类报错不误判为模型不支持图片`() =
        assertNotEquals(
            FailureKind.IMAGE_UNSUPPORTED,
            kind("image dimensions too large; unsupported size 8000x6000"),
        )

    @Test
    fun `上下文超限提及图片不误判为模型不支持图片`() =
        assertNotEquals(
            FailureKind.IMAGE_UNSUPPORTED,
            kind("context_length exceeded: too many image tokens in this request"),
        )

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

    @Test
    fun `DeepSeek 风控文案归为内容安全（不被 UNKNOWN 吃掉）`() =
        assertEquals(
            FailureKind.CONTENT_SAFETY,
            kind("Content Exists Risk (request_id: 7d6e70d7-175a-4eca-b7f4-f6480f36174e)"),
        )

    @Test
    fun `各平台风控文案都归为内容安全`() {
        // Anthropic / OpenAI / Azure OpenAI / 阿里百炼 的官方措辞
        assertEquals(FailureKind.CONTENT_SAFETY, kind("400 Output blocked by content filtering policy"))
        assertEquals(
            FailureKind.CONTENT_SAFETY,
            kind("Your input image may contain content that is not allowed by our safety system."),
        )
        assertEquals(
            FailureKind.CONTENT_SAFETY,
            kind("The response was filtered due to the prompt triggering Azure OpenAI's content management policy"),
        )
        assertEquals(FailureKind.CONTENT_SAFETY, kind("Input data may contain inappropriate content."))
    }

    @Test
    fun `小米 MiMo 的 421 内容拦截按状态码识别`() =
        assertEquals(
            FailureKind.CONTENT_SAFETY,
            classifyFailureKind(
                HttpException(message = "请求被拒绝", statusCode = 421),
                "请求被拒绝",
            ),
        )

    @Test
    fun `机器可读风控码能单独判出、不误伤同状态码的其它错误`() {
        // 服务端只给 code、文案本地化时，靠 code 也要判出来
        assertEquals(
            FailureKind.CONTENT_SAFETY,
            classifyFailureKind(
                HttpException(
                    message = "请求被拒绝",
                    statusCode = 400,
                    providerErrorCode = "content_policy_violation",
                ),
                "请求被拒绝",
            ),
        )
        // 文案与 code 都不指向风控时，不得误判（否则会给出「换个说法再试」的错误引导）
        assertNotEquals(
            FailureKind.CONTENT_SAFETY,
            classifyFailureKind(
                HttpException(
                    message = "invalid parameter",
                    statusCode = 400,
                    providerErrorCode = "invalid_parameter",
                ),
                "invalid parameter",
            ),
        )
    }
}
