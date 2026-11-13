package me.rerere.rikkahub.data.vault

import me.rerere.rikkahub.utils.LogRedactor
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

    @Test
    fun `短 sk token 也被掩`() {
        // 旧口径 {20,} 会漏掉 10 字符左右的短 token；与日志脱敏共用前缀表后应掩
        assertEquals("*** x", SecretMasker.mask("sk-abcdef123456 x", emptyList()))
    }

    @Test
    fun `短 GitHub token 也被掩`() {
        assertEquals("*** x", SecretMasker.mask("ghp_abcdef1234 x", emptyList()))
    }

    @Test
    fun `与日志脱敏的掩码口径一致`() {
        // 同一段凭证在日志与工具输出里都必须被掩（此前两边阈值不同，短 token 只在日志里被掩）
        val secrets = listOf(
            "sk-abcdef123456",
            "sk-ant-abcdef123456",
            "xai-abcdef123456",
            "gsk_abcdef1234567890",
            "ghp_abcdef1234",
            "AKIAIOSFODNN7EXAMPLE", // ALLOW-FAKE-SECRET: 假样本，不是真凭据
        )
        for (secret in secrets) {
            assertFalse(
                "SecretMasker should mask $secret",
                SecretMasker.mask("v=$secret", emptyList()).contains(secret),
            )
            assertFalse(
                "LogRedactor should mask $secret",
                LogRedactor.maskText("v=$secret").contains(secret),
            )
        }
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

    @Test
    fun `FIDO2 公钥前缀不掩`() {
        val ed25519Sk = "sk-ssh-ed25519@openssh.com AAAAGnNrLXNzaC1lZDI1NTE5QG9wZW5zc2guY29t user@host"
        val ecdsaSk = "sk-ecdsa-sha2-nistp256@openssh.com AAAAInNrLWVjZHNhLXNoYTItbmlzdHAyNTZAb3BlbnNzaC5jb20= user@host"
        assertEquals(ed25519Sk, SecretMasker.mask(ed25519Sk, emptyList()))
        assertEquals(ecdsaSk, SecretMasker.mask(ecdsaSk, emptyList()))
    }

    @Test
    fun `普通英文散文中的小写 bearer 不被误掩而真实 Bearer 令牌被掩`() {
        val prose = "Uses standard bearer authentication and bearer credentials."
        assertEquals(prose, SecretMasker.mask(prose, emptyList()))
        val authHeader = "Authorization: Bearer AbCdEfGh1234567890"
        assertFalse(SecretMasker.mask(authHeader, emptyList()).contains("AbCdEfGh1234567890"))
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

    // ---- P2-9 派生形态（base64 / 倒序 / 逐字符分隔）----

    private val derivedSecret = "s3cret-Value-9876"
    private val derivedRules = listOf(SecretRule(derivedSecret, exact = true))

    @Test
    fun `base64 形态被掩`() {
        val bytes = derivedSecret.toByteArray(Charsets.UTF_8)
        val std = java.util.Base64.getEncoder().encodeToString(bytes)
        val url = java.util.Base64.getUrlEncoder().encodeToString(bytes)
        assertFalse(SecretMasker.mask("v=$std", derivedRules).contains(std))
        assertFalse(SecretMasker.mask("v=${std.trimEnd('=')}", derivedRules).contains(std.trimEnd('=')))
        assertFalse(SecretMasker.mask("v=$url", derivedRules).contains(url))
    }

    @Test
    fun `倒序形态被掩`() {
        val reversed = derivedSecret.reversed()
        assertEquals("v=***", SecretMasker.mask("v=$reversed", derivedRules))
    }

    @Test
    fun `逐字符分隔形态被掩`() {
        val spaced = derivedSecret.map { it.toString() }.joinToString(" ")
        assertEquals("***", SecretMasker.mask(spaced, derivedRules))
        val dashed = derivedSecret.map { it.toString() }.joinToString("-")
        assertEquals("***", SecretMasker.mask(dashed, derivedRules))
    }

    @Test
    fun `短秘密不做派生`() {
        // 倒序 / base64 派生只对 ≥8 字符的秘密做：短值的派生形态（如倒序 "ba"）误掩面太大
        val shortRules = listOf(SecretRule("ab12", exact = true))
        assertEquals("21ba", SecretMasker.mask("21ba", shortRules))
        assertEquals("YWIxMg==", SecretMasker.mask("YWIxMg==", shortRules))
        // 但短秘密本身仍精确掩码
        assertEquals("v=***", SecretMasker.mask("v=ab12", shortRules))
    }

    @Test
    fun `分隔形态要求完整字符序列`() {
        // 只出秘密的前半段（带分隔符）不应被掩——分隔容忍要求完整字符序列按序出现
        val partial = derivedSecret.take(6).map { it.toString() }.joinToString(" ")
        assertEquals(partial, SecretMasker.mask(partial, derivedRules))
    }
}
