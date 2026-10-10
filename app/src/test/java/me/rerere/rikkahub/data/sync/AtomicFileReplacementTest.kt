package me.rerere.rikkahub.data.sync

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicFileReplacementTest {
    @Test
    fun `failed replacement restores original target`() {
        val dir = Files.createTempDirectory("atomic-replace-test").toFile()
        try {
            val target = File(dir, "database.db").apply { writeText("original") }
            val temp = File(dir, "database.db.restore-temp").apply { writeText("replacement") }
            var targetInstallAttempts = 0
            val rename: (File, File) -> Boolean = { source, destination ->
                if (source == temp && destination == target) {
                    targetInstallAttempts++
                    false
                } else {
                    source.renameTo(destination)
                }
            }

            try {
                replaceFilePreservingTarget(temp, target, rename)
                throw AssertionError("Expected failed replacement")
            } catch (_: IOException) {
                assertEquals("original", target.readText())
                assertTrue(temp.exists())
            }
            assertEquals(2, targetInstallAttempts)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `successful replacement removes recovery copy`() {
        val dir = Files.createTempDirectory("atomic-replace-test").toFile()
        try {
            val target = File(dir, "database.db").apply { writeText("original") }
            val temp = File(dir, "database.db.restore-temp").apply { writeText("replacement") }
            replaceFilePreservingTarget(temp, target)
            assertEquals("replacement", target.readText())
            assertFalse(temp.exists())
            assertEquals(listOf("database.db"), dir.listFiles()?.map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }
}
