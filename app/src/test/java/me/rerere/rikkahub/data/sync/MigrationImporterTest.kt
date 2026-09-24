package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导入侧读包：条目名要能列、按名要能读回、缺失要返回 null（而不是抛异常把流程带崩）；
 * 迁移条目到凭证库导入条目的映射要字段对齐（漏字段 = 导过去之后少了信息）。
 */
class MigrationImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream().buffered()).use { zipOut ->
            entries.forEach { (name, content) ->
                zipOut.putNextEntry(ZipEntry(name))
                zipOut.write(content.encodeToByteArray())
                zipOut.closeEntry()
            }
        }
    }

    @Test
    fun `zip entries can be listed and read back by name`() {
        val zip = tmp.newFile("pkg.zip")
        writeZip(
            zip,
            mapOf(
                "${MigrationPackage.DIR}/${MigrationPackage.MANIFEST_NAME}" to """{"version":1}""",
                "rikka_hub.db" to "db-bytes",
            ),
        )

        assertEquals(
            setOf("${MigrationPackage.DIR}/${MigrationPackage.MANIFEST_NAME}", "rikka_hub.db"),
            listZipEntryNames(zip).toSet(),
        )
        assertEquals(
            """{"version":1}""",
            readTextEntry(zip, "${MigrationPackage.DIR}/${MigrationPackage.MANIFEST_NAME}"),
        )
        assertNull(readTextEntry(zip, "${MigrationPackage.DIR}/not-there.json"))
    }

    @Test
    fun `a corrupt archive lists nothing instead of throwing`() {
        val broken = tmp.newFile("broken.zip")
        broken.writeText("this is not a zip")

        assertEquals(emptyList<String>(), listZipEntryNames(broken))
        assertNull(readTextEntry(broken, "anything"))
    }

    @Test
    fun `migration credentials map onto vault import entries field by field`() {
        val parsed =
            MigrationCredential(
                name = "TOKEN_A",
                value = "plain",
                type = "Git",
                group = "Git",
                description = "示例",
                metaJson = """{"endpoint":"https://example.invalid"}""",
                publicKey = "ssh-ed25519 AAAA",
            ).toParsedEntry()

        assertEquals("TOKEN_A", parsed.name)
        assertEquals("plain", parsed.value)
        assertEquals("Git", parsed.type)
        assertEquals("Git", parsed.group)
        assertEquals("示例", parsed.description)
        assertEquals("""{"endpoint":"https://example.invalid"}""", parsed.metaJson)
        assertEquals("ssh-ed25519 AAAA", parsed.publicKey)
    }

    @Test
    fun `absent optional fields become blank strings, not nulls`() {
        val parsed = MigrationCredential(name = "A", value = "v").toParsedEntry()

        assertEquals("", parsed.publicKey)
        assertEquals("", parsed.metaJson)
        assertEquals("", parsed.description)
    }
}
