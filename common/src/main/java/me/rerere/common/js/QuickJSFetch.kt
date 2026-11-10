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


/**
 * True when this address must never be a script-driven HTTP target: loopback, link-local,
 * site-local (RFC1918), any-local, multicast, CGNAT (100.64.0.0/10), or IPv6 unique-local
 * (fc00::/7).
 *
 * 169.254.169.254, the cloud metadata address, falls out of the link-local check.
 */
private fun InetAddress.isBlockedTarget(): Boolean {
    val isLocalAddress = isLoopbackAddress || isAnyLocalAddress || isLinkLocalAddress
    val isSiteOrMulticast = isSiteLocalAddress || isMulticastAddress
    if (isLocalAddress || isSiteOrMulticast) {
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
 * Parse [host] as an IPv4 literal in any of the numeric forms a resolver accepts
 * (the `inet_aton` forms): a single number, `a.b`, `a.b.c`, or `a.b.c.d`, where each
 * part may be decimal, `0`-prefixed octal, or `0x`-prefixed hexadecimal. The standard
 * dotted-decimal form is only one of these: `2130706433`, `127.1`, `0x7f000001` and
 * `0177.0.0.1` all denote 127.0.0.1, and a proxy-side resolver interprets them the
 * same way, so the literal check must recognise them too. Returns the parsed address,
 * or null when [host] is not such a literal. No DNS lookup happens here: a hostname
 * is never passed to [InetAddress.getByName].
 */
/**
 * Parse one numeric IPv4 part (`inet_aton` form): `0x`-prefixed hex,
 * `0`-prefixed octal, or plain decimal. Returns null when the part is
 * empty or not a valid number in its form.
 */
private fun parseIpv4Part(part: String): Long? {
    if (part.isEmpty()) return null
    return when {
        part.startsWith("0x", ignoreCase = true) -> {
            if (part.length == 2) return null
            part.substring(2).toLongOrNull(16)
        }
        part.length > 1 && part[0] == '0' -> {
            if (!part.all { it in '0'..'7' }) return null
            part.toLongOrNull(8)
        }
        part.all { it.isDigit() } -> part.toLongOrNull(10)
        else -> null
    }
}

private fun parseNumericIpv4Literal(host: String): InetAddress? {
    if (host.contains(':')) return null
    val parts = host.split('.')
    if (parts.size !in 1..4) return null
    val values = LongArray(parts.size)
    for ((index, part) in parts.withIndex()) {
        values[index] = parseIpv4Part(part) ?: return null
    }
    val combined: Long = when (parts.size) {
        1 -> values[0].takeIf { it <= 0xFFFFFFFFL } ?: return null
        2 -> if (values[0] > 0xFF || values[1] > 0xFFFFFF) return null
            else (values[0] shl 24) or values[1]
        3 -> if (values[0] > 0xFF || values[1] > 0xFF || values[2] > 0xFFFF) return null
            else (values[0] shl 24) or (values[1] shl 16) or values[2]
        else -> {
            if (values.any { it > 0xFF }) return null
            (values[0] shl 24) or (values[1] shl 16) or (values[2] shl 8) or values[3]
        }
    }
    val bytes = byteArrayOf(
        ((combined shr 24) and 0xFF).toByte(),
        ((combined shr 16) and 0xFF).toByte(),
        ((combined shr 8) and 0xFF).toByte(),
        (combined and 0xFF).toByte(),
    )
    return InetAddress.getByAddress(bytes)
}

private val NUMERIC_IPV4_ATTEMPT_RE =
    Regex("""^(0[xX][0-9a-fA-F]+|[0-9]+)(\.(0[xX][0-9a-fA-F]+|[0-9]+)){0,3}$""")

/**
 * True when [host] is an IP literal that [isBlockedTarget] refuses. OkHttp routes a
 * literal-IP host straight to the socket without consulting the custom [Dns] (its route
 * selector short-circuits `canParseAsIpAddress`), so [GuardedDns] never sees it; a caller
 * uses this to reject such a host before dialing.
 *
 * A host counts as a literal when it parses as an IPv4 literal in any numeric form
 * (see [parseNumericIpv4Literal]) or contains ':' (an OkHttp `HttpUrl.host` for IPv6 is
 * bracket-free, and a real hostname never contains ':'). Only literals are inspected,
 * so no DNS query happens here; a hostname returns false. A null or blank host also
 * returns false: it is not a literal, and such a host only arises from an unparseable
 * URL that never dials. A numeric-looking host that does not parse as a valid literal
 * (for example `999.999.999.999`) is refused (fail closed) rather than handed to a
 * resolver that might interpret it differently.
 */
@Suppress("SwallowedException") // fail-closed：解析失败的字面量直接按“被禁”处理，返回值本身就是处理结果；UnknownHostException 已是具体类型，无法再收窄。
private fun hostIsBlockedLiteral(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    if (host.contains(':')) {
        return try {
            // No DNS lookup happens for an IP literal; getByName just parses it.
            InetAddress.getByName(host).isBlockedTarget()
        } catch (e: UnknownHostException) {
            true
        }
    }
    parseNumericIpv4Literal(host)?.let { return it.isBlockedTarget() }
    return NUMERIC_IPV4_ATTEMPT_RE.matches(host)
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
