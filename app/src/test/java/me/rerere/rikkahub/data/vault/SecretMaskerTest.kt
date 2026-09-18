package me.rerere.rikkahub.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SecretMasker masking contract. This matters because a masking regression is a real
 * secret-leak vector: e.g. the URL-embedded `?tavilyApiKey=…` and the pre-`FileLogSink`
 * non-masking were both caught only during manual review. These tests lock the public
 * [SecretMasker.mask] behaviour (generic key shapes, PEM blocks, public keys untouched,
 * exact-value replacement, and the empty/too-short-value pollution guard).
 */
class SecretMaskerTest {

    // ---- 通用密钥形态（不依赖凭证库即可掩）----

    @Test
    fun `GitHub token 形态被掩`() {
        assertEquals("*** x", SecretMasker.mask("ghp_abcdefghijklmnopqrstuvwx x", emptyList()))
    }

    @Test
    fun `OpenAI sk 形态被掩`() {
        assertEquals("*** x", SecretMasker.mask("sk-proj-Abcdefghij1234567890abcdefghij1234567890 x", emptyList()))
    }

    @Test
    fun `JWT 形态被掩`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghij1234567890"
        assertEquals("*** x", SecretMasker.mask("$jwt x", emptyList()))
    }

    // ---- PEM 私钥结构 ----

    @Test
    fun `PEM 私钥块被掩`() {
        val pem = "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"
        val out = SecretMasker.mask("prefix $pem suffix", emptyList())
        assertFalse(out.contains("BEGIN PRIVATE KEY"))
        assertTrue(out.contains("prefix *** suffix"))
    }

    // ---- 公钥不掩 ----

    @Test
    fun `公钥前缀不掩`() {
        val pub = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI test@host"
        assertEquals(pub, SecretMasker.mask(pub, emptyList()))
    }

    // ---- 精确值替换 ----

    @Test
    fun `精确值替换为掩码`() {
        assertEquals("v=***", SecretMasker.mask("v=abcdef", listOf(SecretRule("abcdef", exact = true))))
    }

    // ---- 空值/极短值防污染 ----

    @Test
    fun `空值不会逐字符污染输出`() {
        assertEquals("hello", SecretMasker.mask("hello", listOf(SecretRule("", exact = true))))
    }
}
