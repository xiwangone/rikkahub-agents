package me.rerere.rikkahub.data.sync

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreFileTransactionTest {
    @Test
    fun badDatabaseValidationRollsBackDatabaseSettingsAndAttachmentTogether() {
        val fixture = Files.createTempDirectory("restore-db-invalid").toFile()
        try {
            val root = File(fixture, "app").apply { mkdirs() }
            val transaction = File(fixture, "installing")
            val database = File(fixture, "db/rikka_hub.db").apply { parentFile!!.mkdirs(); writeText("old-db") }
            val preferences = File(root, "datastore/settings.preferences_pb").apply { parentFile!!.mkdirs(); writeText("old-prefs") }
            val attachment = File(root, "upload/a.bin").apply { parentFile!!.mkdirs(); writeText("old-file") }
            val stagedDb = File(fixture, "staged/rikka_hub.db").apply { parentFile!!.mkdirs(); writeText("bad-db") }
            val stagedPrefs = File(fixture, "staged/settings.preferences_pb").apply { writeText("new-prefs") }
            val stagedAttachment = File(fixture, "staged/a.bin").apply { writeText("new-file") }
            val targets = listOf(database, preferences, attachment)
            assertThrows(IllegalStateException::class.java) {
                RestoreFileTransaction.install(
                    transaction, targets,
                    listOf(stagedDb to database, stagedPrefs to preferences, stagedAttachment to attachment),
                    identifyTarget = { it.relativeTo(fixture).invariantSeparatorsPath },
                    resolveTarget = { File(fixture, it) },
                    validateInstalledState = { error("synthetic bad SQLite quick_check") },
                )
            }
            assertEquals("old-db", database.readText())
            assertEquals("old-prefs", preferences.readText())
            assertEquals("old-file", attachment.readText())
            assertFalse(transaction.exists())
        } finally { fixture.deleteRecursively() }
    }

    @Test
    fun injectedMidInstallFailureRestoresEveryOriginalTarget() {
        val fixture = Files.createTempDirectory("restore-fault").toFile()
        try {
            val one = File(fixture, "files/upload/one").apply { parentFile!!.mkdirs(); writeText("old-one") }
            val two = File(fixture, "files/avatars/two").apply { parentFile!!.mkdirs(); writeText("old-two") }
            val stagedOne = File(fixture, "stage/one").apply { parentFile!!.mkdirs(); writeText("new-one") }
            val stagedTwo = File(fixture, "stage/two").apply { writeText("new-two") }
            val transaction = File(fixture, "installing")
            assertThrows(IllegalStateException::class.java) {
                RestoreFileTransaction.install(
                    transaction, listOf(one, two), listOf(stagedOne to one, stagedTwo to two),
                    identifyTarget = { it.relativeTo(fixture).invariantSeparatorsPath },
                    resolveTarget = { File(fixture, it) },
                    validateInstalledState = {},
                    failAfterOperations = 3,
                )
            }
            assertEquals("old-one", one.readText())
            assertEquals("old-two", two.readText())
            assertFalse(transaction.exists())
        } finally { fixture.deleteRecursively() }
    }

    @Test
    fun rejectsSymlinkedArchiveTreesAndRestoreTargets() {
        val fixture = Files.createTempDirectory("restore-symlink").toFile()
        try {
            val root = File(fixture, "files").apply { mkdirs() }
            val outside = File(fixture, "outside").apply { mkdirs() }
            val marker = File(outside, "marker").apply { writeText("untouched") }
            Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
            assertFalse(RestoreFileTransaction.isSafeTreeEntry(root, File(root, "escape")))
            assertFalse(RestoreFileTransaction.isSafeTreeEntry(root, File(root, "escape/marker")))
            assertThrows(IllegalStateException::class.java) {
                RestoreFileTransaction.resolveUnderRoot(root, "escape/marker")
            }
            assertEquals("untouched", marker.readText())
        } finally { fixture.deleteRecursively() }
    }

    @Test
    fun rejectsTraversalAndCanonicalPrefixConfusionWithoutChangingExistingState() {
        val fixture = Files.createTempDirectory("restore-path").toFile()
        try {
            val root = File(fixture, "files").apply { mkdirs() }
            val marker = File(fixture, "files-escape/marker").apply { parentFile!!.mkdirs(); writeText("untouched") }
            assertThrows(IllegalArgumentException::class.java) {
                PendingDatabaseRestore.safeArchivePath("skills/../../files-escape/marker")
            }
            assertThrows(IllegalStateException::class.java) {
                RestoreFileTransaction.resolveUnderRoot(root, "../files-escape/marker")
            }
            assertThrows(IllegalStateException::class.java) {
                RestoreFileTransaction.resolveUnderRoot(root, "../files-escape")
            }
            assertEquals("untouched", marker.readText())
        } finally { fixture.deleteRecursively() }
    }
}
