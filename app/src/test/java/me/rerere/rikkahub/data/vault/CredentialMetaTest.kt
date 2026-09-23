package me.rerere.rikkahub.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 明文元数据的白名单契约。
 *
 * 这是"秘密绝不进明文列"的**强制点**：白名单外的键必须被丢弃（包括 apiKey 这种
 * 名字里就带 key 的字段），而不是靠调用方自觉。
 */
class CredentialMetaTest {

    @Test
    fun `secret-looking keys are dropped by the whitelist`() {
        val encoded = CredentialMeta.encode(
            mapOf(
                "endpoint" to "https://api.example.com/v1",
                "apiKey" to "sk-should-not-appear",
                "token" to "t",
            ),
        )
        assertTrue(encoded.contains("endpoint"))
        assertFalse("apiKey 绝不能进明文", encoded.contains("sk-should-not-appear"))
        assertFalse(encoded.contains("\"token\""))
    }

    @Test
    fun `blank values and empty map produce empty string`() {
        assertEquals("", CredentialMeta.encode(emptyMap()))
        assertEquals("", CredentialMeta.encode(mapOf("endpoint" to "   ")))
    }

    @Test
    fun `round trip keeps whitelisted values`() {
        val meta = mapOf("endpoint" to "https://x/v1", "header" to "Authorization", "prefix" to "Bearer ")
        assertEquals(meta, CredentialMeta.decode(CredentialMeta.encode(meta)))
    }

    @Test
    fun `custom prefixed keys round trip and non-whitelist still dropped`() {
        val meta = mapOf(
            "custom.备用邮箱" to "a@b.c",
            "custom.recovery-hint" to "see paper note",
            "apiKey" to "sk-nope",
        )
        val decoded = CredentialMeta.decode(CredentialMeta.encode(meta))
        assertEquals("a@b.c", decoded["custom.备用邮箱"])
        assertEquals("see paper note", decoded["custom.recovery-hint"])
        assertFalse("白名单外的键仍必须丢", decoded.containsKey("apiKey"))
    }

    @Test
    fun `custom keys are not reported as rejected`() {
        val rejected = CredentialMeta.rejectedKeys(
            mapOf("custom.x" to "1", "endpoint" to "e", "apiKey" to "k"),
        )
        assertEquals(setOf("apiKey"), rejected)
    }

    @Test
    fun `decode tolerates blank and malformed json`() {
        assertEquals(emptyMap<String, String>(), CredentialMeta.decode(""))
        assertEquals(emptyMap<String, String>(), CredentialMeta.decode("   "))
        assertEquals(emptyMap<String, String>(), CredentialMeta.decode("not-json"))
        assertEquals(emptyMap<String, String>(), CredentialMeta.decode("[1,2,3]"))
    }

    @Test
    fun `decode drops non whitelisted keys from stored json`() {
        val raw = """{"endpoint":"https://x","apiKey":"sk-leak"}"""
        assertEquals(mapOf("endpoint" to "https://x"), CredentialMeta.decode(raw))
    }

    @Test
    fun `rejectedKeys reports what would not be stored`() {
        assertEquals(
            setOf("apiKey"),
            CredentialMeta.rejectedKeys(mapOf("endpoint" to "x", "apiKey" to "y")),
        )
    }
}
