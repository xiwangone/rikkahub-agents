package me.rerere.locallm.litert

import android.graphics.Bitmap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream

/** Role labels used in [Turn] signatures. Kept as plain strings so [turnSignature] is a
 *  pure function the provider and the runtime can both call without sharing an enum. */
const val ROLE_USER = "user"
const val ROLE_ASSISTANT = "assistant"

/**
 * Stable, content-derived signature for one conversation turn. Used to decide whether the
 * warm [Conversation]'s KV cache can be reused (see [planTurns]). Length + hashCode keeps
 * the collision rate negligible; a collision would only cause a (correct) cold reload, never
 * wrong output.
 */
fun turnSignature(role: String, text: String): String = "$role|${text.length}|${text.hashCode()}"

/**
 * One rendered conversation turn handed from [LiteRtProvider] to [LiteRtRuntime].
 *
 * [rawText] is the turn's content WITHOUT any "User:/Assistant:" markers — when this turn is
 * the single new turn appended to a warm Conversation, [rawText] is sent as-is and the SDK's
 * chat template applies the role wrapping (the same path Gallery uses). The marker-prefixed
 * cold blob is built separately by the provider for the rebuild-from-scratch path.
 */
data class Turn(val role: String, val rawText: String) {
    val signature: String get() = turnSignature(role, rawText)
}

/**
 * One tool call the model asked for, lifted out of the SDK's `ToolCall` so callers do not
 * have to depend on litertlm types. [argumentsJson] is the arguments object serialised back
 * to a JSON string, matching the `input` field of `UIMessagePart.Tool`.
 */
data class RuntimeToolCall(val name: String, val argumentsJson: String)

/**
 * What [LiteRtRuntime.streamTurns] emits.
 *
 * The runtime disables the SDK's automatic tool calling, so a turn can end either with
 * generated text, with tool calls the host must execute, or with both. Tool calls are
 * surfaced rather than run here so the host can apply approval and the HARDLINE floor.
 */
sealed interface StreamEvent {
    /** The **cumulative** response so far, not a delta, same contract the SDK's
     *  `MessageCallback` uses. Downstream computes deltas itself. */
    data class Delta(val cumulative: String) : StreamEvent

    /** Tool calls the model emitted this turn, in the order the model produced them. */
    data class ToolCalls(val calls: List<RuntimeToolCall>) : StreamEvent
}

/**
 * Outcome of [planTurns]: whether the live [Conversation]'s KV cache can be reused.
 */
sealed interface TurnPlan {
    /** Reuse the warm Conversation; send only `history[sendFromIndex]` (guaranteed exactly
     *  one new turn). The KV cache already holds every turn before [sendFromIndex]. */
    data class Warm(val sendFromIndex: Int) : TurnPlan

    /** Recreate the Conversation (clearing the KV cache) and send the full cold blob. */
    data object Cold : TurnPlan
}

/**
 * Thrown when a `.litertlm` model file is structurally broken in a way that retrying
 * with a different accelerator won't fix (e.g. corrupt tokenizer data, invalid magic
 * number, schema mismatch). Callers should treat [modelPath] as permanently unloadable,
 * delete the file from disk, and remove it from the provider's models list.
 */
class LiteRtModelCorruptException(
    val modelPath: String,
    cause: Throwable,
) : RuntimeException(
    "LiteRT model file appears corrupt or incompatible: ${cause.message}",
    cause,
)

/**
 * Thrown when engine init fails specifically inside the vision executor — the model file
 * itself is fine, but this device's GPU vision pipeline cannot be initialised. The
 * canonical signature is an error message containing `vision_litert_compiled_model_executor`
 * (the SDK's per-modality executor that fails when `CreateSharedMemoryManager` is
 * unimplemented in the OpenGL fallback path — LiteRT-LM #2292, hitting Adreno
 * 7xx + OEM ROMs that block `libvndksupport.so` discovery).
 *
 * The runtime self-recovers by retrying with `supportImage=false` (text-only) once. The
 * provider catches this and persists the per-model decision so subsequent loads skip
 * the doomed GPU vision attempt entirely. Multimodal models still load — they just
 * cannot accept images on this device.
 */
class LiteRtVisionUnavailableException(
    val modelPath: String,
    cause: Throwable,
) : RuntimeException(
    "LiteRT vision encoder failed to initialise on this device's GPU: ${cause.message}",
    cause,
)


internal fun Bitmap.toPngByteArray(): ByteArray {
    val stream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
}

internal fun planTurnsImpl(
    processed: List<String>,
    historySignatures: List<String>,
    hasMedia: Boolean,
): TurnPlan {
    if (hasMedia) return TurnPlan.Cold
    if (processed.isEmpty()) return TurnPlan.Cold
    if (processed.size >= historySignatures.size) return TurnPlan.Cold
    if (historySignatures.subList(0, processed.size) != processed) return TurnPlan.Cold
    if (historySignatures.size - processed.size != 1) return TurnPlan.Cold
    return TurnPlan.Warm(sendFromIndex = processed.size)
}

internal fun argumentsToJsonImpl(arguments: Map<String, Any?>): String =
    JsonObject(arguments.mapValues { (_, v) -> toJsonElement(v) }).toString()

private fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(
        value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) }
    )
    is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
    is Array<*> -> JsonArray(value.map { toJsonElement(it) })
    else -> JsonPrimitive(value.toString())
}

internal fun isVisionExecutorErrorImpl(joinedMessage: String): Boolean =
    joinedMessage.contains("vision_litert", ignoreCase = true) ||
        joinedMessage.contains("CreateSharedMemoryManager", ignoreCase = true) ||
        joinedMessage.contains("gpu_backend_opengl", ignoreCase = true)
