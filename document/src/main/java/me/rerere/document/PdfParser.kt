package me.rerere.document

import com.artifex.mupdf.fitz.PDFDocument
import java.io.File

// Each page allocates a native fitz Page and StructuredText that only finalize() releases;
// without an explicit destroy() call these pile up as native memory for as long as this pass
// runs. MAX_PAGES also bounds a pathological or malicious PDF: a page-by-page prompt dump has
// no use past a few hundred pages anyway, since no LLM context window holds that much text.
private const val MAX_PAGES = 300

// Total text cap, matching the EPUB total cap: a single page can hold megabytes of text,
// so a page cap alone does not bound memory. Text past the cap is cut and marked.
private const val MAX_TOTAL_CHARS = 5_000_000

object PdfParser {
    @Suppress("TooGenericExceptionCaught") // 有意宽 catch：MuPDF 原生调用抛出的异常类型不可枚举（损坏/错名文件都走这里）；失败只返回错误串，不能崩。
    fun parserPdf(file: File): String {
        // Open and convert inside the guarded region: openDocument() hands back a native
        // document that asPDF() may reject (e.g. an XPS file with a .pdf name), and the
        // opened document must still be destroyed on that path instead of leaking.
        val opened = try {
            PDFDocument.openDocument(file.absolutePath)
        } catch (e: Exception) {
            return "Error opening PDF file: ${e.message}"
        }
        val document = try {
            opened.asPDF()
        } catch (e: Exception) {
            runCatching { opened.destroy() }
            return "Error opening PDF file: ${e.message}"
        }
        try {
            val pageCount = document.countPages()
            val pagesToRead = minOf(pageCount, MAX_PAGES)
            val result = StringBuilder()
            var truncated = false
            for (i in 0 until pagesToRead) {
                val page = document.loadPage(i)
                try {
                    val structuredText = page.toStructuredText()
                    try {
                        result.append("---")
                        result.append("Page ${i + 1}:\n")
                        val remaining = (MAX_TOTAL_CHARS - result.length).coerceAtLeast(0)
                        result.append(structuredText.asText().take(remaining))
                        result.appendLine()
                    } finally {
                        structuredText.destroy()
                    }
                } finally {
                    page.destroy()
                }
                if (result.length >= MAX_TOTAL_CHARS) {
                    result.setLength(MAX_TOTAL_CHARS)
                    result.append("\n[Truncated: showing first $MAX_TOTAL_CHARS characters]")
                    truncated = true
                    break
                }
            }
            if (!truncated && pagesToRead < pageCount) {
                result.append("\n[Truncated: showing $pagesToRead of $pageCount pages]")
            }
            return result.toString()
        } finally {
            document.destroy()
        }
    }
}
