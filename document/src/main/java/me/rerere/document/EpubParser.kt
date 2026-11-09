package me.rerere.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipFile

private data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
)

// A single chapter is capped so a hostile archive entry cannot fill memory, and the
// whole book is capped as well: downstream use is a prompt-sized extract, not a full
// dump. Truncated chapters are marked so the cut is visible instead of silent.
private const val MAX_CHAPTER_CHARS = 1_000_000
private const val MAX_TOTAL_CHARS = 5_000_000

// EPUB chapter markup is frequently loose HTML rather than strict XHTML: it uses named HTML
// entities (&nbsp;, &mdash;, ...) and occasional bare "&" that the 5 XML-predefined entities
// don't cover. With FEATURE_PROCESS_DOCDECL disabled (no DTD lookup), an unresolvable entity
// makes parser.next() throw partway through the document, and the catch below silently drops
// everything from that point on. Disarming any "&" that isn't already valid XML turns those
// into literal text instead of a parser error, so one bad entity can no longer truncate the
// rest of the chapter.
private val UNRESOLVABLE_ENTITY_RE = Regex("&(?!amp;|lt;|gt;|quot;|apos;|#[0-9]+;|#x[0-9a-fA-F]+;)")

internal fun escapeUnresolvableEntities(xml: String): String =
    xml.replace(UNRESOLVABLE_ENTITY_RE, "&amp;")

object EpubParser {
    fun parse(file: File): String {
        return try {
            ZipFile(file).use { zip ->
                val opfPath =
                    findOpfPath(zip)
                        ?: return "Unable to find OPF file in EPUB"
                val opfDir = opfPath.substringBeforeLast('/', "")

                val opfEntry =
                    zip.getEntry(opfPath)
                        ?: return "Unable to read OPF file in EPUB"
                val (manifest, spine) = zip.getInputStream(opfEntry).use { parseOpf(it) }

                val result = StringBuilder()
                for (itemId in spine) {
                    if (result.length >= MAX_TOTAL_CHARS) break
                    val item = manifest[itemId] ?: continue
                    if (!item.mediaType.contains("html")) continue

                    val itemPath = if (opfDir.isEmpty()) item.href else "$opfDir/${item.href}"
                    val entry = zip.getEntry(itemPath) ?: continue
                    val content = zip.getInputStream(entry).use { parseXhtml(it) }
                    if (content.isNotBlank()) {
                        result.append(content)
                        result.append("\n\n")
                    }
                }
                if (result.length > MAX_TOTAL_CHARS) {
                    result.setLength(MAX_TOTAL_CHARS)
                    result.append("\n[Truncated: showing first $MAX_TOTAL_CHARS characters]")
                }

                result.toString().trim().ifEmpty { "No readable content found in EPUB file" }
            }
        } catch (e: Exception) {
            "Error parsing EPUB file: ${e.message}"
        }
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
        return zip.getInputStream(containerEntry).use { stream ->
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(stream, "UTF-8")

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    return@use parser.getAttributeValue(null, "full-path")
                }
                parser.next()
            }
            null
        }
    }

    private fun parseOpf(inputStream: InputStream): Pair<Map<String, ManifestItem>, List<String>> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val manifest = mutableMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                        if (id.isNotEmpty()) {
                            manifest[id] = ManifestItem(id, href, mediaType)
                        }
                    }

                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref") ?: ""
                        if (idref.isNotEmpty()) {
                            spine.add(idref)
                        }
                    }
                }
            }
            parser.next()
        }

        return manifest to spine
    }

    /** Read at most [maxChars] characters; the second result tells whether input was cut. */
    private fun readBoundedText(inputStream: InputStream, maxChars: Int): Pair<String, Boolean> {
        val reader = inputStream.bufferedReader(Charsets.UTF_8)
        val out = StringBuilder(minOf(maxChars, 8192))
        val buf = CharArray(8192)
        var truncated = false
        while (out.length < maxChars) {
            val read = reader.read(buf, 0, minOf(buf.size, maxChars - out.length))
            if (read < 0) break
            out.append(buf, 0, read)
            if (out.length >= maxChars && reader.read() != -1) truncated = true
        }
        return out.toString() to truncated
    }

    private fun parseXhtml(inputStream: InputStream): String =
        try {
            val (rawXml, truncated) = readBoundedText(inputStream, MAX_CHAPTER_CHARS)
            val xml = escapeUnresolvableEntities(rawXml)

            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(StringReader(xml))

            val result = StringBuilder()
            val tagStack = ArrayDeque<String>()
            var inBody = false
            var listCounter = 0

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase()
                        tagStack.addLast(tag)

                        when (tag) {
                            "body" -> {
                                inBody = true
                            }

                            "ol" -> {
                                listCounter = 0
                            }

                            "li" -> {
                                val parentTag = tagStack.dropLast(1).lastOrNull()
                                if (parentTag == "ol") {
                                    listCounter++
                                    result.append("$listCounter. ")
                                } else {
                                    result.append("- ")
                                }
                            }

                            "br" -> {
                                result.append("\n")
                            }

                            "img" -> {
                                if (inBody) {
                                    val alt = parser.getAttributeValue(null, "alt")
                                    if (!alt.isNullOrBlank()) {
                                        result.append("[image: $alt]")
                                    }
                                }
                            }

                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                if (inBody) {
                                    val level = tag[1].digitToInt()
                                    result.append("${"#".repeat(level)} ")
                                }
                            }

                            "strong", "b" -> {
                                if (inBody) result.append("**")
                            }

                            "em", "i" -> {
                                if (inBody) result.append("*")
                            }

                            "hr" -> {
                                if (inBody) result.append("\n---\n")
                            }

                            "blockquote" -> {
                                if (inBody) result.append("> ")
                            }
                        }
                    }

                    XmlPullParser.TEXT -> {
                        if (inBody) {
                            val text =
                                parser.text
                                    ?.replace('\n', ' ')
                                    ?.replace('\r', ' ')
                                    ?.replace("\\s+".toRegex(), " ")
                            if (!text.isNullOrBlank()) {
                                result.append(text)
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.lowercase()
                        if (tagStack.isNotEmpty()) tagStack.removeLast()

                        when (tag) {
                            "body" -> {
                                inBody = false
                            }

                            "p", "div" -> {
                                if (inBody) result.append("\n\n")
                            }

                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                if (inBody) result.append("\n\n")
                            }

                            "li" -> {
                                if (inBody) result.append("\n")
                            }

                            "ul", "ol" -> {
                                if (inBody) result.append("\n")
                            }

                            "br" -> {}

                            "strong", "b" -> {
                                if (inBody) result.append("**")
                            }

                            "em", "i" -> {
                                if (inBody) result.append("*")
                            }

                            "blockquote" -> {
                                if (inBody) result.append("\n")
                            }
                        }
                    }
                }
                try {
                    parser.next()
                } catch (_: Exception) {
                    break
                }
            }

            result
                .toString()
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
                .let { if (truncated) "$it\n[Truncated chapter]" else it }
        } catch (e: Exception) {
            ""
        }
}
