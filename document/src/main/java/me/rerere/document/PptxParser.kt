package me.rerere.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

// Deck caps, matching the EPUB/document totals: downstream use is a prompt-sized
// extract, not a full dump. Text past the cap is cut and marked instead of silently
// dropped or held in memory without bound.
private const val MAX_SLIDES = 500
private const val MAX_TOTAL_CHARS = 5_000_000
private const val MAX_SLIDE_CHARS = 1_000_000

private val SLIDE_NAME_RE = Regex("ppt/slides/slide\\d+\\.xml")

object PptxParser {
    @Suppress("TooGenericExceptionCaught") // 有意宽 catch：损坏的 PPTX（zip/解析）抛出的异常类型不可枚举；失败只返回错误串，不能崩。
    fun parse(file: File): String {
        return try {
            ZipFile(file).use { zipFile ->
                // Stream the entry table instead of materialising it: a zip with a
                // huge entry count must not be fully listed before filtering.
                val slideEntries = zipFile
                    .entries()
                    .asSequence()
                    .filter { SLIDE_NAME_RE.matches(it.name) }
                    .mapNotNull { entry ->
                        entry.name
                            .removePrefix("ppt/slides/slide")
                            .removeSuffix(".xml")
                            .toIntOrNull()
                            ?.let { number -> number to entry }
                    }
                    .sortedBy { (number, _) -> number }
                    .toList()

                if (slideEntries.isEmpty()) {
                    return "No slides found in PPTX file"
                }

                // Format incrementally into a single buffer instead of caching every
                // slide and copying the whole deck a second time in formatOutput.
                val result = StringBuilder()
                var parsedSlides = 0
                for ((slideNumber, entry) in slideEntries) {
                    if (parsedSlides >= MAX_SLIDES) {
                        result.append("\n[Truncated: showing first $MAX_SLIDES slides]")
                        break
                    }
                    parsedSlides++
                    val slideContent =
                        zipFile.getInputStream(entry).use { stream ->
                            parseSlideXml(stream)
                        }

                    // Look notes up by the slide's real file number, not by its
                    // position in the sorted list: numbering need not be contiguous,
                    // and an index-based lookup attaches the wrong notes (or none).
                    val notesEntry = zipFile.getEntry("ppt/notesSlides/notesSlide$slideNumber.xml")
                    val notes =
                        if (notesEntry != null) {
                            zipFile.getInputStream(notesEntry).use { stream ->
                                parseNotesXml(stream)
                            }
                        } else {
                            ""
                        }

                    result.append("## Slide $slideNumber\n\n")
                    appendBounded(result, slideContent)
                    if (notes.isNotBlank()) {
                        result.append("\n### Speaker Notes\n\n")
                        appendBounded(result, notes)
                    }
                    result.append("\n")
                    if (result.length >= MAX_TOTAL_CHARS) {
                        result.setLength(MAX_TOTAL_CHARS)
                        result.append("\n[Truncated: showing first $MAX_TOTAL_CHARS characters]")
                        break
                    }
                }

                result.toString().trim()
            }
        } catch (e: Exception) {
            "Error parsing PPTX file: ${e.message}"
        }
    }

    /** Append at most up to the deck cap. */
    private fun appendBounded(result: StringBuilder, text: String) {
        if (result.length >= MAX_TOTAL_CHARS) return
        result.append(text.take(MAX_TOTAL_CHARS - result.length))
    }

    @Suppress("TooGenericExceptionCaught") // 有意宽 catch：畸形幻灯片 XML 解析失败只丢该幻灯片并打标记，不能丢整份演示文稿；解析器异常类型不可枚举。
    private fun parseSlideXml(inputStream: InputStream): String =
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(inputStream, "UTF-8")

            val result = StringBuilder()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "sp" -> processShape(parser, result)

                            // Text box/shape
                            "graphicFrame" -> processGraphicFrame(parser, result) // Table
                        }
                    }
                }
                parser.next()
                if (result.length >= MAX_SLIDE_CHARS) {
                    result.setLength(MAX_SLIDE_CHARS)
                    result.append("\n[Truncated slide]")
                    break
                }
            }

            result.toString()
        } catch (e: Exception) {
            "Error parsing slide XML: ${e.message}\n"
        }

    private fun processShape(
        parser: XmlPullParser,
        result: StringBuilder,
    ) {
        val shapeStartDepth = parser.depth
        val textContent = StringBuilder()
        var hasBullet = false
        var bulletLevel = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "p" -> {
                            // Start of paragraph - check for bullet/numbering
                            val paragraphInfo = processParagraph(parser, textContent)
                            hasBullet = paragraphInfo.first
                            bulletLevel = paragraphInfo.second
                            isNumbered = paragraphInfo.third
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "sp" && parser.depth == shapeStartDepth) {
                        break
                    }
                }
            }
        }

        val text = textContent.toString().trim()
        if (text.isNotBlank()) {
            result.append(text)
            result.append("\n\n")
        }
    }

    private fun processGraphicFrame(
        parser: XmlPullParser,
        result: StringBuilder,
    ) {
        val frameStartDepth = parser.depth

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tbl") {
                        processTable(parser, result)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "graphicFrame" && parser.depth == frameStartDepth) {
                        break
                    }
                }
            }
        }
    }

    private fun processTable(
        parser: XmlPullParser,
        result: StringBuilder,
    ) {
        val tableStartDepth = parser.depth
        val rows = mutableListOf<List<String>>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tr") {
                        val cells = extractTableRow(parser)
                        if (cells.isNotEmpty()) {
                            rows.add(cells)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "tbl" && parser.depth == tableStartDepth) {
                        break
                    }
                }
            }
        }

        // Convert to markdown table
        if (rows.isNotEmpty()) {
            val maxCols = rows.maxOfOrNull { it.size } ?: 0

            for ((index, row) in rows.withIndex()) {
                result.append("| ")
                for (colIndex in 0 until maxCols) {
                    val cellContent = if (colIndex < row.size) row[colIndex] else ""
                    result.append("$cellContent | ")
                }
                result.append("\n")

                // Add separator after first row (header)
                if (index == 0) {
                    result.append("| ")
                    repeat(maxCols) {
                        result.append("--- | ")
                    }
                    result.append("\n")
                }
            }
            result.append("\n")
        }
    }

    private fun extractTableRow(parser: XmlPullParser): List<String> {
        val rowStartDepth = parser.depth
        val cells = mutableListOf<String>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tc") {
                        val cellText = extractTableCell(parser)
                        cells.add(cellText)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "tr" && parser.depth == rowStartDepth) {
                        break
                    }
                }
            }
        }

        return cells
    }

    private fun extractTableCell(parser: XmlPullParser): String {
        val cellStartDepth = parser.depth
        val result = StringBuilder()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        parser.next()
                        if (parser.eventType == XmlPullParser.TEXT) {
                            if (result.isNotEmpty()) {
                                result.append(" ")
                            }
                            result.append(parser.text ?: "")
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "tc" && parser.depth == cellStartDepth) {
                        break
                    }
                }
            }
        }

        return result.toString().trim()
    }

    @Suppress("TooGenericExceptionCaught") // 有意宽 catch：畸形备注 XML 解析失败只返回错误串，不能丢整份演示文稿；解析器异常类型不可枚举。
    private fun parseNotesXml(inputStream: InputStream): String =
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(inputStream, "UTF-8")

            val result = StringBuilder()
            var inNotesShape = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "sp" -> {
                                // isNotesTextShape scans forward looking for <p:ph>, so the
                                // parser is no longer positioned at "sp" by the time it
                                // returns. Capture this shape's own depth first so
                                // extractShapeText's end-of-shape check has the right
                                // boundary instead of the deeper depth of wherever the scan
                                // stopped.
                                val shapeDepth = parser.depth
                                inNotesShape = isNotesTextShape(parser)
                                if (inNotesShape) {
                                    extractShapeText(parser, result, shapeDepth)
                                }
                            }
                        }
                    }
                }
                parser.next()
            }

            result.toString().trim()
        } catch (e: Exception) {
            // A failed notes parse used to vanish silently; surface it like a failed
            // slide parse so the missing notes are visible instead of unexplained.
            "Error parsing notes XML: ${e.message}"
        }

    private fun isNotesTextShape(parser: XmlPullParser): Boolean {
        // Notes text typically has ph type="body"
        val currentDepth = parser.depth
        val originalPosition = parser

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "ph") {
                        val type = parser.getAttributeValue(null, "type")
                        return type == "body"
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.depth <= currentDepth) {
                        return false
                    }
                }
            }
        }
        return false
    }

}

private fun processParagraph(
    parser: XmlPullParser,
    result: StringBuilder,
): Triple<Boolean, Int, Boolean> {
    val paragraphStartDepth = parser.depth
    val paragraphText = StringBuilder()
    var hasBullet = false
    var bulletLevel = 0
    var isNumbered = false

    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                when (parser.name) {
                    "pPr" -> {
                        // Paragraph properties - check for bullets
                        val bulletInfo = extractBulletInfo(parser)
                        hasBullet = bulletInfo.first
                        bulletLevel = bulletInfo.second
                        isNumbered = bulletInfo.third
                    }

                    "r" -> {
                        // Text run
                        extractTextRun(parser, paragraphText)
                    }
                }
            }

            XmlPullParser.END_TAG -> {
                if (parser.name == "p" && parser.depth == paragraphStartDepth) {
                    break
                }
            }
        }
    }

    val text = paragraphText.toString().trim()
    if (text.isNotBlank()) {
        if (hasBullet) {
            val indent = "  ".repeat(bulletLevel)
            val marker = if (isNumbered) "1. " else "- "
            result.append("$indent$marker$text\n")
        } else {
            result.append("$text\n")
        }
    }

    return Triple(hasBullet, bulletLevel, isNumbered)
}

private fun extractBulletInfo(parser: XmlPullParser): Triple<Boolean, Int, Boolean> {
    val pPrStartDepth = parser.depth
    // OOXML's <a:pPr> carries the paragraph's indentation level as its own "lvl"
    // attribute, not as a child element, so it must be read here while the parser is
    // still positioned at the pPr start tag itself (before the loop below advances past
    // it). A previous version looked for a child element named "lvl", which pptx never
    // emits, so the level was always 0.
    var hasBullet = false
    var level = parser.getAttributeValue(null, "lvl")?.toIntOrNull() ?: 0
    var isNumbered = false

    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                when (parser.name) {
                    "buChar" -> {
                        hasBullet = true
                        isNumbered = false
                    }

                    "buAutoNum" -> {
                        hasBullet = true
                        isNumbered = true
                    }
                }
            }

            XmlPullParser.END_TAG -> {
                if (parser.name == "pPr" && parser.depth == pPrStartDepth) {
                    break
                }
            }
        }
    }

    return Triple(hasBullet, level, isNumbered)
}

private fun extractTextRun(
    parser: XmlPullParser,
    result: StringBuilder,
) {
    val runStartDepth = parser.depth

    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                if (parser.name == "t") {
                    parser.next()
                    if (parser.eventType == XmlPullParser.TEXT) {
                        result.append(parser.text ?: "")
                    }
                }
            }

            XmlPullParser.END_TAG -> {
                if (parser.name == "r" && parser.depth == runStartDepth) {
                    break
                }
            }
        }
    }
}

private fun extractShapeText(
    parser: XmlPullParser,
    result: StringBuilder,
    shapeStartDepth: Int,
) {
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                if (parser.name == "t") {
                    parser.next()
                    if (parser.eventType == XmlPullParser.TEXT) {
                        result.append(parser.text ?: "")
                    }
                }
            }

            XmlPullParser.END_TAG -> {
                if (parser.name == "sp" && parser.depth == shapeStartDepth) {
                    break
                }
                if (parser.name == "p") {
                    result.append("\n")
                }
            }
        }
    }
}
