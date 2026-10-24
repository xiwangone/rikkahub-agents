package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.common.http.jsonPrimitiveOrNull

internal fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitiveOrNull?.contentOrNull
