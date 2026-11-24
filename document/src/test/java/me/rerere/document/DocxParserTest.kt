package me.rerere.document

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxParserTest {

    @Test
    fun `missing document xml returns descriptive message`() {
        val tmp = File.createTempFile("empty_docx", ".docx")
        try {
            ZipOutputStream(tmp.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("[Content_Types].xml"))
                zos.write("<Types/>".toByteArray())
                zos.closeEntry()
            }
            val parsed = DocxParser.parse(tmp)
            assertTrue(parsed.contains("Unable to find document content"))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `corrupt archive returns IO error message instead of throwing`() {
        val tmp = File.createTempFile("corrupt_docx", ".docx")
        try {
            tmp.writeText("not a zip archive")
            val parsed = DocxParser.parse(tmp)
            assertTrue(parsed.startsWith("Error parsing DOCX file:"))
        } finally {
            tmp.delete()
        }
    }
}
