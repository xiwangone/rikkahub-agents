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
    fun `keeps tab newline and carriage return`() {
        val value = "line1\tX\nline2\r\n"
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
