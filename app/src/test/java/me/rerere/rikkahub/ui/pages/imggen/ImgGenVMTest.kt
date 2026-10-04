package me.rerere.rikkahub.ui.pages.imggen

import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Coverage for [selectOrphanedGenMedia], the pure selection logic backing the gallery
 * orphan purge (#39). The VM itself needs a real Android Context to construct, so this
 * pins the extracted function directly, same pattern as the doctor-check pure functions.
 */
class ImgGenVMTest {

    private lateinit var imagesDir: File

    @Before fun setUp() {
        imagesDir = Files.createTempDirectory("imggen-orphan-test").toFile()
    }

    @After fun tearDown() {
        imagesDir.deleteRecursively()
    }

    private fun entity(id: Int, fileName: String) = GenMediaEntity(
        id = id,
        path = "images/$fileName",
        modelId = "test-model",
        prompt = "prompt",
        createAt = 0L,
    )

    @Test fun `no entities returns no orphans`() {
        val orphans = selectOrphanedGenMedia(emptyList(), imagesDir)
        assertEquals(emptyList<GenMediaEntity>(), orphans)
    }

    @Test fun `entity whose file exists is not an orphan`() {
        File(imagesDir, "present.png").writeBytes(byteArrayOf(1))
        val present = entity(1, "present.png")

        val orphans = selectOrphanedGenMedia(listOf(present), imagesDir)

        assertEquals(emptyList<GenMediaEntity>(), orphans)
    }

    @Test fun `entity whose file is missing is an orphan`() {
        val missing = entity(1, "missing.png")

        val orphans = selectOrphanedGenMedia(listOf(missing), imagesDir)

        assertEquals(listOf(missing), orphans)
    }

    @Test fun `mixed present and missing entities returns exactly the missing ones`() {
        File(imagesDir, "present-a.png").writeBytes(byteArrayOf(1))
        File(imagesDir, "present-b.png").writeBytes(byteArrayOf(2))
        val presentA = entity(1, "present-a.png")
        val presentB = entity(2, "present-b.png")
        val missingA = entity(3, "missing-a.png")
        val missingB = entity(4, "missing-b.png")

        val orphans = selectOrphanedGenMedia(
            listOf(presentA, missingA, presentB, missingB),
            imagesDir,
        )

        assertEquals(listOf(missingA, missingB), orphans)
    }

    @Test fun `legacy record with slash model id resolves file in subdirectory`() {
        // 旧版本把含 "/" 的模型名直接拼进文件名，图片实际落在子目录里，而记录只存了最后一段
        val createAt = 1234567890L
        val modelId = "google/gemini-2.5-flash-image-preview"
        val subDir = File(imagesDir, "${createAt}_${modelId.substringBeforeLast('/')}")
        subDir.mkdirs()
        File(subDir, "gemini-2.5-flash-image-preview_0.png").writeBytes(byteArrayOf(1))
        val legacy = GenMediaEntity(
            id = 1,
            path = "images/gemini-2.5-flash-image-preview_0.png",
            modelId = modelId,
            prompt = "prompt",
            createAt = createAt,
        )

        val orphans = selectOrphanedGenMedia(listOf(legacy), imagesDir)

        assertEquals(emptyList<GenMediaEntity>(), orphans)
    }

    @Test fun `legacy record with slash model id but truly missing file is an orphan`() {
        val legacy = GenMediaEntity(
            id = 1,
            path = "images/gemini-2.5-flash-image-preview_0.png",
            modelId = "google/gemini-2.5-flash-image-preview",
            prompt = "prompt",
            createAt = 1234567890L,
        )

        val orphans = selectOrphanedGenMedia(listOf(legacy), imagesDir)

        assertEquals(listOf(legacy), orphans)
    }
}
