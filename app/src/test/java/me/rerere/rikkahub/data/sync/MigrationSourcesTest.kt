package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 凭证 → 迁移条目的映射：字段要一一对应（漏字段 = 换机后凭证残缺），
 * 空白字段要收敛成 null（否则包里塞一堆空串噪音）。
 */
class MigrationSourcesTest {

    @Test
    fun `entity maps onto the migration entry field by field`() {
        val entity =
            VaultCredentialEntity(
                name = "TOKEN_A",
                description = "示例凭证",
                publicKey = "",
                grp = "Git",
                type = "Git",
                metaJson = """{"endpoint":"https://example.invalid"}""",
                valueEncrypted = "cipher-text",
            )

        val entry = toMigrationCredential(entity, plaintextValue = "plain-1")

        assertEquals("TOKEN_A", entry.name)
        assertEquals("plain-1", entry.value)
        assertEquals("Git", entry.type)
        assertEquals("Git", entry.group)
        assertEquals("示例凭证", entry.description)
        assertEquals("""{"endpoint":"https://example.invalid"}""", entry.metaJson)
        assertNull("空白公钥不该进包", entry.publicKey)
    }

    @Test
    fun `blank metadata is dropped and a real public key is kept`() {
        val entity =
            VaultCredentialEntity(
                name = "SSH_KEY",
                metaJson = "",
                publicKey = "ssh-ed25519 AAAA",
                valueEncrypted = "cipher",
            )

        val entry = toMigrationCredential(entity, plaintextValue = "private-key-material")

        assertNull(entry.metaJson)
        assertEquals("ssh-ed25519 AAAA", entry.publicKey)
        assertEquals("private-key-material", entry.value)
    }
}
