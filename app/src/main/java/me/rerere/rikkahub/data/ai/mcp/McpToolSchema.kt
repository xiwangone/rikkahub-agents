package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema

private const val SCHEMA_REF = "\$ref"

/** Convert MCP inputSchema while inlining document-local refs before `$defs` is discarded. */
internal fun ToolSchema.toSchema(): InputSchema {
    val properties = properties ?: JsonObject(emptyMap())
    val root = JsonObject(buildMap {
        put("type", JsonPrimitive(type))
        put("properties", properties)
        defs?.let { put("\$defs", it) }
    })
    return InputSchema.Obj(
        properties = inlineSchemaMap(properties, root, emptySet()),
        required = required,
    )
}

// Schema-valued single/subschema-array keywords. Literal data (enum/default/const/examples) is untouched.
private val schemaKeywords = setOf(
    "items", "prefixItems", "additionalItems", "unevaluatedItems", "contains",
    "additionalProperties", "unevaluatedProperties", "propertyNames",
    "allOf", "anyOf", "oneOf", "not", "if", "then", "else", "contentSchema",
)
private val schemaMapKeywords = setOf("properties", "patternProperties", "dependentSchemas", "\$defs", "definitions")

private fun inlineSchemaMap(map: JsonObject, root: JsonObject, resolving: Set<String>) =
    JsonObject(map.mapValues { (_, value) -> inlineSchema(value, root, resolving) })

private fun inlineSchema(schema: JsonElement, root: JsonObject, resolving: Set<String>): JsonElement {
    if (schema !is JsonObject) return schema
    val ref = (schema[SCHEMA_REF] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val siblings = (if (ref == null) schema else schema - SCHEMA_REF).mapValues { (key, value) ->
        when {
            key in schemaKeywords && value is JsonArray -> JsonArray(value.map { inlineSchema(it, root, resolving) })
            key in schemaKeywords -> inlineSchema(value, root, resolving)
            key in schemaMapKeywords && value is JsonObject -> inlineSchemaMap(value, root, resolving)
            else -> value
        }
    }
    if (ref == null) return JsonObject(siblings)

    val target = resolveLocalPointer(root, ref) as? JsonObject
    val expanded = when {
        target == null -> emptyMap() // external/malformed/missing refs are removed, siblings survive
        ref in resolving -> target.filterKeys { it == "type" } // terminate recursive definitions
        else -> inlineSchema(target, root, resolving + ref).let { (it as? JsonObject).orEmpty() }
    }
    // JSON Schema permits sibling keywords alongside $ref; preserve all constraints, sibling wins.
    return JsonObject(expanded + siblings)
}

private fun resolveLocalPointer(root: JsonObject, ref: String): JsonElement? {
    if (ref == "#") return root
    if (!ref.startsWith("#/")) return null
    val tokens = ref.removePrefix("#/").split('/').map { it.replace("~1", "/").replace("~0", "~") }
    val initial: JsonElement? = root
    return tokens.fold(initial) { node, token ->
        when (node) {
            is JsonObject -> node[token]
            is JsonArray -> token.toIntOrNull()?.let(node::getOrNull)
            else -> null
        }
    }
}
