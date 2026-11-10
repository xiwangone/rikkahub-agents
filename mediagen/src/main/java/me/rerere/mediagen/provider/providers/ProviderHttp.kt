package me.rerere.mediagen.provider.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException

internal val mediaGenerationJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal val jsonMediaType = "application/json".toMediaType()

internal fun bearerRequest(url: String, apiKey: String): Request.Builder =
    Request.Builder().url(url).addHeader("Authorization", "Bearer $apiKey")

// Response bodies are JSON metadata, but image results may carry a base64 payload, so
// the cap is generous; it only guards against an unbounded or hostile response filling
// memory. A truncated JSON body would parse as corrupt, so exceeding the cap fails
// loudly instead of silently returning a partial body.
internal const val MAX_JSON_BODY_BYTES = 32 * 1024 * 1024

internal fun boundedBody(response: Response, capBytes: Int): String {
    val charset = response.body.contentType()?.charset() ?: Charsets.UTF_8
    val input = response.body.byteStream()
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
    return String(out.toByteArray(), charset)
}

// Maximum characters of a response body kept in an exception message: the raw body
// can be up to MAX_JSON_BODY_BYTES, and callers log the exception, so the full text
// must never be pasted into the message.
internal const val MAX_ERROR_SNIPPET_CHARS = 1024

internal fun truncateErrorSnippet(text: String): String =
    if (text.length <= MAX_ERROR_SNIPPET_CHARS) text
    else text.take(MAX_ERROR_SNIPPET_CHARS) + "... (truncated, ${text.length} chars total)"

// 响应体在 IO 线程读取，调用方可以直接从主线程协程发起请求
internal suspend fun OkHttpClient.executeJson(request: Request, provider: String): JsonObject =
    withContext(Dispatchers.IO) {
        newCall(request).await().use { response ->
            val text = boundedBody(response, MAX_JSON_BODY_BYTES)
            val body = runCatching { mediaGenerationJson.parseToJsonElement(text).jsonObject }
                .getOrElse { JsonObject(emptyMap()) }
            if (!response.isSuccessful) {
                val error = body.obj("error")
                throw MediaGenerationApiException(
                    provider = provider,
                    statusCode = response.code,
                    code = error?.string("code") ?: body.string("code") ?: error?.string("type"),
                    message = truncateErrorSnippet(
                        error?.string("message") ?: body.string("message") ?: text,
                    ),
                )
            }
            body
        }
    }

internal fun Request.Builder.postJson(body: JsonObject): Request.Builder =
    post(
        mediaGenerationJson.encodeToString(JsonObject.serializer(), body)
            .toRequestBody(jsonMediaType)
    )

internal fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.long(name: String): Long? =
    this[name]?.jsonPrimitive?.longOrNull

internal fun JsonObject.int(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

internal fun JsonObject.double(name: String): Double? =
    this[name]?.jsonPrimitive?.doubleOrNull

internal fun JsonObject.obj(name: String): JsonObject? =
    this[name] as? JsonObject

class MediaGenerationApiException(
    val provider: String,
    val statusCode: Int,
    val code: String?,
    override val message: String,
) : Exception(
    "$provider media generation failed ($statusCode${
        code?.let { ", $it" }.orEmpty()
    }): $message"
)
