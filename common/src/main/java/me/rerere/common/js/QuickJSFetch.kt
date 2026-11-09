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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
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
        // An IP literal host is routed straight to the socket without a DNS lookup,
        // so a DNS-level guard never sees it. Reject it here, before the call is
        // built, so it cannot reach any interceptor or the network.
        if (hostIsBlockedLiteral(url.toHttpUrlOrNull()?.host)) {
            error("blocked_private_address: $url")
        }
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

private val IPV4_LITERAL_RE = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/**
 * True when this address must never be a script-driven HTTP target: loopback, link-local,
 * site-local (RFC1918), any-local, multicast, CGNAT (100.64.0.0/10), or IPv6 unique-local
 * (fc00::/7).
 *
 * 169.254.169.254, the cloud metadata address, falls out of the link-local check.
 */
private fun InetAddress.isBlockedTarget(): Boolean {
    if (isLoopbackAddress || isAnyLocalAddress || isLinkLocalAddress ||
        isSiteLocalAddress || isMulticastAddress
    ) {
        return true
    }
    // 100.64.0.0/10 is not covered by isSiteLocalAddress.
    if (this is Inet4Address) {
        val b = address
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        if (first == 100 && second in 64..127) return true
    }
    // fc00::/7 (unique-local) is not covered by isSiteLocalAddress, which only matches
    // the deprecated fec0::/10 for Inet6Address.
    if (this is Inet6Address) {
        val first = address[0].toInt() and 0xFF
        if (first and 0xFE == 0xFC) return true
    }
    return false
}

/**
 * True when [host] is an IP literal that [isBlockedTarget] refuses. A host counts as a
 * literal when it is an IPv4 dotted-quad or contains ':' (an OkHttp `HttpUrl.host` for
 * IPv6 is bracket-free, and a real hostname never contains ':'). Only literals are
 * inspected, so no DNS query happens here; a hostname returns false. A null or blank
 * host also returns false: it is not a literal, and such a host only arises from an
 * unparseable URL that never dials. A malformed literal is refused (fail closed) rather
 * than throwing.
 */
private fun hostIsBlockedLiteral(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    val isLiteral = IPV4_LITERAL_RE.matches(host) || host.contains(':')
    if (!isLiteral) return false
    return try {
        // No DNS lookup happens for an IP literal; getByName just parses it.
        InetAddress.getByName(host).isBlockedTarget()
    } catch (e: UnknownHostException) {
        true
    }
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
