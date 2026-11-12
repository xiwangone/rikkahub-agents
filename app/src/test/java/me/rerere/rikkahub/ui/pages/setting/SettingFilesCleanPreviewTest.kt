package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文件清理预览（[cleanPreview]）：口径必须与 [me.rerere.rikkahub.data.files.FilesManager.deleteOlderThan] /
 * [me.rerere.rikkahub.data.files.FilesManager.deleteAll] 一致，否则预览说的数和实际删的数对不上。
 */
class SettingFilesCleanPreviewTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun file(createdAt: Long, sizeBytes: Long) =
        ManagedFileEntity(
            id = 0,
            folder = "upload",
            relativePath = "f",
            displayName = "f",
            mimeType = "text/plain",
            sizeBytes = sizeBytes,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

    @Test
    fun `ALL counts every file in the folder`() {
        val files = listOf(file(now - day, 100), file(now, 200), file(now - 30 * day, 300))
        assertEquals(CleanPreview(3, 600), cleanPreview(files, CleanRange.ALL, now))
    }

    @Test
    fun `DAYS_7 only counts files strictly older than the cutoff`() {
        val files = listOf(
            file(now - 8 * day, 100), // 8 天前：删
            file(now - 7 * day, 200), // 恰好 7 天前：不删（deleteOlderThan 用 <）
            file(now - 6 * day, 300), // 6 天前：不删
            file(now, 400),
        )
        assertEquals(CleanPreview(1, 100), cleanPreview(files, CleanRange.DAYS_7, now))
    }

    @Test
    fun `DAYS_30 cutoff is 30 days back`() {
        val files = listOf(file(now - 31 * day, 10), file(now - 29 * day, 20))
        assertEquals(CleanPreview(1, 10), cleanPreview(files, CleanRange.DAYS_30, now))
    }

    @Test
    fun `empty folder previews zero`() {
        assertEquals(CleanPreview(0, 0), cleanPreview(emptyList(), CleanRange.ALL, now))
        assertEquals(CleanPreview(0, 0), cleanPreview(emptyList(), CleanRange.DAYS_7, now))
    }

    @Test
    fun `sizes are summed over the matched files only`() {
        val files = listOf(file(now - 10 * day, 1_024), file(now - 10 * day, 2_048), file(now, 4_096))
        assertEquals(CleanPreview(2, 3_072), cleanPreview(files, CleanRange.DAYS_7, now))
    }
}
