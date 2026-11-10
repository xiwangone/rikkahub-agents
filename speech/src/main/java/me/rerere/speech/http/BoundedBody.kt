package me.rerere.speech.http

import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException

// Single TTS request text is chunked to about 160 chars upstream, so a normal audio
// response is a few hundred KB. The cap only guards against a runaway or hostile
// response filling memory; exceeding it fails loudly instead of OOMing.
internal const val MAX_TTS_AUDIO_BYTES = 16 * 1024 * 1024

// Gemini returns JSON whose base64 audio field inflates the payload by ~4/3, so the
// JSON cap matches the audio cap above.
internal const val MAX_TTS_JSON_BODY_BYTES = 16 * 1024 * 1024

// A MiMo ASR segment holds at most a few minutes of speech, so its JSON response is
// a small transcript object; 1MB leaves ample headroom.
internal const val MAX_ASR_RESPONSE_BYTES = 1 * 1024 * 1024

// Error bodies are diagnostic JSON; 64KB is ample for a message and code.
internal const val MAX_ERROR_BODY_BYTES = 64 * 1024

// Maximum characters of a response body kept in an exception message or log line.
internal const val MAX_ERROR_SNIPPET_CHARS = 1024

/**
 * Read at most [capBytes] from this body, mirroring the chunked-read loop used by
 * mediagen/videogen `boundedBody`: a response larger than the cap throws instead of
 * being buffered whole. Closing the stream releases the connection.
 */
internal fun ResponseBody.readBoundedBytes(capBytes: Int): ByteArray {
    byteStream().use { input ->
        val out = ByteArrayOutputStream(minOf(capBytes, 8 * 1024))
        val buf = ByteArray(8192)
        var total = 0
        while (total < capBytes) {
            val want = minOf(buf.size, capBytes - total)
            val read = input.read(buf, 0, want)
            if (read < 0) break
            out.write(buf, 0, read)
            total += read
        }
        if (input.read() != -1) {
            throw IOException("Response body exceeds $capBytes bytes")
        }
        return out.toByteArray()
    }
}

/** Same bound as [readBoundedBytes], decoded with the declared charset (UTF-8 fallback). */
internal fun ResponseBody.readBoundedString(capBytes: Int): String {
    val charset = contentType()?.charset() ?: Charsets.UTF_8
    return String(readBoundedBytes(capBytes), charset)
}

/**
 * Keep only a short prefix of a response body for exception messages and logs, so a
 * multi-MB error body cannot be amplified into a giant log line. The total length is
 * always reported, so a truncated snippet is distinguishable from a short body.
 */
internal fun String.truncateForError(maxChars: Int = MAX_ERROR_SNIPPET_CHARS): String =
    if (length <= maxChars) this else take(maxChars) + "... (truncated, $length chars total)"
