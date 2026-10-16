package me.rerere.rikkahub.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CredentialValueSanitizer` 契约测试：写入前清洗必须"去掉不可见、保住可真值"。
 *
 * 重点覆盖两类回归风险：
 * 1. 清洗过度 —— 把多行私钥的换行吃掉，等于悄悄损坏用户密钥；
 * 2. 清洗不足 —— 控制符入库后破坏命令解析（本类存在的原因）。
 */
class CredentialValueSanitizerTest {

    @Test
    fun `keeps printable ascii untouched`() {
        // 刻意不使用任何真实密钥前缀：测试数据不应携带凭据形态（否则形态扫描会误报）
        val value = "test-value-abcdefghijklmnopqrstuvwxyz-0123456789"
        assertEquals(value, CredentialValueSanitizer.sanitize(value))
        assertFalse(CredentialValueSanitizer.hasRejectedChars(value))
    }

    @Test
    fun `keeps tab and newline normalizes crlf and keeps lone cr`() {
        // CRLF 归一：OpenSSH 读到 PEM 头/尾行行尾的 \r 会直接报 error in libcrypto
        assertEquals("line1\tX\nline2\n", CredentialValueSanitizer.sanitize("line1\tX\nline2\r\n"))
        assertTrue(CredentialValueSanitizer.hasRejectedChars("a\r\nb"))
        // 单独的 \r 不猜意图：可能是真值内容，原样保留
        assertEquals("a\rb", CredentialValueSanitizer.sanitize("a\rb"))
        assertFalse(CredentialValueSanitizer.hasRejectedChars("a\rb"))
    }

    /**
     * Cf 格式符必须被剔除 —— 这是旧实现的漏网之鱼：它们代码点 ≥ 0x20，
     * 于是"剔除不可见控制符"的承诺只覆盖了 ASCII。零宽字符/BOM 会原样入库，
     * UI 看不出任何异常，而 `ssh-keygen` 读到就报 `error in libcrypto`
     * （等价于"密钥坏了"），从报错信息完全回溯不到"多了个看不见的字符"。
     */
    @Test
    fun `strips invisible unicode format characters`() {
        val cases = listOf(
            "BOM" to "abc\uFEFF",
            "ZWSP" to "ab\u200Bc",
            "ZWJ" to "ab\u200Dc",
            "WORD-JOINER" to "ab\u2060c",
            "SOFT-HYPHEN" to "ab\u00ADc",
            "RL-OVERRIDE" to "ab\u202Ec",
            "LRE" to "ab\u202Ac",
        )
        cases.forEach { (label, value) ->
            assertEquals("$label 应被剔除", "abc", CredentialValueSanitizer.sanitize(value))
            assertTrue("$label 应报告被拒字符", CredentialValueSanitizer.hasRejectedChars(value))
        }
        // 只由这些字符组成 = 等于写入一个空密钥
        assertEquals("", CredentialValueSanitizer.sanitize("\uFEFF\u200B\u2060"))
    }

    @Test
    fun `multi line pem body survives normalization except crlf`() {
        // 多行值逐字符不变的承诺仍然成立（除了 CRLF 行尾这一处有意归一）
        val lf = "-----BEGIN SAMPLE BLOCK-----\nYWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo=\n-----END SAMPLE BLOCK-----\n"
        assertEquals(lf, CredentialValueSanitizer.sanitize(lf))
        val crlf = lf.replace("\n", "\r\n")
        assertEquals(lf, CredentialValueSanitizer.sanitize(crlf))
    }

    @Test
    fun `non ascii printable text is not collaterally stripped`() {
        // 回归护栏：中文/重音/emoji 都不是 Cf，不能因为"剔除不可见字符"被带走
        val value = "口令-パスワード-비밀번호-café-🔑"
        assertEquals(value, CredentialValueSanitizer.sanitize(value))
        assertFalse(CredentialValueSanitizer.hasRejectedChars(value))
    }

    @Test
    fun `strips control characters`() {
        assertEquals("AB", CredentialValueSanitizer.sanitize("A\u0001B"))
        assertEquals("secret", CredentialValueSanitizer.sanitize("\u0000secret\u0008"))
        assertTrue(CredentialValueSanitizer.hasRejectedChars("A\u0001B"))
    }

    @Test
    fun `strips escape sequence and DEL`() {
        // ESC 常被用来伪造终端输出/颜色，DEL(0x7F) 同样不应入库
        assertEquals("abc", CredentialValueSanitizer.sanitize("a\u001bbc"))
        assertEquals("abc", CredentialValueSanitizer.sanitize("abc\u007f"))
    }

    @Test
    fun `multiline block survives byte for byte`() {
        // 多行 + 折行的值（私钥/证书类）必须逐字节保留：换行被吃掉等于悄悄损坏密钥。
        // 这里用不具密钥形态的等价多行文本，避免仓库内出现私钥结构字样。
        val block = """
            -----BEGIN SAMPLE BLOCK-----
            YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo=
            -----END SAMPLE BLOCK-----
        """.trimIndent()
        assertEquals(block, CredentialValueSanitizer.sanitize(block))
    }

    @Test
    fun `value made only of control chars becomes empty`() {
        assertEquals("", CredentialValueSanitizer.sanitize("\u0001\u0002\u0003"))
        assertEquals("", CredentialValueSanitizer.sanitize("\u001b"))
    }

    @Test
    fun `empty stays empty and reports nothing rejected`() {
        assertEquals("", CredentialValueSanitizer.sanitize(""))
        assertFalse(CredentialValueSanitizer.hasRejectedChars(""))
    }

    @Test
    fun `non ascii text is preserved`() {
        // 中文/日文等可打印 Unicode 不是控制符，必须原样保留
        val value = "口令-パスワード-비밀번호"
        assertEquals(value, CredentialValueSanitizer.sanitize(value))
    }
}
