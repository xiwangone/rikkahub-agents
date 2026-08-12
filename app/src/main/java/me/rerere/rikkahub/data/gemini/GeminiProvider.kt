package me.rerere.rikkahub.data.gemini

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.google.CODE_ASSIST_SAFETY_CATEGORIES
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.android.Logging
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Talks to Google Cloud Code Assist with a signed-in Google account instead of an API key.
 *
 * The wire format is plain Gemini wrapped one level deep: the request body goes under `request`
 * next to the account's project and the model id, and each SSE payload carries the usual
 * candidates under `response`. That lets the whole message conversion be delegated to
 * [GoogleProvider] rather than duplicated here.
 */
class GeminiProvider(
    private val client: OkHttpClient,
    private val repository: GeminiAccountRepository,
    private val json: Json,
) : Provider<ProviderSetting.GeminiOAuth> {
    private val wire = GoogleProvider(client)

    override suspend fun listModels(
        providerSetting: ProviderSetting.GeminiOAuth,
    ): List<Model> = withContext(Dispatchers.IO) {
        val account = repository.acquireAccount()
        val response = client.newCall(
            Request.Builder()
                .url("${GeminiAccountRepository.CODE_ASSIST_ENDPOINT}/v1internal:fetchAvailableModels")
                .antigravityHeaders(account.accessToken)
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
        ).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            if (response.code == 401) repository.markInvalid(account.id)
            error("Failed to list Gemini models: ${response.code} $body")
        }
        mapAvailableModels(body, json)
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.GeminiOAuth,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        var collected = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        val handler = StreamChunkHandler(params.model)
        streamText(providerSetting, messages, params).collect { chunk ->
            collected = handler.handle(collected, chunk)
        }
        val message = collected.last()
        return TextGenerationResult(
            id = "",
            model = params.model.modelId,
            message = message,
            usage = message.usage,
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.GeminiOAuth,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val account = repository.acquireAccount()
        val requestBody = buildJsonObject {
            put("project", account.projectId)
            put("model", params.model.modelId)
            put(
                "request",
                raiseMaxTokensAboveThinkingBudget(
                    raiseThinkingBudgetToClaudeFloor(
                        wire.buildCompletionRequestBody(messages, params, CODE_ASSIST_SAFETY_CATEGORIES)
                    )
                )
            )
        }
        val request = Request.Builder()
            .url("${GeminiAccountRepository.CODE_ASSIST_ENDPOINT}/v1internal:streamGenerateContent?alt=sse")
            .headers(params.customHeaders.toHeaders())
            .antigravityHeaders(account.accessToken)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(json.encodeToString(requestBody).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val adapter = GeminiStreamChunkAdapter()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                try {
                    val payload = json.parseToJsonElement(data).jsonObject
                    payload["error"]?.jsonObject?.let { error ->
                        close(
                            IllegalStateException(
                                "Cloud Code Assist error: " +
                                    (error["message"]?.jsonPrimitive?.contentOrNull ?: "unknown")
                            )
                        )
                        return
                    }
                    // Cloud Code Assist nests the ordinary Gemini payload one level down; a
                    // chunk that carries only bookkeeping has no `response` at all.
                    val inner = payload["response"]?.jsonObject ?: return
                    val reason = inner["promptFeedback"]?.jsonObject
                        ?.get("blockReason")?.jsonPrimitive?.contentOrNull
                    if (reason != null) {
                        close(IllegalStateException("Prompt feedback: $reason"))
                        return
                    }
                    val chunk = wire.parseStreamCandidates(inner, params.model) ?: return
                    adapter.translate(chunk).forEach { streamChunk ->
                        trySend(streamChunk).onFailure { e ->
                            Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onEvent: failed to parse chunk, payload=${data.take(PAYLOAD_LOG_LIMIT)}", e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (response?.code == 401) {
                    launch { repository.markInvalid(account.id) }
                }
                close(
                    resolveStreamFailureCause(t, response?.code, json) {
                        response?.takeUnless { it.isSuccessful }?.body?.stringSafe()
                    }
                )
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(adapter.finish())
                close()
            }
        }
        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
        // trySend silently drops a delta when the buffer is full, dropping characters mid-reply
        // (#1295), so the buffer must be unbounded - same as the other providers' streamText.
    }.buffer(Channel.UNLIMITED)

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported by the Gemini OAuth provider")
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        // Keep the diagnostic log line readable; the payload can be many KB of candidate text.
        const val PAYLOAD_LOG_LIMIT = 500
    }
}

private const val TAG = "GeminiProvider"

/**
 * Adapts [GoogleProvider.parseStreamCandidates]'s legacy [MessageChunk] shape (see that
 * function's doc for why it still exists) onto the app-wide [StreamChunk] event stream, so this
 * provider can keep consuming Cloud Code Assist's per-event candidate parsing while still
 * implementing the current [Provider] interface.
 *
 * Mirrors the merge rules the removed `List<UIMessage>.handleMessageChunk` used to apply: a part
 * joins the previous part of the same kind, or starts a new one - just expressed as
 * Start/Delta/End events instead of an eagerly merged [UIMessage]. One instance is stateful for
 * exactly one stream and must not be reused across calls.
 */
private class GeminiStreamChunkAdapter {
    private enum class OpenKind { TEXT, REASONING, IMAGE }

    private var openKind: OpenKind? = null
    private var openId: String = ""
    private var nextId = 0

    private fun startNew(kind: OpenKind): String {
        openKind = kind
        openId = "gemini-${nextId++}"
        return openId
    }

    fun translate(chunk: MessageChunk): List<StreamChunk> {
        val out = mutableListOf<StreamChunk>()
        val choice = chunk.choices.getOrNull(0)
        val delta = choice?.delta ?: choice?.message
        if (delta != null) {
            // Google never sends an explicit "thought finished" signal; infer it the same way
            // the removed merge logic did - a delta with no reasoning part at all closes
            // whatever reasoning run is currently open.
            val hasReasoning = delta.parts.any { it is UIMessagePart.Reasoning }
            if (openKind == OpenKind.REASONING && !hasReasoning && delta.parts.isNotEmpty()) {
                out += StreamChunk.ReasoningEnd(openId)
                openKind = null
            }
            delta.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        if (part.text.isNotEmpty()) {
                            val id = if (openKind == OpenKind.TEXT) openId else startNew(OpenKind.TEXT)
                            out += StreamChunk.TextDelta(id, part.text)
                        }
                    }

                    is UIMessagePart.Reasoning -> {
                        if (part.reasoning.isNotEmpty() || part.metadata != null) {
                            val id =
                                if (openKind == OpenKind.REASONING) openId else startNew(OpenKind.REASONING)
                            out += StreamChunk.ReasoningDelta(
                                id = id,
                                text = part.reasoning,
                                metadata = part.metadata,
                            )
                        }
                    }

                    is UIMessagePart.Image -> {
                        val isNew = openKind != OpenKind.IMAGE
                        val id = if (isNew) startNew(OpenKind.IMAGE) else openId
                        // parseMessagePart already builds a full data URL; StreamChunk.ImageSnapshot
                        // wants just the base64 payload and re-attaches its own prefix.
                        val base64 = part.url.substringAfter(',', part.url)
                        out += StreamChunk.ImageSnapshot(id = id, data = base64, metadata = part.metadata)
                    }

                    is UIMessagePart.Tool -> {
                        // GoogleProvider assigns a fresh random id per functionCall part and Gemini
                        // sends each call's name + args in a single event, so every Tool part here
                        // is already complete - no cross-event merge is needed.
                        openKind = null
                        out += StreamChunk.ToolCallStart(id = part.toolCallId, toolName = part.toolName)
                        out += StreamChunk.ToolCallDelta(id = part.toolCallId, inputDelta = part.input)
                    }

                    else -> Log.w(TAG, "translate: unsupported delta part $part")
                }
            }
            if (delta.annotations.isNotEmpty()) {
                out += StreamChunk.Annotations(delta.annotations)
            }
        }
        chunk.usage?.let { out += StreamChunk.Usage(it) }
        return out
    }

    /** Sent once, right before the underlying event source closes normally. */
    fun finish(): StreamChunk = StreamChunk.Finish()
}

// Errors are small; this only bounds a pathological body, unlike PAYLOAD_LOG_LIMIT above which
// bounds streamed candidate text. Caps the rendered error detail appended to the thrown message.
private const val ERROR_DETAIL_LOG_LIMIT = 2000

private fun parseErrorMessage(body: String?, json: Json): String? {
    if (body.isNullOrBlank()) return null
    return runCatching {
        val error = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: return null
        val code = error["code"]?.jsonPrimitive?.intOrNull
        val prefix = if (code != null) "Cloud Code Assist error ($code): $message" else message
        prefix + formatErrorDetail(error).take(ERROR_DETAIL_LOG_LIMIT)
    }.getOrNull()
}

// gemini-3.1-pro-high is advertised by fetchAvailableModels but Antigravity's
// v1internal streamGenerateContent endpoint rejects it as a model name at every
// reasoning level (400, no fieldViolations to name the field). Confirmed on-device
// 2026-08-12 and independently by five other projects; gemini-3.1-pro-low works fine on
// the same endpoint, so this is scoped to the exact id rather than a `-high` suffix rule
// (other suffixed models work). Re-test and drop this once upstream fixes it.
//
// internal rather than private: PreferencesStore's GeminiOAuth normalization branch also
// filters on this set, to evict copies already persisted from before this filter existed.
internal val DENIED_MODEL_IDS = setOf("gemini-3.1-pro-high")

/**
 * Parses the `fetchAvailableModels` response body's `models` object into the list of [Model]s the
 * provider offers, filtering out internal-only entries and [DENIED_MODEL_IDS]. An absent or empty
 * `models` object yields an empty list.
 */
private fun mapAvailableModels(body: String, json: Json): List<Model> {
    val models = json.parseToJsonElement(body).jsonObject["models"]?.jsonObject
        ?: return emptyList()
    return models.mapNotNull { (modelId, element) ->
        val item = element as? JsonObject ?: return@mapNotNull null
        if (item["isInternal"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
        if (modelId in DENIED_MODEL_IDS) return@mapNotNull null
        val supportsImages = item["supportsImages"]?.jsonPrimitive?.booleanOrNull == true
        Model(
            modelId = modelId,
            displayName = item["displayName"]?.jsonPrimitive?.contentOrNull ?: modelId,
            inputModalities = if (supportsImages) {
                listOf(Modality.TEXT, Modality.IMAGE)
            } else {
                listOf(Modality.TEXT)
            },
            abilities = buildList {
                add(ModelAbility.TOOL)
                if (item["supportsThinking"]?.jsonPrimitive?.booleanOrNull == true) {
                    add(ModelAbility.REASONING)
                }
            },
        )
    }
}

/**
 * Renders `error.status` and a flattened `error.details[]` for appending after the existing
 * `Cloud Code Assist error (code): message` prefix. `BadRequest` detail entries surface each
 * `fieldViolations[]` entry's `field` and `description` - that is the whole point, since that is
 * where Google names the invalid argument. Other detail entries render as their `@type` plus
 * whatever scalar fields they carry. Any unexpected shape (wrong types, missing keys) is skipped
 * rather than thrown, so a malformed body degrades to just the prefix.
 */
private fun formatErrorDetail(error: JsonObject): String {
    val status = (error["status"] as? JsonPrimitive)?.contentOrNull
    val details = (error["details"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.mapNotNull(::formatDetailEntry)
        .orEmpty()
    val parts = buildList {
        status?.let { add("status=$it") }
        addAll(details)
    }
    if (parts.isEmpty()) return ""
    return " (" + parts.joinToString("; ") + ")"
}

private fun formatDetailEntry(entry: JsonObject): String? {
    val violations = (entry["fieldViolations"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.mapNotNull { violation ->
            val field = (violation["field"] as? JsonPrimitive)?.contentOrNull
            val description = (violation["description"] as? JsonPrimitive)?.contentOrNull
            if (field == null && description == null) {
                null
            } else {
                "field=${field ?: "unknown"}, description=${description ?: "unknown"}"
            }
        }
    if (!violations.isNullOrEmpty()) {
        return violations.joinToString("; ")
    }
    val type = (entry["@type"] as? JsonPrimitive)?.contentOrNull
    val scalars = entry.entries
        .filter { it.key != "@type" && it.key != "fieldViolations" }
        .mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { "$key=$it" } }
    if (type == null && scalars.isEmpty()) return null
    return buildString {
        append(type ?: "unknown detail type")
        if (scalars.isNotEmpty()) {
            append(": ")
            append(scalars.joinToString(", "))
        }
    }
}

/**
 * Resolves what a `streamText` [EventSourceListener.onFailure] should close the SSE producer
 * with. Reading the error body ([readDetail], normally [stringSafe]) or parsing it
 * ([parseErrorMessage]) can itself throw - e.g. a truncated or aborted body - and OkHttp never
 * re-dispatches a signalled callback, so letting that escape here would strand the producer and
 * the collector would wait forever. Catching it and falling back to the underlying throwable (or
 * the read failure itself) guarantees the caller always has a cause to close with.
 */
private fun resolveStreamFailureCause(
    t: Throwable?,
    responseCode: Int?,
    json: Json,
    readDetail: () -> String?,
): Throwable {
    return try {
        val detail = readDetail()
        t ?: HttpException(
            parseErrorMessage(detail, json)
                ?: "Cloud Code Assist request failed: $responseCode $detail",
            statusCode = responseCode,
        )
    } catch (e: Throwable) {
        // android.util.Log is unmocked in JVM unit tests (throws instead of logging), so this
        // testable top-level function uses the Logging facade the rest of :app already relies on
        // for exactly that reason (e.g. ChatService.kt) rather than android.util.Log.
        Logging.log(TAG, "onFailure: failed to read error body, detail lost: ${e.javaClass.simpleName}: ${e.message}")
        t ?: e
    }
}

/**
 * Raise a thinking budget below Claude's floor up to that floor.
 *
 * Code Assist fronts Anthropic models as well as Gemini, and Anthropic rejects any request whose
 * thinking budget is under 1024 tokens. Gemini's own reasoning levels can ask for less (e.g.
 * [me.rerere.ai.core.ReasoningLevel.LOW] is 1000), so anything in 1..1023 is raised here; `0`
 * (reasoning off) and budgets already at or above the floor are left untouched, as is the
 * `thinkingLevel` string used by Gemini-3 models. Must run before
 * [raiseMaxTokensAboveThinkingBudget] so that function sees the clamped budget.
 */
private fun raiseThinkingBudgetToClaudeFloor(request: JsonObject): JsonObject {
    val config = request["generationConfig"] as? JsonObject ?: return request
    val thinkingConfig = config["thinkingConfig"] as? JsonObject ?: return request
    val budget = thinkingConfig["thinkingBudget"]?.jsonPrimitive?.intOrNull ?: return request
    if (budget !in 1..1023) return request
    val raisedThinkingConfig = JsonObject(
        thinkingConfig + ("thinkingBudget" to JsonPrimitive(CLAUDE_MIN_THINKING_BUDGET))
    )
    return JsonObject(request + ("generationConfig" to JsonObject(
        config + ("thinkingConfig" to raisedThinkingConfig)
    )))
}

// Anthropic's floor for `thinking.budget_tokens` on Claude models fronted by Code Assist.
private const val CLAUDE_MIN_THINKING_BUDGET = 1024

/**
 * Guarantee `maxOutputTokens` sits above the thinking budget.
 *
 * Code Assist fronts Anthropic models as well as Gemini, and those reject any request whose
 * thinking budget is not strictly below `max_tokens`. Gemini itself has no such rule, so the
 * shared wire builder does not enforce it and the ceiling is raised here instead. Leaving
 * `maxOutputTokens` unset is not safe either: the backend then applies a default that a high
 * reasoning level overshoots.
 */
private fun raiseMaxTokensAboveThinkingBudget(request: JsonObject): JsonObject {
    val config = request["generationConfig"] as? JsonObject ?: return request
    val budget = (config["thinkingConfig"] as? JsonObject)
        ?.get("thinkingBudget")?.jsonPrimitive?.intOrNull ?: return request
    if (budget <= 0) return request
    val maxTokens = config["maxOutputTokens"]?.jsonPrimitive?.intOrNull
    if (maxTokens != null && maxTokens > budget) return request
    val raised = JsonObject(
        config + ("maxOutputTokens" to JsonPrimitive(budget + THINKING_ANSWER_HEADROOM))
    )
    return JsonObject(request + ("generationConfig" to raised))
}

// Room for the answer itself once the thinking budget is spent.
private const val THINKING_ANSWER_HEADROOM = 8192
