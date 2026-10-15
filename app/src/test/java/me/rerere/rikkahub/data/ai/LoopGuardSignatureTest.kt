package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [LoopGuard.signature] normalisation (L1 key order + L2 whitespace) and, just as
 * important, what it must NOT normalise.
 *
 * The guard compares signatures for equality, so every case is expressed as an equality claim
 * on the produced signature — never on the intermediate JSON text — which is what the callsite
 * in generateText actually depends on.
 */
class LoopGuardSignatureTest {

    private fun sig(input: String, name: String = "read_file") =
        LoopGuard.signatureOf(Json, name, input)

    // ── L1: object key order ────────────────────────────────────────────────

    @Test
    fun keyOrderOnly_differs_canonicallyEqual() {
        assertEquals(
            sig("""{"b":2,"a":1}"""),
            sig("""{"a":1,"b":2}"""),
        )
    }

    @Test
    fun keyOrder_nestedAndInsideArrays_canonicallyEqual() {
        assertEquals(
            sig("""{"outer":{"y":[2,{"q":1,"p":0}],"x":true},"a":"z"}"""),
            sig("""{"a":"z","outer":{"x":true,"y":[2,{"p":0,"q":1}]}}"""),
        )
    }

    @Test
    fun arrayElementOrder_isSemantics_notNormalised() {
        // [a, b] and [b, a] are different calls (page lists, edit ops, coordinates).
        assertNotEquals(sig("""{"xs":["a","b"]}"""), sig("""{"xs":["b","a"]}"""))
    }

    // ── L2: formatting whitespace ───────────────────────────────────────────

    @Test
    fun prettyPrinted_vsCompact_canonicallyEqual() {
        val pretty = """
            {
              "path" : "/sdcard/x",
              "mode" : "ro"
            }
        """.trimIndent()
        assertEquals(sig("""{"path":"/sdcard/x","mode":"ro"}"""), sig(pretty))
    }

    @Test
    fun whitespaceInsideStringLiteral_isPreserved_notStripped() {
        // Two messages differing only by spaces/newlines inside the text are different calls.
        assertNotEquals(sig("""{"text":"hi  there"}"""), sig("""{"text":"hithere"}"""))
        assertNotEquals(sig("""{"text":"a\nb"}"""), sig("""{"text":"ab"}"""))
        // And the literal's own spacing survives canonicalisation.
        assertNotEquals(sig("""{"text":"a b"}"""), sig("""{"text":"a  b"}"""))
    }

    // ── known, deliberately accepted misses ─────────────────────────────────

    @Test
    fun offsetTimestampAndRandomId_stayDistinct() {
        assertNotEquals(sig("""{"offset":0}"""), sig("""{"offset":50}"""))
        assertNotEquals(sig("""{"node_id":"1:2"}"""), sig("""{"node_id":"1:3"}"""))
        assertNotEquals(
            sig("""{"at":1728100000000,"command":"ls"}"""),
            sig("""{"at":1728100000001,"command":"ls"}"""),
        )
    }

    // ── args口径 / failure paths ────────────────────────────────────────────

    @Test
    fun blankArgs_shareTheExistingIfBlankCaliber() {
        assertEquals(sig("{}"), sig(""))
        assertEquals(sig("{}"), sig("   \n  "))
    }

    @Test
    fun unparseableArgs_fallBackToRawAndNeverThrow() {
        val truncated = """{"command":"rm -rf """
        // Must not throw, and must keep comparing by the raw text (no false equality).
        assertEquals("termux_run_command::$truncated", sig(truncated, "termux_run_command"))
        assertNotEquals(sig(truncated, "termux_run_command"), sig("""{"command":"rm -rf"}""", "termux_run_command"))
    }

    @Test
    fun nonObjectTopLevel_isCanonicalisedAndStable() {
        assertEquals(sig("""[1, 2]"""), sig("""[1,2]"""))
        assertEquals(sig("123"), sig(" 123 "))
        // signature() is pure: same args twice ⇒ identical text.
        assertEquals(sig("""{"b":{"d":[1,2],"c":3},"a":1}"""), sig("""{"b":{"d":[1,2],"c":3},"a":1}"""))
    }

    @Test
    fun preParsedAndParseBasedPaths_agree() {
        val input = """{"b":1,"a":[2,{"y":1,"x":0}]}"""
        val parsed = Json.parseToJsonElement(input)
        assertEquals(
            LoopGuard.signature("t", input, parsed),
            LoopGuard.signatureOf(Json, "t", input),
        )
    }

    // ── the point of the whole thing: the guard actually trips ───────────────

    @Test
    fun driftInRepresentation_noLongerEvadesTheGuard() {
        // The model re-issues the same call three times, each time with different key order /
        // spacing / trailing newline. Before normalisation these were three distinct signatures
        // and the loop ran unimpeded. Threshold is 3 prior identical calls.
        val canonical = sig("""{"q":"kotlin","limit":10}""", "search")
        val prior = listOf(
            PriorToolCall("search", sig("""{"limit":10,"q":"kotlin"}""", "search"), 0L),
            PriorToolCall("search", sig("{\n  \"limit\" : 10,\n  \"q\": \"kotlin\"\n}", "search"), 0L),
            PriorToolCall("search", canonical, 0L),
        )
        val decision = LoopGuard.evaluate(
            priorCalls = prior,
            toolName = "search",
            signature = canonical,
            nowMs = 0L,
        )
        assertEquals(3, decision.priorOccurrences)
        assertTrue("key-order/whitespace drift must not evade the guard", decision.block)
    }

    @Test
    fun realArgDrift_stillDoesNotTrip() {
        val prior = listOf(
            PriorToolCall("search", sig("""{"q":"a","offset":0}""", "search"), 0L),
            PriorToolCall("search", sig("""{"offset":10,"q":"a"}""", "search"), 0L),
        )
        val decision = LoopGuard.evaluate(
            priorCalls = prior,
            toolName = "search",
            signature = sig("""{"q":"a","offset":20}""", "search"),
            nowMs = 0L,
        )
        assertEquals(0, decision.priorOccurrences)
        assertTrue(!decision.block)
    }
}
