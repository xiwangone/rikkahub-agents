package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 2026-09-10 审计发现的脱敏缺口：
 *  - 无固定前缀的 key（只靠「键名 = 值」形态识别）
 *  - 常见厂商前缀（AIza / ghp_ / AKIA / gsk_ …）
 *  - JWT
 *  - 不应误掩的普通文本（保守性回归）
 */
class LogRedactorTest {

    @Test
    fun `known prefixes are masked`() {
        val cases = listOf(
            "sk-abcdef123456",
            "sk-ant-api03-abcdef123456",
            "xai-abcdef123456",
            "gsk_abcdef1234567890",
            "AIzaSyA1234567890abcdef",
            "ghp_abcdefghij1234567890",
            "github_pat_11ABCDEFG0123456789",
            "AKIAIOSFODNN7EXAMPLE",
            "hf_abcdefghij1234567890",
            "xoxb-1234567890-abcdefghij",
        )
        for (secret in cases) {
            val out = LogRedactor.maskText("value=$secret")
            assertFalse("should mask $secret -> $out", out.contains(secret))
            assertTrue("should keep marker for $secret -> $out", out.contains("***"))
        }
    }

    @Test
    fun `bearer token is masked but keeps scheme`() {
        val out = LogRedactor.maskText("Authorization: Bearer 35fdda4a9f9a4cf2b7c1e0d8")
        assertTrue(out, out.startsWith("Authorization: Bearer "))
        assertFalse(out, out.contains("35fdda4a9f9a4cf2b7c1e0d8"))
    }

    @Test
    fun `prefixedless key with sensitive name is masked`() {
        // 无固定前缀的国内厂商 key（本机 vault 的实测形态：35 位 / 93 位混合串）
        val short = "AbCdEf1234567890AbCdEf1234567890abc"
        val shortOut = LogRedactor.maskText("""{"api_key": "$short"}""")
        assertFalse(shortOut, shortOut.contains(short))

        val long = "c2Vuc2V0aW1lLXRva2VuLTEyMzQ1Njc4OTAtYWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo"
        val longOut = LogRedactor.maskText("x-api-key: $long")
        assertFalse(longOut, longOut.contains(long))

        val queryOut = LogRedactor.maskText("POST https://example.com/v1/chat?key=0123456789abcdef")
        assertFalse(queryOut, queryOut.contains("0123456789abcdef"))
    }

    @Test
    fun `jwt is masked`() {
        val jwt =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val out = LogRedactor.maskText("token=$jwt")
        assertFalse(out, out.contains(jwt))
    }

    @Test
    fun `plain text is not over-masked`() {
        // 保守性回归：普通日志不得被掩成 *** —— 键名不敏感 / 值过短等
        val samples = listOf(
            "tool_surface_report: 106 tools, order_is_sorted=false",
            "GET https://api.example.com/v1/models?page=2",
            "key=short",
            "error: connection reset by peer",
            "model=gpt-4o-mini, tokens=1024",
        )
        for (s in samples) {
            assertEquals("over-masked: $s", s, LogRedactor.maskText(s))
        }
    }

    @Test
    fun `short secret is fully hidden`() {
        assertEquals("***", LogRedactor.maskSecret("abc123"))
        assertEquals("abc***xyz", LogRedactor.maskSecret("abcdefxyz"))
    }

    @Test
    fun `url query params are masked in free text`() {
        val p = "S".repeat(16)
        val masked = LogRedactor.maskText("POST https://example.com/v1/chat?key=$p&model=gpt-4o")
        assertFalse(masked, masked.contains(p))
        assertTrue(masked, masked.contains("model=gpt-4o"))
        val token = LogRedactor.maskText("GET https://x/y?token=abcdefgh12345")
        assertFalse(token, token.contains("abcdefgh12345"))
    }

    @Test
    fun `headers and url keep previous behaviour`() {
        assertEquals("***", LogRedactor.maskHeader("Authorization", "abc123"))
        assertEquals("application/json", LogRedactor.maskHeader("Content-Type", "application/json"))
        assertEquals(
            "https://x/v1?key=***&model=gpt-4o",
            LogRedactor.maskUrl("https://x/v1?key=sk-abcdef&model=gpt-4o"),
        )
    }
}
