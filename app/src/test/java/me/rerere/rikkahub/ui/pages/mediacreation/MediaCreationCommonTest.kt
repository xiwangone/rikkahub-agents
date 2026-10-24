package me.rerere.rikkahub.ui.pages.mediacreation

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaCreationCommonTest {
    @Test
    fun sameRequestedNameCreatesDistinctFilesAndPreservesBothContents() {
        val directory = Files.createTempDirectory("media-gallery-target-test").toFile()
        try {
            val first = createUniqueMediaTarget(directory, "RikkaHub_123.jpg")
            val second = createUniqueMediaTarget(directory, "RikkaHub_123.jpg")
            first.writeText("first image")
            second.writeText("second image")

            assertNotEquals(first, second)
            assertEquals("RikkaHub_123.jpg", first.name)
            assertEquals("RikkaHub_123_1.jpg", second.name)
            assertEquals("first image", File(directory, first.name).readText())
            assertEquals("second image", File(directory, second.name).readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
