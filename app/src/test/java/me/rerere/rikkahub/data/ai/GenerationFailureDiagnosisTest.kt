package me.rerere.rikkahub.data.ai

import android.app.Application
import org.robolectric.RuntimeEnvironment
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 失败诊断文案映射（[diagnoseFailure]）：失败种类 → 资源化标签。
 *
 * 分类本身（[classifyFailureKind]）已有 [FailureKindClassificationTest] 覆盖 12 例；
 * 这里只测「种类 → 文案」这一层：每种失败拿到的标签必须是对应种类的资源串，
 * 错配会让模型面板错误卡片给出误导性提示。`raw` 取首条非空异常消息（见 [retryFailureReason]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class GenerationFailureDiagnosisTest {

    private val context: Application by lazy {
        RuntimeEnvironment.getApplication()
    }

    private fun diagnose(failure: Throwable) = diagnoseFailure(context, failure)

    @Test
    fun `auth failure maps to the auth label`() {
        val diag = diagnose(RuntimeException("401 Unauthorized: invalid api key"))
        assertEquals(FailureKind.AUTH, diag.kind)
        assertEquals("Key invalid or expired (check your API key)", diag.label)
    }

    @Test
    fun `quota failure maps to the quota label`() {
        val diag = diagnose(RuntimeException("402 Payment Required: insufficient balance"))
        assertEquals(FailureKind.QUOTA, diag.kind)
        assertEquals("Insufficient balance or quota (top up and retry)", diag.label)
    }

    @Test
    fun `rate limit failure maps to the rate limit label`() {
        val diag = diagnose(RuntimeException("429 Too Many Requests: rate limit reached"))
        assertEquals(FailureKind.RATE_LIMIT, diag.kind)
        assertEquals("Too many requests (rate limited, retry shortly)", diag.label)
    }

    @Test
    fun `timeout failure maps to the network label`() {
        val diag = diagnose(SocketTimeoutException("Read timed out"))
        assertEquals(FailureKind.NETWORK, diag.kind)
        assertEquals("Network down or endpoint unreachable", diag.label)
    }

    @Test
    fun `content safety failure maps to the content safety label`() {
        val diag = diagnose(RuntimeException("400 Output blocked by content filtering policy"))
        assertEquals(FailureKind.CONTENT_SAFETY, diag.kind)
        assertEquals("Blocked by content safety check (try rephrasing)", diag.label)
    }

    @Test
    fun `tool pairing failure maps to the tool pairing label`() {
        val diag = diagnose(
            RuntimeException(
                "An assistant message with 'tool_calls' must be followed by tool messages " +
                    "responding to each 'tool_call_id'. (insufficient tool messages)",
            ),
        )
        assertEquals(FailureKind.TOOL_PAIRING, diag.kind)
        assertEquals(
            "Tool call without its result (repaired automatically; start a new chat if it persists)",
            diag.label,
        )
    }

    @Test
    fun `unrecognized failure maps to the unknown label`() {
        val diag = diagnose(RuntimeException("something completely unexpected happened"))
        assertEquals(FailureKind.UNKNOWN, diag.kind)
        assertEquals("Unrecognized error (see the raw server message below)", diag.label)
    }

    @Test
    fun `raw keeps the first non-blank failure message`() {
        val diag = diagnose(RuntimeException("429 Too Many Requests: rate limit reached"))
        assertEquals("429 Too Many Requests: rate limit reached", diag.raw)
    }
}
