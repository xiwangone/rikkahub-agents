package me.rerere.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDeltaTrackerTest {

    private fun parsed(content: String, reasoning: String = "", toolCalls: String = "[]") = """
        {"role":"assistant","content":"$content","reasoning_content":"$reasoning",
         "tool_calls":$toolCalls}
    """.trimIndent()

    @Test
    fun `emits only the newly added text`() {
        val tracker = ChatDeltaTracker()
        assertEquals("Hel", tracker.consume(parsed("Hel")).textDelta)
        assertEquals("lo", tracker.consume(parsed("Hello")).textDelta)
        assertEquals(" there", tracker.consume(parsed("Hello there")).textDelta)
    }

    @Test
    fun `emits nothing when the parse is unchanged`() {
        val tracker = ChatDeltaTracker()
        tracker.consume(parsed("Hello"))
        val delta = tracker.consume(parsed("Hello"))
        assertEquals("", delta.textDelta)
        assertTrue(delta.completedToolCalls.isEmpty())
    }

    @Test
    fun `separates reasoning from content`() {
        val tracker = ChatDeltaTracker()
        val delta = tracker.consume(parsed(content = "answer", reasoning = "thinking"))
        assertEquals("answer", delta.textDelta)
        assertEquals("thinking", delta.reasoningDelta)
    }

    @Test
    fun `a tool call is emitted once, not on every subsequent parse`() {
        val tracker = ChatDeltaTracker()
        val call = """[{"id":"c1","type":"function",
            "function":{"name":"get_time","arguments":"{}"}}]"""
        val first = tracker.consume(parsed("", toolCalls = call))
        assertEquals(1, first.completedToolCalls.size)
        assertEquals("get_time", first.completedToolCalls[0].name)

        val second = tracker.consume(parsed("", toolCalls = call))
        assertTrue("the same call must not be emitted twice", second.completedToolCalls.isEmpty())
    }

    @Test
    fun `a growing partial arguments string is not emitted until it settles`() {
        // Partial parses can show a tool call whose arguments are still being written.
        // Emitting early would run a tool with truncated arguments.
        val tracker = ChatDeltaTracker()
        val growing = """[{"id":"c1","type":"function",
            "function":{"name":"write","arguments":"{\"text\":\"par"}}]"""
        val settled = """[{"id":"c1","type":"function",
            "function":{"name":"write","arguments":"{\"text\":\"partial\"}"}}]"""

        assertTrue(tracker.consume(parsed("", toolCalls = growing)).completedToolCalls.isEmpty())
        val done = tracker.consume(parsed("", toolCalls = settled)).completedToolCalls
        assertEquals(1, done.size)
        assertEquals("""{"text":"partial"}""", done[0].arguments)
    }

    // Defect 1: org.json's optString(key, fallback) only falls back when the key is
    // absent or JSON null, not when it is present but blank. A model that emits
    // `"id": ""` for every call must not collapse every call onto the same id.
    @Test
    fun `two tool calls with a blank id in the same parse both get distinct generated ids`() {
        val tracker = ChatDeltaTracker()
        val calls = """[
            {"id":"","type":"function","function":{"name":"a","arguments":"{}"}},
            {"id":"","type":"function","function":{"name":"b","arguments":"{}"}}
        ]"""

        val completed = tracker.consume(parsed("", toolCalls = calls)).completedToolCalls

        assertEquals(2, completed.size)
        val ids = completed.map { it.id }.toSet()
        assertEquals("both calls must get distinct generated ids", 2, ids.size)
        assertEquals(setOf("a", "b"), completed.map { it.name }.toSet())
    }

    // Defect 3: delta() must not move the watermark backwards when a re-parse shrinks
    // the content, or a later extension from the shorter base re-emits already-shown text.
    @Test
    fun `a shrinking then regrowing content never repeats or duplicates already emitted text`() {
        val tracker = ChatDeltaTracker()
        val deltas = mutableListOf<String>()
        deltas += tracker.consume(parsed("Hello world")).textDelta
        deltas += tracker.consume(parsed("Hello")).textDelta
        deltas += tracker.consume(parsed("Hello there")).textDelta

        val combined = deltas.joinToString("")
        assertFalse("must not repeat text already emitted", combined.contains("world there"))
        assertFalse("must not duplicate 'world'", combined.count { it == 'w' } > 1)
    }

    // Ruling 2: generation can be cut off mid-tool-call (e.g. by max_tokens). A call
    // whose arguments never became valid JSON must not be emitted while streaming, but
    // must be flushed with its raw arguments on the final, isPartial = false parse
    // rather than silently dropped.
    @Test
    fun `a call whose arguments never complete is withheld while streaming and flushed on the final parse`() {
        val tracker = ChatDeltaTracker()
        val truncated = """[{"id":"c1","type":"function",
            "function":{"name":"write","arguments":"{\"text\":\"cut off"}}]"""

        val whileStreaming = tracker.consume(parsed("", toolCalls = truncated))
        assertTrue(
            "incomplete arguments must not be emitted while still partial",
            whileStreaming.completedToolCalls.isEmpty(),
        )

        val flushed = tracker.consume(parsed("", toolCalls = truncated), isPartial = false).completedToolCalls
        assertEquals(1, flushed.size)
        assertEquals("c1", flushed[0].id)
        assertEquals("""{"text":"cut off""", flushed[0].arguments)
    }

    // Ruling 2 guard: do not manufacture a phantom call out of a tool call that never
    // produced any arguments at all.
    @Test
    fun `a pending call with blank arguments is not flushed on the final parse`() {
        val tracker = ChatDeltaTracker()
        val blank = """[{"id":"c1","type":"function","function":{"name":"write","arguments":""}}]"""

        val flushed = tracker.consume(parsed("", toolCalls = blank), isPartial = false).completedToolCalls

        assertTrue("a call with no arguments must not be flushed", flushed.isEmpty())
    }

    @Test
    fun `a call already emitted while streaming is not re-emitted on the final parse`() {
        val tracker = ChatDeltaTracker()
        val call = """[{"id":"c1","type":"function",
            "function":{"name":"get_time","arguments":"{}"}}]"""

        val whileStreaming = tracker.consume(parsed("", toolCalls = call)).completedToolCalls
        assertEquals(1, whileStreaming.size)

        val final = tracker.consume(parsed("", toolCalls = call), isPartial = false).completedToolCalls
        assertTrue("a call already emitted must not be re-emitted on the final parse", final.isEmpty())
    }
}
