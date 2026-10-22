package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.atomic.AtomicLong

/**
 * XML tool_call / tool_use text fallback parser (OpenAI-compatible providers only).
 *
 * Background: some OpenAI-compatible endpoints (third-party relays/aggregators,
 * especially small models on them) do not follow the structured tool_calls
 * protocol and return tool calls as XML text mixed into content. Two dialects
 * observed in practice:
 *  - agnes-3.0-flash at apihub.agnes-ai.com: tool_call tags wrapping function
 *    tags / bare JSON arguments;
 *  - similar small models (Anthropic-trained): tool_use tags wrapping a
 *    tool_name child plus parameter args, with malformed spacing/newlines.
 *    Tag-name strings are always assembled by concatenation so this source
 *    file itself never contains a matchable full tag.
 *
 * Without this fallback such text is only rendered as a normal reply - the tool
 * never executes, and the user-visible symptom is "the model only replies with
 * one sentence / a workspace was assigned but nothing was produced".
 *
 * Design aligned with ToolTagParser inside AICoreProvider:
 * - complete closed block -> UIMessagePart.Tool (enters the GenerationLoop
 *   tool execution loop);
 * - unparsable/unclosed block -> kept as-is as text, the user always sees the
 *   raw model output, nothing is silently swallowed.
 *
 * Streaming: SSE splits one tool_call across multiple deltas. The caller caches
 * Result.remainder, concatenates it with the next delta and calls split again;
 * at stream end the remaining remainder is flushed as Text.
 */
// 解析器：单文件内集中了标签/属性/增量缓冲的十几个小工具函数，拆分只会把同一状态机割裂成多段。
@Suppress("TooManyFunctions")
internal object XmlToolCallParser {
    /** Backward-compatible single open tag (decoder fast-path check). */
    const val OPEN_TAG = "tool_" + "call"

    /** All supported open tags. */
    val OPEN_TAGS: List<String> = listOf("tool_" + "call", "tool_" + "use")

    private val idSeq = AtomicLong(0)

    data class Result(
        val parts: List<UIMessagePart>,
        val remainder: String,
    )

    fun split(content: String): Result {
        val out = mutableListOf<UIMessagePart>()
        var rest = content
        while (true) {
            // Find the earliest occurrence of any open tag (tolerates a space after "<")
            val hit = OPEN_TAGS.mapNotNull { tag ->
                ("<\\s*" + tag + "(?=[\\s>])").toRegex().find(rest)?.let { tag to it }
            }.minByOrNull { it.second.range.first }
            if (hit == null) {
                // The tail may be an open-tag prefix cut by a delta -> keep as remainder
                val keep = minOf(rest.length, OPEN_TAGS.maxOf { it.length } - 1)
                val safeEnd = rest.length - keep
                if (safeEnd > 0) out += UIMessagePart.Text(rest.substring(0, safeEnd))
                return Result(out, rest.substring(safeEnd))
            }
            val (tag, openMatch) = hit
            if (openMatch.range.first > 0) out += UIMessagePart.Text(rest.substring(0, openMatch.range.first))
            val afterOpen = rest.substring(openMatch.range.last + 1)
            // Close tag tolerates extra spaces and newlines between "<", "/" and the tag name
            val closeMatch = ("<\\s*/\\s*" + tag + "\\s*>").toRegex().find(afterOpen)
            if (closeMatch == null) {
                // Unclosed: keep everything from the open tag onward (incl. the open tag text)
                return Result(out, rest.substring(openMatch.range.first))
            }
            val body = afterOpen.substring(0, closeMatch.range.first)
            rest = afterOpen.substring(closeMatch.range.last + 1)
            out += parseBody(body, tag)
                ?: UIMessagePart.Text(openMatch.value + body + closeMatch.value)
        }
    }

    private fun parseBody(body: String, tag: String): UIMessagePart.Tool? = when (tag) {
        "tool_" + "use" -> parseToolUseBody(body)
        else -> parseToolCallBody(body)
    }

    private fun parseToolCallBody(body: String): UIMessagePart.Tool? {
        val trimmed = body.trim()
        return when {
            trimmed.startsWith("{") -> parseJsonStyle(trimmed)
            trimmed.contains("<function=") -> parseAnthropicStyle(trimmed)
            else -> null
        }
    }

    /**
     * tool_use dialect: tool_name text child + Anthropic-style
     * parameter tags, or a bare JSON argument block. Small-model output is
     * often malformed (a parameter tag written with "=" instead of name="",
     * newline inside the close tag), so everything uses lenient regexes plus
     * a JSON fallback.
     */
    private fun parseToolUseBody(body: String): UIMessagePart.Tool? {
        val nameRe = ("<\\s*tool_name\\s*>\\s*([A-Za-z0-9_.\\-]+)\\s*<\\s*/\\s*tool_name\\s*>")
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val name = nameRe.find(body)?.groupValues?.get(1)
            // Fallback: body itself is JSON with a name field (same shape as the
            // tool_call JSON dialect)
            ?: body.trim().takeIf { it.startsWith("{") }
                ?.let { runCatching { Json.parseToJsonElement(it).jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
            ?: return null
        val paramRe = (
            "<\\s*parameter\\s+name\\s*=\\s*\"([A-Za-z0-9_.\\-]+)\"\\s*>" +
                "(.*?)<\\s*/\\s*parameter\\s*>"
            ).toRegex(RegexOption.DOT_MATCHES_ALL)
        val matches = paramRe.findAll(body).toList()
        val input = if (matches.isNotEmpty()) {
            buildJsonObject {
                matches.forEach { m ->
                    val key = m.groupValues[1]
                    val raw = m.groupValues[2].trim()
                    val structured = raw
                        .takeIf { it.startsWith("{") || it.startsWith("[") }
                        ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                    put(key, structured ?: JsonPrimitive(raw))
                }
            }.toString()
        } else {
            // Fallback: first JSON object in the body is the input (malformed
            // "parameter= {json}" shape)
            val jsonText = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(body)?.value
            jsonText?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }?.toString()
                ?: "{}"
        }
        return UIMessagePart.Tool(
            toolCallId = nextToolCallId(),
            toolName = name,
            input = input,
            output = emptyList(),
        )
    }

    private fun parseJsonStyle(body: String): UIMessagePart.Tool? {
        val obj = parseJsonObject(body) ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        return UIMessagePart.Tool(
            toolCallId = nextToolCallId(),
            toolName = name,
            input = coerceInput(obj["input"]),
            output = emptyList(),
        )
    }

    private fun parseAnthropicStyle(body: String): UIMessagePart.Tool? {
        val fnNameRe = ("<" + "function" + "\\s*=\\s*([A-Za-z0-9_.\\-]+)").toRegex()
        val fnMatch = fnNameRe.find(body) ?: return null
        val name = fnMatch.groupValues[1]
        val paramRe = ("<" + "parameter" + "\\s*=\\s*([A-Za-z0-9_.\\-]+)\\s*>(.*?)<" + "/parameter>")
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val args = buildJsonObject {
            paramRe.findAll(body).forEach { m ->
                val key = m.groupValues[1]
                val raw = m.groupValues[2].trim()
                val structured = raw
                    .takeIf { it.startsWith("{") || it.startsWith("[") }
                    ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                put(key, structured ?: JsonPrimitive(raw))
            }
        }
        return UIMessagePart.Tool(
            toolCallId = nextToolCallId(),
            toolName = name,
            input = args.toString(),
            output = emptyList(),
        )
    }

    private fun coerceInput(input: JsonElement?): String = when (input) {
        null, is JsonNull -> "{}"
        is JsonObject -> input.toString()
        is JsonPrimitive -> {
            val content = input.contentOrNull
            if (content != null && content.trimStart().let { it.startsWith("{") || it.startsWith("[") }) {
                runCatching { Json.parseToJsonElement(content).toString() }
                    .getOrNull() ?: wrapString(content)
            } else {
                wrapString(content ?: "")
            }
        }
        else -> input.toString()
    }

    private fun wrapString(value: String): String = buildJsonObject {
        put("input", JsonPrimitive(value))
    }.toString()

    private fun parseJsonObject(body: String): JsonObject? {
        parseLenient(body)?.let { return it }
        val repaired = body.replace(Regex("""\}\s*>\s*$"""), "}}")
        return parseLenient(repaired)
    }

    private fun parseLenient(body: String): JsonObject? = runCatching {
        val text = body
            .replace(Regex(""",\s*\}"""), "}")
            .replace(Regex(""",\s*\]"""), "]")
        Json.parseToJsonElement(text).jsonObject
    }.getOrNull()

    private fun nextToolCallId(): String =
        "xml-tool-${idSeq.incrementAndGet()}-${System.nanoTime()}"
}
