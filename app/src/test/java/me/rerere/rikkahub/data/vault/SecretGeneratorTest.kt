package me.rerere.rikkahub.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretGeneratorTest {

    @Test
    fun `token is url-safe base64 without padding`() {
        val token = SecretGenerator.token()
        // 32 字节 → ceil(256/6) = 43 个字符，无 '=' 填充
        assertEquals(43, token.length)
        assertTrue(token.none { it == '=' })
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `token length follows the requested byte size`() {
        assertEquals(22, SecretGenerator.token(16).length) // 16 字节 → 128 bit → 22 字符
        assertEquals(86, SecretGenerator.token(64).length)
    }

    @Test
    fun `password always covers every character class`() {
        repeat(25) {
            val password = SecretGenerator.password()
            assertEquals(20, password.length)
            assertTrue(password.any { it.isUpperCase() })
            assertTrue(password.any { it.isLowerCase() })
            assertTrue(password.any { it.isDigit() })
            assertTrue(password.any { !it.isLetterOrDigit() })
        }
    }

    @Test
    fun `password avoids look-alike characters`() {
        val password = SecretGenerator.password(200)
        listOf('0', 'O', 'o', '1', 'l', 'I').forEach { lookAlike ->
            assertTrue("unexpected look-alike char $lookAlike", lookAlike !in password)
        }
    }

    @Test
    fun `each call returns fresh material`() {
        assertNotEquals(SecretGenerator.token(), SecretGenerator.token())
        assertNotEquals(SecretGenerator.password(), SecretGenerator.password())
    }

    @Test
    fun `too-short requests are rejected`() {
        assertTrue(runCatching { SecretGenerator.token(8) }.isFailure)
        assertTrue(runCatching { SecretGenerator.password(4) }.isFailure)
    }
}
