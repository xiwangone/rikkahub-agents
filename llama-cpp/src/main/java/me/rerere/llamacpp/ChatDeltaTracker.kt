package me.rerere.llamacpp

import org.json.JSONObject

data class ParsedToolCall(val id: String, val name: String, val arguments: String)

data class ChatDelta(
    val textDelta: String,
    val reasoningDelta: String,
    val completedToolCalls: List<ParsedToolCall>,
)

/**
 * Converts successive whole-message parses into incremental deltas.
 *
 * llama.cpp re-parses the entire generated text each time, so every parse restates
 * everything seen so far. The chat surface needs only what is new.
 */
class ChatDeltaTracker {

    private var emittedText = ""
    private var emittedReasoning = ""
    private val emittedCalls = mutableSetOf<String>()

    /**
     * Consumes one whole-message parse and returns what changed since the last call.
     *
     * A tool call's arguments are emitted the first time they parse as complete JSON: a
     * top-level JSON object cannot parse until its closing brace arrives, so completeness
     * already means the arguments are finished. No stability check across repeated
     * parses is needed to confirm that.
     *
     * The caller MUST pass [isPartial] = false exactly once, on the final parse. When it
     * is false, any not-yet-emitted call whose arguments never became complete JSON (the
     * model was cut off mid-call, e.g. by max_tokens) is flushed with its raw, incomplete
     * arguments string instead of being dropped silently, so a truncated call fails
     * loudly in the tool executor rather than the assistant appearing to do nothing. A
     * call with no arguments at all is not flushed, since there is nothing to surface.
     */
    fun consume(parsedJson: String, isPartial: Boolean = true): ChatDelta {
        val obj = JSONObject(parsedJson)
        val content = obj.optString("content", "")
        val reasoning = obj.optString("reasoning_content", "")

        val (textDelta, newEmittedText) = advance(emittedText, content)
        val (reasoningDelta, newEmittedReasoning) = advance(emittedReasoning, reasoning)
        emittedText = newEmittedText
        emittedReasoning = newEmittedReasoning

        val completed = mutableListOf<ParsedToolCall>()
        val calls = obj.optJSONArray("tool_calls")
        if (calls != null) {
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.optJSONObject("function") ?: continue
                // optString(key, fallback) only falls back when the key is absent or
                // JSON null, not when it is present but blank. A model that emits an
                // empty "id" for every call would otherwise collapse them all onto the
                // same generated id, silently dropping every call after the first.
                val id = call.optString("id", "").ifBlank { "call_$i" }
                if (emittedCalls.contains(id)) {
                    continue
                }
                val arguments = fn.optString("arguments", "")
                val complete = isCompleteJson(arguments)
                val shouldEmit = complete || (!isPartial && arguments.isNotBlank())
                if (!shouldEmit) {
                    continue
                }

                emittedCalls += id
                completed += ParsedToolCall(id, fn.optString("name", ""), arguments)
            }
        }

        return ChatDelta(textDelta, reasoningDelta, completed)
    }

    /**
     * The new text minus what was already emitted, plus the watermark to carry into the
     * next call. When the new content does not extend what was already emitted (a
     * re-parse that shrank), nothing is emitted and the watermark stays at the longer,
     * already-emitted text instead of retreating to the shorter one. Retreating would
     * let a later extension of the shorter text re-emit content the UI already showed,
     * e.g. "Hello world" -> "Hello" -> "Hello there" would otherwise render as
     * "Hello world there".
     */
    private fun advance(previouslyEmitted: String, current: String): Pair<String, String> = when {
        current == previouslyEmitted -> "" to previouslyEmitted
        current.startsWith(previouslyEmitted) -> current.substring(previouslyEmitted.length) to current
        else -> "" to previouslyEmitted
    }

    private fun isCompleteJson(value: String): Boolean {
        if (value.isBlank()) return false
        return runCatching { JSONObject(value) }.isSuccess
    }
}
