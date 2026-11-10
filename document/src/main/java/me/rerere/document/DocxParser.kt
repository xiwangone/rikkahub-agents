package me.rerere.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

// Whole-document cap, matching the EPUB total cap: downstream use is a prompt-sized
// extract, not a full dump. Text past the cap is cut and marked instead of silently
// dropped or held in memory without bound.
private const val MAX_TOTAL_CHARS = 5_000_000

private data class ListInfo(
    val level: Int,
    val isNumbered: Boolean,
    val number: Int,
)

private data class ParagraphProperties(
    val listInfo: ListInfo?,
    val headingLevel: Int,
)

object DocxParser {
    fun parse(file: File): String {
        return try {
            // Look the entry up by name: a sequential scan would have to inflate every
            // entry ahead of document.xml first, so a junk entry planted before it could
            // burn unbounded CPU before parsing even starts.
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("word/document.xml")
                    ?: return "Unable to find document content in DOCX file"
                zip.getInputStream(entry).use { parseDocumentXml(it) }
            }
        } catch (e: IOException) {
            "Error parsing DOCX file: ${e.message}"
        }
    }

    /** Append at most up to the document cap. Returns true when the cap was reached. */
    private fun appendBounded(result: StringBuilder, text: String): Boolean {
        if (result.length >= MAX_TOTAL_CHARS) return true
        result.append(text.take(MAX_TOTAL_CHARS - result.length))
        return result.length >= MAX_TOTAL_CHARS
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 有意宽 catch：畸形 DOCX 里单个段落/表格解析失败只丢该块、不能丢整篇；解析器实现抛出的 RuntimeException 类型不可枚举，收窄会漏接。
    private fun parseDocumentXml(inputStream: InputStream): String =
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(inputStream, "UTF-8")

            val result = StringBuilder()
            var inBody = false
            var truncated = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT && !truncated) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "body" -> inBody = true
                            // One malformed paragraph/table must not lose the whole
                            // document: drop just that block and mark it, like EPUB
                            // drops only the failing chapter.
                            "p" -> if (inBody) {
                                val before = result.length
                                try {
                                    processParagraph(parser, result)
                                } catch (e: Exception) {
                                    result.setLength(before)
                                    result.append("[Error: skipped a paragraph that could not be parsed]\n\n")
                                }
                            }
                            "tbl" -> if (inBody) {
                                val before = result.length
                                try {
                                    processTable(parser, result)
                                } catch (e: Exception) {
                                    result.setLength(before)
                                    result.append("[Error: skipped a table that could not be parsed]\n\n")
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name == "body") inBody = false
                    }
                }
                parser.next()
                if (result.length >= MAX_TOTAL_CHARS) {
                    result.append("\n[Truncated: showing first $MAX_TOTAL_CHARS characters]")
                    truncated = true
                }
            }

            result.toString().trim()
        } catch (e: Exception) {
            "Error parsing document XML: ${e.message}"
        }

    private fun processParagraph(
        parser: XmlPullParser,
        result: StringBuilder,
    ) {
        val paragraphStartDepth = parser.depth
        val paragraphContent = StringBuilder()
        var listInfo: ListInfo? = null
        var headingLevel = 0

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "r" -> {
                            extractRunText(parser, paragraphContent)
                        }

                        "pPr" -> {
                            val props = extractParagraphProperties(parser)
                            listInfo = props.listInfo
                            headingLevel = props.headingLevel
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

        val paragraphText = paragraphContent.toString().trim()
        if (paragraphText.isNotBlank()) {
            when {
                listInfo != null -> {
                    val indent = "  ".repeat(listInfo.level)
                    val marker = if (listInfo.isNumbered) "${listInfo.number}. " else "- "
                    appendBounded(result, "$indent$marker$paragraphText\n")
                }

                headingLevel > 0 -> {
                    val headingPrefix = "#".repeat(headingLevel)
                    appendBounded(result, "$headingPrefix $paragraphText\n\n")
                }

                else -> {
                    appendBounded(result, "$paragraphText\n\n")
                }
            }
        }
    }

    private fun extractRunText(
        parser: XmlPullParser,
        result: StringBuilder,
    ) {
        val runStartDepth = parser.depth
        var isBold = false
        var isItalic = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "rPr" -> {
                            val formatting = extractFormatting(parser)
                            isBold = formatting.first
                            isItalic = formatting.second
                        }

                        "t" -> {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                var text = parser.text ?: ""

                                // Apply markdown formatting
                                text =
                                    when {
                                        isBold && isItalic -> "***$text***"
                                        isBold -> "**$text**"
                                        isItalic -> "*$text*"
                                        else -> text
                                    }

                                // A single run must not grow the paragraph buffer past the
                                // document cap; later runs are still consumed so the parser
                                // stays positioned at the end of the run.
                                if (result.length < MAX_TOTAL_CHARS) {
                                    result.append(text.take(MAX_TOTAL_CHARS - result.length))
                                }
                            }
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

    private fun processTable(
        parser: XmlPullParser,
        result: StringBuilder,
    ) {
        val tableStartDepth = parser.depth
        val rows = mutableListOf<List<String>>()
        var tableChars = 0
        var hitTableCap = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tr") {
                        val cells = extractTableRow(parser)
                        if (cells.isNotEmpty()) {
                            rows.add(cells)
                            tableChars += cells.sumOf { it.length }
                            // Cap the buffered table itself: a hostile table must not grow
                            // unboundedly in memory before it is rendered.
                            if (tableChars > MAX_TOTAL_CHARS) {
                                hitTableCap = true
                                break
                            }
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
        if (hitTableCap) {
            // Skip the rest of this table so its remaining cells are not re-read as
            // top-level paragraphs by the caller.
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.END_TAG &&
                    parser.name == "tbl" && parser.depth == tableStartDepth
                ) {
                    break
                }
            }
        }

        // Render into a scratch buffer first, then append only up to the document cap
        // instead of doubling the whole table in the output buffer.
        val tableText = StringBuilder()
        if (rows.isNotEmpty()) {
            val maxCols = rows.maxOfOrNull { it.size } ?: 0

            // Add table rows
            for ((index, row) in rows.withIndex()) {
                tableText.append("| ")
                for (colIndex in 0 until maxCols) {
                    val cellContent = if (colIndex < row.size) row[colIndex] else ""
                    tableText.append("$cellContent | ")
                }
                tableText.append("\n")

                // Add separator after first row (header)
                if (index == 0) {
                    tableText.append("| ")
                    repeat(maxCols) {
                        tableText.append("--- | ")
                    }
                    tableText.append("\n")
                }
            }
        }
        tableText.append("\n")
        appendBounded(result, tableText.toString())
    }

    private fun extractTableRow(parser: XmlPullParser): List<String> {
        val rowStartDepth = parser.depth
        val cells = mutableListOf<String>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tc") {
                        val cellText = extractCellText(parser)
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

    private fun extractCellText(parser: XmlPullParser): String {
        val cellStartDepth = parser.depth
        val result = StringBuilder()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "p") {
                        val paragraphText = extractCellParagraphText(parser)
                        if (paragraphText.isNotBlank()) {
                            if (result.isNotEmpty()) {
                                result.append(" ")
                            }
                            result.append(paragraphText)
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

    private fun extractCellParagraphText(parser: XmlPullParser): String {
        val paragraphStartDepth = parser.depth
        val result = StringBuilder()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "r") {
                        extractRunText(parser, result)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "p" && parser.depth == paragraphStartDepth) {
                        break
                    }
                }
            }
        }

        return result.toString().trim()
    }

    private fun extractParagraphProperties(parser: XmlPullParser): ParagraphProperties {
        val pPrStartDepth = parser.depth
        var listLevel = 0
        var isNumbered = false
        var headingLevel = 0

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "pStyle" -> {
                            val styleVal = parser.getAttributeValue(null, "val")
                            if (styleVal?.startsWith("Heading") == true || styleVal?.startsWith("heading") == true) {
                                headingLevel = styleVal.lastOrNull()?.digitToIntOrNull() ?: 1
                            }
                        }

                        "numPr" -> {
                            val numPrStartDepth = parser.depth
                            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                                when (parser.eventType) {
                                    XmlPullParser.START_TAG -> {
                                        when (parser.name) {
                                            "ilvl" -> {
                                                listLevel =
                                                    parser.getAttributeValue(null, "val")?.toIntOrNull() ?: 0
                                            }

                                            "numId" -> {
                                                isNumbered = parser.getAttributeValue(null, "val") != null
                                            }
                                        }
                                    }

                                    XmlPullParser.END_TAG -> {
                                        if (parser.name == "numPr" && parser.depth == numPrStartDepth) break
                                    }
                                }
                            }
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

        val listInfo =
            if (listLevel > 0 || isNumbered) {
                ListInfo(level = listLevel, isNumbered = isNumbered, number = 1)
            } else {
                null
            }

        return ParagraphProperties(listInfo = listInfo, headingLevel = headingLevel)
    }
}

private fun extractFormatting(parser: XmlPullParser): Pair<Boolean, Boolean> {
    val rPrStartDepth = parser.depth
    var isBold = false
    var isItalic = false

    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                when (parser.name) {
                    "b" -> isBold = true
                    "i" -> isItalic = true
                }
            }

            XmlPullParser.END_TAG -> {
                if (parser.name == "rPr" && parser.depth == rPrStartDepth) {
                    break
                }
            }
        }
    }

    return Pair(isBold, isItalic)
}
