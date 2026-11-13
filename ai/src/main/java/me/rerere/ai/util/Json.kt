package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

@PublishedApi
internal val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Canonical sensitive-key word list for JSON redaction — the single口径 shared by
 * structured logging (every provider `redactSecrets` call site) and the SSE
 * `settings` snapshot (the web route now calls this same function instead of
 * keeping its own 16-name list).
 *
 * Matching is a case-insensitive **suffix** match on the key with separators
 * (`_`, `-`, `.`, spaces) stripped, so it covers every spelling the old
 * implementations each covered only in part:
 *  - bare keys: `token`, `password`, `key`, `authorization`, `auth`, `secret` …
 *  - snake_case: `api_key`, `private_key`, `refresh_token`, `access_token` …
 *  - camelCase: `accessToken`, `refreshToken`, `clientSecret`, `apiKey`, `myApiKey` …
 *  - prefixed: `x-api-key`, `tavilyApiKey`, `proxy-authorization` …
 *
 * Deliberately suffix-based rather than exact: a key ending in a sensitive word
 * is credential material whatever its prefix. The cost is mild over-redaction of
 * lookalikes (`monkey`, `turkey` end in `key`) — acceptable for a redactor, the
 * same trade-off `LogRedactor` already makes for URL query params. Words are
 * whole sensitive words, not fragments: `sign` / `sig` are NOT in the list, so
 * `design` / `assign` stay untouched.
 */
private val SECRET_KEY_SUFFIXES: Set<String> = setOf(
    "password",
    "passphrase",
    "secret",
    "token",
    "apikey",
    "key",
    "privatekey",
    "authorization",
    "auth",
    "credential",
    "credentials",
    "bearer",
    "passwd",
    "pwd",
    "signature",
)

/** Strip separators and lowercase, so `X-Api-Key`, `api_key` and `apiKey` compare equal. */
private fun normalizeKey(key: String): String =
    key.lowercase().filter { it.isLetterOrDigit() }

/**
 * Whether a JSON key names credential material. Public so the SSE route and the
 * free-text log redactor share this口径 instead of each keeping a copy.
 */
fun isSecretKey(key: String): Boolean {
    val normalized = normalizeKey(key)
    if (normalized.isEmpty()) return false
    return SECRET_KEY_SUFFIXES.any { normalized.endsWith(it) }
}

/**
 * Header names that are sensitive although no word in [SECRET_KEY_SUFFIXES]
 * covers them (`Cookie` carries session credentials, `Set-Cookie` carries them
 * back). Everything else header-shaped (`Authorization`, `X-Api-Key`,
 * `X-CSRF-Token` …) is already covered by [isSecretKey].
 */
private val SENSITIVE_NAME_EXTRAS: Set<String> = setOf("cookie", "setcookie")

/**
 * Whether a `name` field of a `{name, value}` / `{first, second}` pair object
 * names credential material — i.e. whether the paired `value` must be redacted.
 * This is what the SSE `settings` snapshot was leaking: MCP headers serialize as
 * `[{"first": "Authorization", "second": "Bearer …"}]` and provider custom headers
 * as `[{"name": "x-api-key", "value": "…"}]`; neither `name` nor `value` is itself
 * a secret key, so key-only redaction passed both through verbatim.
 */
fun isSensitiveName(name: String): Boolean {
    val normalized = normalizeKey(name)
    return normalized in SENSITIVE_NAME_EXTRAS || isSecretKey(name)
}

/**
 * Walk [element] and replace any string primitive whose KEY matches [isSecretKey]
 * with the string `"***"`. Numbers, booleans, nulls, and non-secret strings pass
 * through unchanged. Objects shaped as `{name, value}` / `{first, second}` /
 * `{key, value}` pairs have their value replaced when the name is sensitive
 * ([isSensitiveName]) — the name itself is kept, only the credential is hidden.
 */
fun redactSecrets(element: JsonElement, key: String? = null): JsonElement {
    val isSecret = key != null && isSecretKey(key)
    return when (element) {
        is JsonPrimitive ->
            if (isSecret && element.isString) JsonPrimitive("***") else element

        is JsonObject -> redactObject(element)
        is JsonArray -> buildJsonArray { element.forEach { add(redactSecrets(it, key)) } }
    }
}

private fun redactObject(element: JsonObject): JsonElement {
    val name = (element["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: (element["first"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: (element["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val valueKey = when {
        "value" in element -> "value"
        "second" in element -> "second"
        else -> null
    }
    return JsonObject(element.mapValues { (k, v) ->
        if (k == valueKey && name != null && isSensitiveName(name)) JsonPrimitive("***")
        else redactSecrets(v, k)
    })
}
