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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.util.toHeaders
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
                .geminiCliHeaders(account.accessToken)
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
        ).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            if (response.code == 401) repository.markInvalid(account.id)
            error("Failed to list Gemini models: ${response.code} $body")
        }
        val models = json.parseToJsonElement(body).jsonObject["models"]?.jsonObject
            ?: return@withContext emptyList()
        models.mapNotNull { (modelId, element) ->
            val item = element as? JsonObject ?: return@mapNotNull null
            if (item["isInternal"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
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

    override suspend fun generateText(
        providerSetting: ProviderSetting.GeminiOAuth,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        var collected = listOf(UIMessage.assistant(""))
        var usage: TokenUsage? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            collected = collected.handleMessageChunk(chunk, params.model)
            usage = chunk.usage ?: usage
        }
        return MessageChunk(
            id = "",
            model = params.model.modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = collected.last(),
                    finishReason = "stop",
                )
            ),
            usage = usage,
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.GeminiOAuth,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val account = repository.acquireAccount()
        val requestBody = buildJsonObject {
            put("project", account.projectId)
            put("model", params.model.modelId)
            put("request", wire.buildCompletionRequestBody(messages, params))
        }
        val request = Request.Builder()
            .url("${GeminiAccountRepository.CODE_ASSIST_ENDPOINT}/v1internal:streamGenerateContent?alt=sse")
            .headers(params.customHeaders.toHeaders())
            .geminiCliHeaders(account.accessToken, params.model.modelId)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(json.encodeToString(requestBody).toRequestBody(JSON_MEDIA_TYPE))
            .build()

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
                    trySend(chunk).onFailure { e ->
                        Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onEvent: failed to parse chunk", e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val detail = response
                    ?.takeUnless { it.isSuccessful }
                    ?.body
                    ?.string()
                if (response?.code == 401) {
                    launch { repository.markInvalid(account.id) }
                }
                close(
                    t ?: IllegalStateException(
                        parseErrorMessage(detail)
                            ?: "Cloud Code Assist request failed: ${response?.code} $detail"
                    )
                )
            }

            override fun onClosed(eventSource: EventSource) {
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

    private fun parseErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val error = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
            val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: return null
            val code = error["code"]?.jsonPrimitive?.intOrNull
            if (code != null) "Cloud Code Assist error ($code): $message" else message
        }.getOrNull()
    }

    private companion object {
        const val TAG = "GeminiProvider"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
