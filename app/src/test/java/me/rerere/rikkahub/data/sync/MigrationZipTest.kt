package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 迁移段追加进常规备份 zip：原条目要保留、追加段要能读回、同名条目不能留两份。
 * 纯 JVM 用例（`java.util.zip`），不依赖 Android。
 */
class MigrationZipTest {

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

    private fun readZip(file: File): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(file.inputStream().buffered()).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    out[entry.name] = zipIn.readBytes().decodeToString()
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
        return out
    }

    @Test
    fun `migration entries are appended and original ones survive`() {
        val source = tmp.newFile("backup.zip")
        writeZip(source, mapOf("rikka_hub.db" to "db-bytes", "settings.json" to "{}"))
        val target = File(tmp.root, "migration.zip")

        appendTextEntriesToZip(
            source = source,
            extraEntries =
                mapOf(
                    "${MigrationPackage.DIR}/${MigrationPackage.MANIFEST_NAME}" to """{"version":1}""",
                    "${MigrationPackage.DIR}/${MigrationPackage.CREDENTIALS_NAME}" to "[]",
                ),
            target = target,
        )

        val entries = readZip(target)
        assertEquals(4, entries.size)
        assertEquals("db-bytes", entries["rikka_hub.db"])
        assertEquals("{}", entries["settings.json"])
        assertEquals("""{"version":1}""", entries["${MigrationPackage.DIR}/${MigrationPackage.MANIFEST_NAME}"])
        assertEquals("[]", entries["${MigrationPackage.DIR}/${MigrationPackage.CREDENTIALS_NAME}"])
    }

    @Test
    fun `an appended entry wins over a same-named original instead of duplicating`() {
        val source = tmp.newFile("backup2.zip")
        val manifestName = "${MigrationPackage.DIR}/${MigrationPackage.MANIFEST_NAME}"
        writeZip(source, mapOf(manifestName to "stale", "rikka_hub.db" to "db"))
        val target = File(tmp.root, "migration2.zip")

        appendTextEntriesToZip(source, mapOf(manifestName to "fresh"), target)

        val entries = readZip(target)
        // 同名的旧条目被丢弃 → 总条目数仍是 2（若留了两份，ZipInputStream 会读出 3 次）
        assertEquals(2, entries.size)
        assertEquals("fresh", entries[manifestName])
        assertEquals("db", entries["rikka_hub.db"])
    }
}
