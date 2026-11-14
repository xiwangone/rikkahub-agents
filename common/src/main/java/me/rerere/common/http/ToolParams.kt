package me.rerere.common.http

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Lenient readers for model-supplied tool arguments.
 *
 * Tool arguments come from the model as a [JsonObject], and the model often sends a
 * number or a boolean as a string ("limit": "20", "overwrite": "true"), or sends a
 * JSON object/array where a primitive is expected. Reading such values with the
 * throwing `JsonElement.jsonPrimitive` accessor turns a recoverable shape mistake
 * into an `IllegalArgumentException` that aborts the whole tool call.
 *
 * These readers never throw: a missing key, `JsonNull`, or a non-primitive node all
 * read as `null`, so the caller can fall back to a default or report a clean
 * "parameter is required / has the wrong type" error instead. String numbers and
 * string booleans coerce through the underlying `content` parsing, so `"20"` reads
 * as `20` and `"true"` reads as `true`. String booleans match case-insensitively
 * (`"True"` also reads as `true`); any other string reads as `null`.
 */
fun JsonObject.optString(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.optInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.optLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

fun JsonObject.optBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.optDouble(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

/**
 * Reads a string list, skipping elements that are not primitives. A missing key or
 * a non-array node reads as `null`; an empty array reads as an empty list.
 */
fun JsonObject.optStringList(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
