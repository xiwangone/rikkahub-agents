package me.rerere.common.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lenient tool-argument readers ([ToolParams.kt]).
 *
 * The model sends numbers/booleans as strings ("limit": "20") and sometimes an
 * object/array where a primitive is expected. All readers must coerce the string
 * forms and read every other shape mistake as null instead of throwing.
 */
class ToolParamsTest {

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `optString reads strings and coerces numbers and booleans`() {
        val args = obj("""{"a":"x","b":20,"c":true,"d":1.5}""")
        assertEquals("x", args.optString("a"))
        assertEquals("20", args.optString("b"))
        assertEquals("true", args.optString("c"))
        assertEquals("1.5", args.optString("d"))
    }

    @Test
    fun `optString returns null for missing keys nulls and non-primitives`() {
        val args = obj("""{"a":null,"b":{"x":1},"c":[1,2]}""")
        assertNull(args.optString("missing"))
        assertNull(args.optString("a"))
        assertNull(args.optString("b"))
        assertNull(args.optString("c"))
    }

    @Test
    fun `optInt coerces string numbers and rejects the rest`() {
        val args = obj("""{"a":20,"b":"20","c":"abc","d":20.5,"e":true,"f":{"x":1}}""")
        assertEquals(20, args.optInt("a"))
        assertEquals(20, args.optInt("b"))
        assertNull(args.optInt("c"))
        assertNull(args.optInt("d"))
        assertNull(args.optInt("e"))
        assertNull(args.optInt("f"))
        assertNull(args.optInt("missing"))
    }

    @Test
    fun `optLong coerces string longs`() {
        val args = obj("""{"a":1234567890123,"b":"1234567890123","c":"nope"}""")
        assertEquals(1234567890123L, args.optLong("a"))
        assertEquals(1234567890123L, args.optLong("b"))
        assertNull(args.optLong("c"))
    }

    @Test
    fun `optBoolean coerces string booleans case-insensitively`() {
        val args = obj("""{"a":true,"b":"true","c":"false","d":"True","e":1,"f":"yes"}""")
        assertEquals(true, args.optBoolean("a"))
        assertEquals(true, args.optBoolean("b"))
        assertEquals(false, args.optBoolean("c"))
        // kotlinx booleanOrNull matches "true"/"false" ignoring case
        assertEquals(true, args.optBoolean("d"))
        assertNull(args.optBoolean("e"))
        assertNull(args.optBoolean("f"))
    }

    @Test
    fun `optDouble coerces string doubles and whole numbers`() {
        val args = obj("""{"a":1.5,"b":"1.5","c":2,"d":"abc"}""")
        assertEquals(1.5, args.optDouble("a"))
        assertEquals(1.5, args.optDouble("b"))
        assertEquals(2.0, args.optDouble("c"))
        assertNull(args.optDouble("d"))
    }

    @Test
    fun `optStringList reads arrays and skips non-primitive elements`() {
        val args = obj("""{"a":["x","y"],"b":["x",20,{"k":1},true],"c":"x","d":[]}""")
        assertEquals(listOf("x", "y"), args.optStringList("a"))
        assertEquals(listOf("x", "20", "true"), args.optStringList("b"))
        assertNull(args.optStringList("c"))
        assertEquals(emptyList<String>(), args.optStringList("d"))
        assertNull(args.optStringList("missing"))
    }
}
