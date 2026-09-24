package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移包结构：①清单能往返；②**拒绝**不合法的包（非法 JSON / 版本过高 / 加密方式不符），
 * 否则会把用户引到「导入后数据全没」的路上；③能区分迁移包与常规备份包。
 */
class MigrationPackageTest {

    @Test
    fun `manifest round-trips with items`() {
        val manifest = MigrationPackage.buildManifest(exportedAt = 123L, items = listOf("DATABASE", "SETTINGS"))
        val parsed = MigrationPackage.parseManifest(MigrationPackage.encodeManifest(manifest))

        assertEquals(MigrationPackage.CURRENT_VERSION, parsed?.version)
        assertEquals(123L, parsed?.exportedAt)
        assertEquals(listOf("DATABASE", "SETTINGS"), parsed?.items)
        assertEquals(MigrationManifest.ENCRYPTION_PASSWORD, parsed?.encryption)
    }

    @Test
    fun `manifest parsing rejects broken or foreign packages`() {
        assertNull("非法 JSON", MigrationPackage.parseManifest("{ not json"))
        assertNull(
            "版本高于本机支持的",
            MigrationPackage.parseManifest("""{"version":99,"encryption":"password"}"""),
        )
        assertNull(
            "加密方式不是口令",
            MigrationPackage.parseManifest("""{"version":1,"encryption":"none"}"""),
        )
    }

    @Test
    fun `credentials round-trip including metadata and public key`() {
        val credentials =
            listOf(
                MigrationCredential(
                    name = "GITHUB_TOKEN",
                    value = "secret-value",
                    type = "Git",
                    group = "Git",
                    description = "示例",
                    metaJson = """{"endpoint":"https://example.invalid"}""",
                ),
                MigrationCredential(name = "SSH_KEY", value = "private", publicKey = "ssh-ed25519 AAAA"),
            )

        val parsed = MigrationPackage.parseCredentials(MigrationPackage.encodeCredentials(credentials))

        assertEquals(2, parsed?.size)
        assertEquals("secret-value", parsed?.get(0)?.value)
        assertEquals("""{"endpoint":"https://example.invalid"}""", parsed?.get(0)?.metaJson)
        assertEquals("ssh-ed25519 AAAA", parsed?.get(1)?.publicKey)
    }

    @Test
    fun `broken credentials payload is reported instead of silently empty`() {
        assertNull(MigrationPackage.parseCredentials("[]x"))
    }

    @Test
    fun `migration packages are distinguishable from regular backups`() {
        assertTrue(
            MigrationPackage.looksLikeMigrationPackage(
                listOf("rikka_hub.db", "migration/MANIFEST.json", "settings.json"),
            ),
        )
        assertTrue(
            "zip 条目可能带前导斜杠",
            MigrationPackage.looksLikeMigrationPackage(listOf("/migration/MANIFEST.json")),
        )
        assertFalse(
            "常规备份包",
            MigrationPackage.looksLikeMigrationPackage(listOf("rikka_hub.db", "settings.json")),
        )
    }
}
