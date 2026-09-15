package me.rerere.rikkahub.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「不见值比对」与「名称规范化」的契约测试。
 *
 * 这两者是"粘贴即入库 / 重复判定 / 改名规范化"的共同地基：
 * - 指纹必须**稳定**（同值同指纹）且**不可反推明文**；
 * - 规范化必须**总能产出合法名**（否则入库会直接抛异常）。
 */
class CredentialVaultImportTest {

    @Test
    fun `fingerprint is stable for the same value`() {
        val v = "test-value-abcdefghijklmnop"
        assertEquals(CredentialVaultRepository.fingerprint(v), CredentialVaultRepository.fingerprint(v))
    }

    @Test
    fun `fingerprint differs for different values`() {
        assertNotEquals(
            CredentialVaultRepository.fingerprint("test-value-0000000001"),
            CredentialVaultRepository.fingerprint("test-value-0000000002"),
        )
    }

    @Test
    fun `fingerprint does not leak the plaintext and has fixed width`() {
        val v = "test-value-abcdefghijklmnop"
        val fp = CredentialVaultRepository.fingerprint(v)
        assertEquals(16, fp.length)
        assertFalse(fp.contains("test-value"))
        assertTrue(fp.all { it in "0123456789abcdef" })
    }

    @Test
    fun `normalizeName folds arbitrary input into legal form`() {
        // 用户直接粘贴一段小写/带空格的描述时，必须仍能得到合法名
        assertEquals("OPENAI_API_KEY", CredentialVaultRepository.normalizeName("openai api key"))
        assertEquals("MY_KEY_2", CredentialVaultRepository.normalizeName("  my-key-2  "))
        assertEquals("A_B", CredentialVaultRepository.normalizeName("a.b"))
    }

    @Test
    fun `normalizeName output always matches the vault naming rule`() {
        val samples = listOf("", "   ", "123", "___", "中文名", "a b c", "-x-", " już ".repeat(40))
        samples.forEach { raw ->
            val name = CredentialVaultRepository.normalizeName(raw)
            assertTrue("illegal name from <$raw>: $name", CredentialVaultRepository.validateCredentialName(name))
            assertTrue("too long from <$raw>", name.length <= 64)
        }
    }
}
