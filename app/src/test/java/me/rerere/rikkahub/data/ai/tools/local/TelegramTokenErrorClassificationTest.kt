package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.telegram.TelegramApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Locks in the token-verification error classification used by telegram_set_token.
 *
 * The distinction is load-bearing: a HTTP 401 means the token is permanently invalid and
 * the LLM must NOT retry it (it has to fetch a fresh token from @BotFather); anything else
 * is transient and a retry is reasonable. Before this split both failures produced the same
 * opaque envelope and the model would retry a dead token forever.
 */
class TelegramTokenErrorClassificationTest {

    @Test
    fun `telegram 401 maps to token_invalid with do-not-retry recovery`() {
        val env = classifyTokenVerifyError(TelegramApiException(401, "Unauthorized"))
        assertEquals("token_invalid", env["data"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        assertTrue(
            "message mentions 401",
            env["message"]!!.jsonPrimitive.content.contains("401")
        )
        assertEquals("abort", env["data"]!!.jsonObject["recovery"]!!.jsonPrimitive.content)
        assertTrue(
            "hint tells the model not to retry",
            env["data"]!!.jsonObject["hint"]!!.jsonPrimitive.content.contains("do NOT retry", ignoreCase = true)
        )
    }

    @Test
    fun `socket timeout maps to network_error with retry recovery`() {
        val env = classifyTokenVerifyError(SocketTimeoutException("timeout"))
        assertEquals("network_error", env["data"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        assertEquals("retry", env["data"]!!.jsonObject["recovery"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown host maps to network_error`() {
        val env = classifyTokenVerifyError(UnknownHostException("api.telegram.org"))
        assertEquals("network_error", env["data"]!!.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generic IOException maps to network_error`() {
        val env = classifyTokenVerifyError(IOException("connection reset"))
        assertEquals("network_error", env["data"]!!.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non-401 telegram api error maps to network_error not token_invalid`() {
        // A 500 / 429 from Telegram is a server-side or rate-limit problem, not a bad token.
        val env = classifyTokenVerifyError(TelegramApiException(429, "Too Many Requests"))
        assertEquals("network_error", env["data"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        assertTrue(
            "detail carries the api error code",
            env["data"]!!.jsonObject["detail"]!!.jsonPrimitive.content.contains("429")
        )
    }

    @Test
    fun `every envelope carries error hint and recovery keys`() {
        listOf(
            classifyTokenVerifyError(TelegramApiException(401, "Unauthorized")),
            classifyTokenVerifyError(IOException("boom")),
        ).forEach { env ->
            val data = env["data"]!!.jsonObject
            assertTrue("has error", "error" in data)
            assertTrue("has hint", "hint" in data)
            assertTrue("has recovery", "recovery" in data)
        }
    }
}
