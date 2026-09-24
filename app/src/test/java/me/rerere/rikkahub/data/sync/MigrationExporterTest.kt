package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 迁移段组装：三个段的**名字**必须是导入侧认识的那几个（写错 = 导入时找不到段），
 * 且 provider 段缺失时**不发空段** —— 宁可少一段，也别让导入侧以为「这台机器没有 provider 配置」。
 */
class MigrationExporterTest {

    private fun entryNames() =
        mapOf(
            "manifest" to "${MigrationPackage.DIR}/${MigrationPackage.MANIFEST_NAME}",
            "credentials" to "${MigrationPackage.DIR}/${MigrationPackage.CREDENTIALS_NAME}",
            "providers" to "${MigrationPackage.DIR}/${MigrationPackage.PROVIDERS_NAME}",
        )

    @Test
    fun `entries carry manifest, credentials and providers`() {
        val names = entryNames()
        val entries =
            migrationEntriesOf(
                exportedAt = 42L,
                items = listOf("DATABASE", "SETTINGS"),
                credentials = listOf(MigrationCredential(name = "TOKEN_A", value = "plain")),
                providersJson = """[{"type":"openai"}]""",
            )

        assertEquals(3, entries.size)
        val manifest = MigrationPackage.parseManifest(entries.getValue(names.getValue("manifest")))
        assertEquals(42L, manifest?.exportedAt)
        assertEquals(listOf("DATABASE", "SETTINGS"), manifest?.items)
        assertEquals(
            1,
            MigrationPackage.parseCredentials(entries.getValue(names.getValue("credentials")))?.size,
        )
        assertEquals("""[{"type":"openai"}]""", entries[names.getValue("providers")])
    }

    @Test
    fun `a blank providers payload omits the segment instead of shipping empty json`() {
        val names = entryNames()
        val entries =
            migrationEntriesOf(
                exportedAt = 1L,
                items = listOf("DATABASE"),
                credentials = emptyList(),
                providersJson = "   ",
            )

        assertEquals(2, entries.size)
        assertFalse(entries.containsKey(names.getValue("providers")))
    }
}
