package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.net.hostIsBlockedLiteral
import me.rerere.rikkahub.data.ai.net.withEgressGuard
import me.rerere.common.http.optString
import me.rerere.rikkahub.data.ai.tools.ToolErrors
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private const val WEB_EXTRACT_TIMEOUT_MS = 30_000L

/**
 * Read a web page as text. A thin wrapper over the same extraction path as
 * `web_fetch(extract_mode=...)`; it exists as its own tool because a model looking to
 * *read* a page reaches for a reading verb, not for a transport primitive.
 */
/** URL 校验：空值 / 协议 / 私有地址字面量。返回 (url, 错误 JSON)，二者恰有一个非空。 */
private fun resolveExtractTarget(obj: JsonObject): Pair<String?, String?> {
    val url = obj.optString("url")?.trim()
    if (url.isNullOrBlank()) return null to fmErrEnvelope("missing_url", "url is required")
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
        return null to fmErrEnvelope("bad_url", "url must start with http:// or https://")
    }
    // OkHttp routes a literal-IP host straight to the socket without consulting GuardedDns, so
    // refuse private/loopback/link-local literals deterministically here; the egress interceptor
    // still covers literal redirect hops.
    val host = url.toHttpUrlOrNull()?.host
    if (host != null && hostIsBlockedLiteral(host)) {
        return null to ToolErrors.envelopeFor(
            error = "blocked_address",
            message = "blocked_private_address: $host",
            hint = "This tool refuses private, loopback and link-local addresses.",
        ).toString()
    }
    return url to null
}

/** mode 校验：非法值 / 不支持的 raw。 */
private fun resolveExtractMode(obj: JsonObject): Pair<FetchExtract?, String?> {
    val mode = parseExtractModeOrNull(obj.optString("mode") ?: "article")
        ?: return null to fmErrEnvelope("bad_mode", "mode must be one of article, text, links, metadata")
    if (mode == FetchExtract.RAW) {
        return null to fmErrEnvelope(
            "bad_mode",
            "web_extract does not serve raw markup; use web_fetch with extract_mode='raw'",
        )
    }
    return mode to null
}

fun webExtractTool(client: OkHttpClient): Tool = Tool(
    name = "web_extract",
    description = """
        Read a web page as clean readable content (nav/ads stripped). Prefer over web_fetch
        to read a page's prose; JS-rendered pages may return empty — use browser tools then.
        Truncated results carry next_start_index for paging.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The http:// or https:// URL to read")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "article (default), text, links, or metadata")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum characters of text to return")
                })
                put("start_index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Resume offset; pass next_start_index from a truncated result")
                })
            },
            required = listOf("url"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val (url, urlError) = resolveExtractTarget(obj)
        if (url == null) return@Tool fmTextPart(urlError.orEmpty())
        val (mode, modeError) = resolveExtractMode(obj)
        if (mode == null) return@Tool fmTextPart(modeError.orEmpty())

        val startIndex = obj.optString("start_index")?.toIntOrNull() ?: 0
        val maxChars = obj.optString("max_chars")?.toIntOrNull()
            ?.coerceIn(1, WEB_FETCH_EXTRACT_CAP) ?: WEB_FETCH_EXTRACT_CAP

        val guarded = client.withEgressGuard()

        val result = withTimeoutOrNull(WEB_EXTRACT_TIMEOUT_MS) {
            try {
                guarded.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    val (raw, bodyTruncated) = readBounded(resp.body.byteStream(), WEB_FETCH_EXTRACT_CAP * 8)
                    val contentType = resp.header("Content-Type")
                    buildExtractEnvelope(
                        status = resp.code,
                        ok = resp.isSuccessful,
                        finalUrl = resp.request.url.toString(),
                        html = decodeBody(raw, raw.size, contentType),
                        contentType = contentType,
                        mode = mode,
                        maxChars = maxChars,
                        startIndex = startIndex,
                        bodyTruncated = bodyTruncated,
                        headers = null,
                    )
                }
            } catch (e: IllegalArgumentException) {
                ToolErrors.envelopeFor(
                    error = "invalid_argument",
                    message = e.message ?: "Malformed URL: $url",
                    hint = "Pass a valid http:// or https:// URL.",
                ).toString()
            } catch (e: java.io.InterruptedIOException) {
                // OkHttp's callTimeout (set in withEgressGuard) fires this when a call, including
                // a trickling read, runs past the advertised 30s limit; withTimeoutOrNull cannot
                // catch this itself since the blocking execute() call has no suspension point.
                ToolErrors.envelopeFor(error = "timeout", message = "Request exceeded the 30s limit.", hint = "The host is slow or unreachable; try a different URL.").toString()
            } catch (e: IOException) {
                val blocked = e.message?.contains("blocked_private_address") == true
                ToolErrors.envelopeFor(
                    error = if (blocked) "blocked_address" else "network_error",
                    message = e.message ?: e::class.java.simpleName,
                    hint = if (blocked) {
                        "This tool refuses private, loopback and link-local addresses."
                    } else {
                        "Check connectivity and that the host is reachable, then retry."
                    },
                ).toString()
            }
        } ?: ToolErrors.envelopeFor(error = "timeout", message = "Request exceeded the 30s limit.", hint = "The host is slow or unreachable; try a different URL.").toString()

        fmTextPart(result)
    },
)
