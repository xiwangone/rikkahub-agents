package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文件类型归类（[fileTypeCategory]）：非图片文件不再全显示图片图标，
 * 按 MIME 归类——图片/视频/音频/文档/压缩包/文本/其他。
 */
class SettingFilesPageTypeTest {

    @Test
    fun `media majors map to their category`() {
        assertEquals(FileTypeCategory.IMAGE, fileTypeCategory("image/png"))
        assertEquals(FileTypeCategory.IMAGE, fileTypeCategory("image/svg+xml"))
        assertEquals(FileTypeCategory.VIDEO, fileTypeCategory("video/mp4"))
        assertEquals(FileTypeCategory.AUDIO, fileTypeCategory("audio/mpeg"))
        assertEquals(FileTypeCategory.TEXT, fileTypeCategory("text/plain"))
    }

    @Test
    fun `documents map to document`() {
        assertEquals(FileTypeCategory.DOCUMENT, fileTypeCategory("application/pdf"))
        assertEquals(FileTypeCategory.DOCUMENT, fileTypeCategory("application/msword"))
        assertEquals(FileTypeCategory.DOCUMENT, fileTypeCategory("application/rtf"))
        assertEquals(FileTypeCategory.DOCUMENT, fileTypeCategory("application/epub+zip"))
        assertEquals(
            FileTypeCategory.DOCUMENT,
            fileTypeCategory("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        )
        assertEquals(
            FileTypeCategory.DOCUMENT,
            fileTypeCategory("application/vnd.oasis.opendocument.text"),
        )
    }

    @Test
    fun `archives map to archive`() {
        assertEquals(FileTypeCategory.ARCHIVE, fileTypeCategory("application/zip"))
        assertEquals(FileTypeCategory.ARCHIVE, fileTypeCategory("application/x-tar"))
        assertEquals(FileTypeCategory.ARCHIVE, fileTypeCategory("application/gzip"))
        assertEquals(FileTypeCategory.ARCHIVE, fileTypeCategory("application/x-gzip"))
        assertEquals(FileTypeCategory.ARCHIVE, fileTypeCategory("application/x-7z-compressed"))
        assertEquals(FileTypeCategory.ARCHIVE, fileTypeCategory("application/x-rar-compressed"))
        assertEquals(FileTypeCategory.ARCHIVE, fileTypeCategory("application/x-xz"))
    }

    @Test
    fun `unknown and empty fall back to other`() {
        assertEquals(FileTypeCategory.OTHER, fileTypeCategory("application/octet-stream"))
        assertEquals(FileTypeCategory.OTHER, fileTypeCategory("application/json"))
        assertEquals(FileTypeCategory.OTHER, fileTypeCategory(""))
        assertEquals(FileTypeCategory.OTHER, fileTypeCategory("garbage"))
    }

    @Test
    fun `matching is case-insensitive and trims whitespace`() {
        assertEquals(FileTypeCategory.IMAGE, fileTypeCategory("IMAGE/PNG"))
        assertEquals(FileTypeCategory.DOCUMENT, fileTypeCategory(" application/pdf "))
    }
}
