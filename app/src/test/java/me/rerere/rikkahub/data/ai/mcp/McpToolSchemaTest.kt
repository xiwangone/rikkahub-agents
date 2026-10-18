package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolSchemaTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun convert(properties: String, defs: String = "{}", required: List<String> = emptyList()): JsonObject {
        val input = ToolSchema(properties = json.parseToJsonElement(properties) as JsonObject,
            required = required, defs = json.parseToJsonElement(defs) as JsonObject).toSchema() as InputSchema.Obj
        assertEquals(required, input.required)
        return input.properties
    }

    @Test fun `local definition is expanded and sibling constraints override target`() {
        assertEquals(json.parseToJsonElement("""{"x":{"type":"string","minLength":3,"description":"local"}}"""),
            convert("""{"x":{"${'$'}ref":"#/${'$'}defs/Text","minLength":3,"description":"local"}}""",
                """{"Text":{"type":"string","minLength":1,"description":"definition"}}"""))
    }
    @Test fun `nested object and array refs are recursively expanded`() {
        assertEquals(json.parseToJsonElement("""{"x":{"type":"object","properties":{"rows":{"type":"array","items":{"type":"object","properties":{"id":{"type":"integer","minimum":1}}}}}}}"""),
            convert("""{"x":{"${'$'}ref":"#/${'$'}defs/Outer"}}""",
                """{"Outer":{"type":"object","properties":{"rows":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/Inner"}}}},"Inner":{"type":"object","properties":{"id":{"type":"integer","minimum":1}}}}"""))
    }
    @Test fun `prefixItems and composition arrays resolve refs`() {
        assertEquals(json.parseToJsonElement("""{"x":{"type":"array","prefixItems":[{"type":"string"}],"anyOf":[{"type":"number"}]}}"""),
            convert("""{"x":{"type":"array","prefixItems":[{"${'$'}ref":"#/${'$'}defs/A"}],"anyOf":[{"${'$'}ref":"#/${'$'}defs/B"}]}}""",
                """{"A":{"type":"string"},"B":{"type":"number"}}"""))
    }
    @Test fun `recursive definition terminates while retaining object type`() {
        val result = convert("""{"node":{"${'$'}ref":"#/${'$'}defs/Node"}}""",
            """{"Node":{"type":"object","properties":{"children":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/Node"}}}}}""")
        assertTrue(result.toString().contains("\"type\":\"object\""))
        assertFalse(result.toString().contains("${'$'}ref"))
    }
    @Test fun `missing and external refs are removed but sibling descriptions remain`() {
        assertEquals(json.parseToJsonElement("""{"missing":{"description":"kept"},"remote":{}}"""),
            convert("""{"missing":{"${'$'}ref":"#/${'$'}defs/Nope","description":"kept"},"remote":{"${'$'}ref":"https://example.org/s.json"}}"""))
    }
    @Test fun `escaped JSON pointer tokens resolve slash and tilde`() {
        assertEquals(json.parseToJsonElement("""{"x":{"type":"string","maxLength":8},"y":{"type":"integer"}}"""),
            convert("""{"x":{"${'$'}ref":"#/${'$'}defs/a~1b"},"y":{"${'$'}ref":"#/${'$'}defs/a~0b"}}""",
                """{"a/b":{"type":"string","maxLength":8},"a~b":{"type":"integer"}}"""))
    }
    @Test fun `literal ref keys in enum const default and examples are not interpreted`() {
        assertEquals(json.parseToJsonElement("""{"x":{"enum":[{"${'$'}ref":"#/${'$'}defs/Literal"}],"const":{"${'$'}ref":"not-a-schema-ref"},"default":{"${'$'}ref":"also-literal"},"examples":[{"${'$'}ref":"literal"}]}}"""),
            convert("""{"x":{"enum":[{"${'$'}ref":"#/${'$'}defs/Literal"}],"const":{"${'$'}ref":"not-a-schema-ref"},"default":{"${'$'}ref":"also-literal"},"examples":[{"${'$'}ref":"literal"}]}}"""))
    }
    @Test fun `all useful scalar constraints and required survive conversion`() {
        val result = convert("""{"x":{"type":"number","minimum":2,"maximum":7,"exclusiveMinimum":true,"multipleOf":0.5,"pattern":"^x$","format":"uri","enum":["x"],"description":"d","deprecated":true}}""", required = listOf("x"))
        assertEquals(json.parseToJsonElement("""{"type":"number","minimum":2,"maximum":7,"exclusiveMinimum":true,"multipleOf":0.5,"pattern":"^x$","format":"uri","enum":["x"],"description":"d","deprecated":true}"""), result["x"])
    }
    @Test fun `local array pointer target is expanded`() {
        assertEquals(json.parseToJsonElement("""{"x":{"type":"string"}}"""),
            convert("""{"x":{"${'$'}ref":"#/${'$'}defs/Choices/0"}}""", """{"Choices":[{"type":"string"}]}"""))
    }
}
