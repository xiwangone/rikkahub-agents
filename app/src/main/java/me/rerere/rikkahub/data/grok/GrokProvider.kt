package me.rerere.rikkahub.data.grok

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class GrokProvider(
    private val context: Context,
    private val client: OkHttpClient,
    private val repository: GrokAccountRepository,
    private val json: Json,
) : Provider<ProviderSetting.Grok> {
    private val responseApi = ResponseAPI(client)
    private val eventSourceClient by lazy {
        client.newBuilder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.isSuccessful && response.header("Content-Type") == null) {
                    val body = response.body
                    response.newBuilder()
                        .header("Content-Type", "text/event-stream")
                        .body(
                            body.source().asResponseBody(
                                contentType = "text/event-stream".toMediaType(),
                                contentLength = body.contentLength(),
                            )
                        )
                        .build()
                } else {
                    response
                }
            }
            .build()
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Grok): List<Model> =
        withContext(Dispatchers.IO) {
            val account = repository.acquireAccount()
            val request = Request.Builder()
                .url("$API_BASE/models")
                .grokHeaders(account)
                .get()
                .build()
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                if (response.code == 401) repository.markInvalid(account.id)
                error("Failed to get Grok models: ${response.code} ${response.body.string()}")
            }
            val models = json.parseToJsonElement(response.body.string())
                .jsonObject["data"]?.jsonArray
                ?: return@withContext emptyList()
            models.mapNotNull { element ->
                val item = element.jsonObject
                val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Model(
                    modelId = id,
                    displayName = id,
                    inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                    abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
                )
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Grok,
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
        providerSetting: ProviderSetting.Grok,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val reasoningEffort = params.model.abilities
            .takeIf { it.contains(ModelAbility.REASONING) }
            ?.let { grokReasoningEffort(params.reasoningLevel) }
        val account = repository.acquireAccount()
        val syntheticSetting = ProviderSetting.OpenAI(
            id = providerSetting.id,
            enabled = providerSetting.enabled,
            name = providerSetting.name,
            models = providerSetting.models,
            baseUrl = API_BASE,
            useResponseApi = true,
        )
        val baseRequestBody = responseApi.createRequestBody(
            providerSetting = syntheticSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val requestBody = buildJsonObject {
            baseRequestBody.forEach { (key, value) -> put(key, value) }
            reasoningEffort?.let { effort ->
                put("reasoning", buildJsonObject {
                    put("effort", effort)
                })
            }
        }
        val request = Request.Builder()
            .url("$API_BASE/responses")
            .grokHeaders(account)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                if (data == "[DONE]") {
                    close()
                    return
                }
                val payload = runCatching {
                    json.parseToJsonElement(data).jsonObject
                }.getOrElse {
                    close(it)
                    return
                }
                val eventType = payload["type"]?.jsonPrimitive?.contentOrNull ?: type
                if (eventType in FINAL_RESPONSE_EVENTS) {
                    parseTokenUsage(payload)?.let { usage ->
                        trySend(
                            MessageChunk(
                                id = payload["response"]?.jsonObject
                                    ?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty(),
                                model = params.model.modelId,
                                choices = emptyList(),
                                usage = usage,
                            )
                        )
                    }
                    if (eventType == "response.failed") {
                        close(IllegalStateException(parseErrorMessage(payload) ?: "Grok request failed"))
                    } else {
                        close()
                    }
                    return
                }
                runCatching { responseApi.parseResponseDelta(payload) }
                    .onSuccess { chunk -> if (chunk != null) trySend(chunk) }
                    .onFailure { close(it) }
                if (eventType == "error") {
                    close(IllegalStateException(parseErrorMessage(payload) ?: "Grok request failed"))
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                response?.let {
                    if (it.code == 401) {
                        launch { repository.markInvalid(account.id) }
                    }
                }
                val detail = response
                    ?.takeUnless { it.isSuccessful }
                    ?.body
                    ?.string()
                close(t ?: IllegalStateException("Grok request failed: ${response?.code} $detail"))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }
        val eventSource = EventSources.createFactory(eventSourceClient).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported by the Grok provider")
    }

    private fun Request.Builder.grokHeaders(account: GrokAccount): Request.Builder {
        return header("Authorization", "Bearer ${account.accessToken}")
            .header("User-Agent", GrokOAuthManager.USER_AGENT)
    }

    private fun parseTokenUsage(payload: JsonObject): TokenUsage? {
        val usage = payload["usage"]?.jsonObject
            ?: payload["response"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val input = usage["input_tokens"]?.jsonPrimitive?.intOrNull
            ?: usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val output = usage["output_tokens"]?.jsonPrimitive?.intOrNull
            ?: usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        return TokenUsage(
            promptTokens = input,
            completionTokens = output,
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull ?: input + output,
            cachedTokens = usage["input_tokens_details"]?.jsonObject
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private fun parseErrorMessage(payload: JsonObject): String? {
        return runCatching {
            payload["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: payload["response"]?.jsonObject
                    ?.get("error")?.jsonObject
                    ?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private companion object {
        const val API_BASE = "https://api.x.ai/v1"
        val FINAL_RESPONSE_EVENTS = setOf(
            "response.completed",
            "response.done",
            "response.incomplete",
            "response.failed",
        )
    }
}

internal fun grokReasoningEffort(level: ReasoningLevel): String? {
    return when (level) {
        ReasoningLevel.AUTO -> null
        ReasoningLevel.OFF -> null
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH -> "high"
    }
}
