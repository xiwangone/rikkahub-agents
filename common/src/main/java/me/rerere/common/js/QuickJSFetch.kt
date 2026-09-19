package me.rerere.common.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

private val json = Json { ignoreUnknownKeys = true }

// A custom search/scrape script's fetch() can hit any endpoint the user configures (including
// a self-hosted one), so this bounds only the failure modes that are never a legitimate
// response shape: a call that never finishes, and a body that never ends.
private const val FETCH_CALL_TIMEOUT_SECONDS = 30L
private const val FETCH_BODY_CAP_BYTES = 256 * 1024

@Serializable
private data class HttpResponseDto(
    val status: Int,
    val ok: Boolean,
    val statusText: String,
    val body: String,
)

// Keep fetch() synchronous for compatibility with existing custom search scripts.
// Both direct use and `await fetch(...)` work with this Response object.
private const val FETCH_POLYFILL = """
globalThis.fetch = function(url, options) {
    options = options || {};
    var method = (options.method || 'GET').toUpperCase();
    var headers = options.headers ? JSON.stringify(options.headers) : null;
    var body = options.body;
    if (typeof body === 'object' && body !== null) {
        body = JSON.stringify(body);
    } else if (typeof body !== 'string') {
        body = null;
    }

    var raw = __httpRequest(url, method, headers, body);
    var data = JSON.parse(raw);
    return {
        status: data.status,
        ok: data.ok,
        statusText: data.statusText,
        url: url,
        _body: data.body,
        text: function() { return this._body; },
        json: function() { return JSON.parse(this._body); }
    };
};
"""

suspend fun QuickJs.injectFetch(httpClient: OkHttpClient) {
    val parentJob = currentCoroutineContext().job
    function("__httpRequest") { args ->
        val url = args[0] as? String ?: error("url is required")
        val method = (args[1] as? String ?: "GET").uppercase()
        val headersJson = args[2] as? String
        val body = args[3] as? String

        val requestBuilder = Request.Builder().url(url)

        val parsedHeaders = if (!headersJson.isNullOrBlank() && headersJson != "null") {
            json.parseToJsonElement(headersJson).jsonObject
        } else null

        parsedHeaders?.entries?.forEach { (key, value) ->
            requestBuilder.addHeader(key, value.jsonPrimitive.content)
        }

        val contentType = try {
            parsedHeaders?.get("Content-Type")?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }

        val mediaType = (contentType ?: "application/json").toMediaType()
        when (method) {
            "GET" -> requestBuilder.get()
            "HEAD" -> requestBuilder.head()
            else -> {
                val reqBody = body?.toRequestBody(mediaType)
                    ?: if (method in setOf("POST", "PUT", "PATCH")) {
                        "".toRequestBody(mediaType)
                    } else {
                        null
                    }
                requestBuilder.method(method, reqBody)
            }
        }

        val call = httpClient.newCall(requestBuilder.build())
        // 整次调用超时（连接 + 读写）：脚本 fetch 打到自己配置的地址时，避免永久挂住
        call.timeout().timeout(FETCH_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // Native JS interruption cannot interrupt a blocking HTTP callback. A child job
        // observes cancellation immediately, even while evaluate() is still running.
        val cancellation = Job(parentJob)
        cancellation.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                json.encodeToString(
                    HttpResponseDto(
                        status = response.code,
                        ok = response.isSuccessful,
                        statusText = response.message,
                        body = readBoundedBody(response, FETCH_BODY_CAP_BYTES),
                    )
                )
            }
        } finally {
            cancellation.complete()
        }
    }

    evaluate<Unit>(FETCH_POLYFILL + "\nvoid 0;")
}

/**
 * Read at most [capBytes] from [response]'s body, mirroring me.rerere.search.boundedBody so a
 * huge or unbounded response can't be dragged fully into memory (and then into the JS engine's
 * own heap as a string) just because fetch() has to return the whole body synchronously.
 */
internal fun readBoundedBody(response: Response, capBytes: Int): String {
    val charset = response.body.contentType()?.charset() ?: Charsets.UTF_8
    val ins = response.body.byteStream()
    val out = ByteArrayOutputStream(minOf(capBytes, 8 * 1024))
    val buf = ByteArray(8192)
    var total = 0
    while (total < capBytes) {
        val want = minOf(buf.size, capBytes - total)
        val read = ins.read(buf, 0, want)
        if (read < 0) break
        out.write(buf, 0, read)
        total += read
    }
    return String(out.toByteArray(), charset)
}
