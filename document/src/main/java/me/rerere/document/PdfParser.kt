package me.rerere.document

import me.rerere.document.pdf.PdfFile
import me.rerere.document.pdf.PdfTextExtractor
import java.io.File

// Keep prompt extraction bounded: pages beyond this limit are unlikely to fit
// in a model context window and could make a malformed PDF unnecessarily costly.
private const val MAX_PAGES = 300

object PdfParser {
    fun parserPdf(file: File): String {
        val pdf = PdfFile(file.readBytes())
        val extractor = PdfTextExtractor(pdf)
        val pages = pdf.pages()
        if (pages.isEmpty()) throw IllegalStateException("No pages found in PDF")

        val pagesToRead = minOf(pages.size, MAX_PAGES)
        val result = StringBuilder()
        for (i in 0 until pagesToRead) {
            result.append("---")
            result.append("Page ${i + 1}:\n")
            val text = try {
                extractor.extract(pages[i])
            } catch (e: Exception) {
                "[Failed to extract text from this page: ${e.message}]"
            }
            result.append(text)
            result.appendLine()
        }

        if (pagesToRead < pages.size) {
            result.append("\n[Truncated: showing $pagesToRead of ${pages.size} pages]")
        }
        return result.toString()
    }
}
