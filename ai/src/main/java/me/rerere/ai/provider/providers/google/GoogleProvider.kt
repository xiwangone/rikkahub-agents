package me.rerere.ai.provider.providers.google


import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.google.vertex.ServiceAccountTokenProvider
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.ProviderKeyRefs
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.configureSessionHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.errorAsHttpException
import me.rerere.ai.util.json
import me.rerere.ai.util.logSafeMessage
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.redactSecrets
import me.rerere.ai.util.shortRequestClient
import me.rerere.ai.util.sanitizeForGeminiSchema
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.common.android.Logging
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.apache.commons.text.StringEscapeUtils
import kotlin.uuid.Uuid
import me.rerere.common.log.AppLogger
import me.rerere.common.log.SamplingGate

private const val TAG = "GoogleProvider"

/** listModels 失败日志里 error 详情的最大长度：服务端错误体可能很大，只记摘要。 */
private const val MAX_LIST_MODELS_FAILURE_DETAIL_CHARS = 500

/** listModels 翻页上限：单页 100 个，10 页即 1000 个模型，防服务端异常翻页死循环。 */
private const val MAX_LIST_MODELS_PAGES = 10

/**
 * Every category the generative-language API accepts on `safetySettings`.
 */
val GOOGLE_SAFETY_CATEGORIES = listOf(
    "HARM_CATEGORY_HARASSMENT",
    "HARM_CATEGORY_HATE_SPEECH",
    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
    "HARM_CATEGORY_DANGEROUS_CONTENT",
    "HARM_CATEGORY_CIVIC_INTEGRITY",
)

/**
 * What Cloud Code Assist accepts, which is the same list minus civic integrity: sending that one
 * fails the whole request with a 400 naming the offending element, even though the error text
 * lists the category as allowed.
 */
val CODE_ASSIST_SAFETY_CATEGORIES =
    GOOGLE_SAFETY_CATEGORIES - "HARM_CATEGORY_CIVIC_INTEGRITY"

class GoogleProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Google> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
    private val serviceAccountTokenProvider by lazy {
        ServiceAccountTokenProvider(client)
    }
    private val interactionsAPI = InteractionsAPI(client = client, keyRoulette = keyRoulette)

    // Interactions API is available on Gemini Developer API only; Vertex keeps generateContent.
    private fun ProviderSetting.Google.usesInteractionsApi() = useInteractionsApi && !vertexAI

    private fun buildUrl(providerSetting: ProviderSetting.Google, path: String): HttpUrl {
        return if (!providerSetting.vertexAI) {
            "${providerSetting.baseUrl}/$path".toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid Google baseUrl: '${providerSetting.baseUrl}'")
        } else if (providerSetting.useServiceAccount) {
            // projectId / location 是用户填的值，非法字符直接拼进 URL 会让 toHttpUrl() 抛错：
            // 先校验，报错时明确指出是哪个字段非法，与非 Vertex 分支的口径一致。
            "https://aiplatform.googleapis.com/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/$path".toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid Google Vertex projectId/location: '${providerSetting.projectId}' / '${providerSetting.location}'")
        } else {
            "https://aiplatform.googleapis.com/v1/$path".toHttpUrl()
        }
    }

    private suspend fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request
    ): Request {
        return if (providerSetting.vertexAI && providerSetting.useServiceAccount) {
            val rawPrivateKey = providerSetting.privateKey.trim()
            if (rawPrivateKey.startsWith("enc:v1:")) {
                throw IllegalStateException(
                    "Google Vertex AI service account privateKey is in degraded state (ciphertext could not be decrypted; original ciphertext is preserved). Please re-enter the private key in Settings.",
                )
            }
            val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
                // 支持写成 `$$凭证名`：由上层注入的解析器换取真值（未注入时原样）
                privateKeyPem = ProviderKeyRefs.expand(
                    StringEscapeUtils.unescapeJson(rawPrivateKey),
                ),
            )
            request.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            // API key travels in a header on both paths, never in the URL query string,
            // so it does not land in proxy logs or crash reports.
            request.newBuilder()
                .addHeader("x-goog-api-key", key)
                .build()
        }
    }

    /**
     * listModels 失败记一条 e：只记供应商名、脱敏后的 host（请求 URL 的 host，不含
     * path/query，key 不会进日志）、HTTP 状态码、翻页序号与截断后的错误摘要；
     * 完整错误体仍随 error() 抛给调用方展示，不重复进日志。
     */
    private fun logListModelsFailure(
        providerSetting: ProviderSetting.Google,
        request: Request,
        code: Int?,
        page: Int,
        detail: String,
    ) {
        AppLogger.e(
            TAG,
            "listModels failed: provider=${providerSetting.name} host=${request.url.host} " +
                "code=${code ?: "n/a"} page=$page " +
                "detail=${detail.take(MAX_LIST_MODELS_FAILURE_DETAIL_CHARS)}",
        )
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Google): List<Model> =
        withContext(Dispatchers.IO) {
            // 短请求单独收紧超时：共享 client 没有 callTimeout，/models 卡住时模型页最长转圈 10 分钟。
            val shortClient = client.shortRequestClient()
            val allModels = mutableListOf<Model>()
            var pageToken: String? = null
            // 翻页拉全：单页最多 100 个，nextPageToken 为空即最后一页；设上限防服务端异常翻页死循环。
            repeat(MAX_LIST_MODELS_PAGES) {
                val urlBuilder = buildUrl(providerSetting = providerSetting, path = "models?pageSize=100")
                    .newBuilder()
                pageToken?.let { urlBuilder.addQueryParameter("pageToken", it) }
                val request = transformRequest(
                    providerSetting = providerSetting,
                    request = Request.Builder()
                        .url(urlBuilder.build())
                        .get()
                        .build()
                )
                val response = shortClient.newCall(request).await()
                val body = response.body.string()
                if (!response.isSuccessful) {
                    logListModelsFailure(providerSetting, request, response.code, it + 1, body)
                    error("Failed to get models: ${response.code} $body")
                }
                // Full model-list body is large and unneeded outside debug:
                // release builds get the page size only.
                if (Logging.isDebugLoggingEnabled()) {
                    AppLogger.d(TAG, "listModels: $body")
                } else {
                    AppLogger.d(TAG, "listModels: ${body.length} bytes")
                }
                val bodyObject = json.parseToJsonElement(body).jsonObject
                // 成功但无 models 字段时才返回空列表（之前已拉到的页保留）。
                val models = bodyObject["models"]?.jsonArray ?: return@withContext allModels

                models.mapNotNullTo(allModels) {
                    val modelObject = it.jsonObject

                    // 忽略非chat/embedding模型；任一必需字段缺失时跳过该条目，
                    // 而不是让整次模型列表刷新失败
                    val supportedGenerationMethods =
                        (modelObject["supportedGenerationMethods"] as? JsonArray)
                            ?.mapNotNull { method -> (method as? JsonPrimitive)?.contentOrNull }
                            ?: return@mapNotNullTo null
                    if ("generateContent" !in supportedGenerationMethods && "embedContent" !in supportedGenerationMethods) {
                        return@mapNotNullTo null
                    }

                    val modelName =
                        (modelObject["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNullTo null
                    val displayName =
                        (modelObject["displayName"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNullTo null

                    Model(
                        modelId = modelName.substringAfter("/"),
                        displayName = displayName,
                        type = if ("generateContent" in supportedGenerationMethods) ModelType.CHAT else ModelType.EMBEDDING,
                        // 与其它通道一致：用注册表推断能力（视觉输入 / 工具 / 推理）
                        inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(
                            modelName.substringAfter("/"),
                        ),
                        outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(
                            modelName.substringAfter("/"),
                        ),
                        abilities = ModelRegistry.MODEL_ABILITIES.getData(
                            modelName.substringAfter("/"),
                        ),
                    )
                }
                pageToken = bodyObject["nextPageToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@withContext allModels
            }
            allModels
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        if (providerSetting.usesInteractionsApi()) {
            return@withContext interactionsAPI.generateText(providerSetting, messages, params)
        }

        val requestBody = buildCompletionRequestBody(messages, params)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:generateContent"
            } else {
                "models/${params.model.modelId}:generateContent"
            }
        )

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
                .configureSessionHeaders(url.toString(), params.sessionId)
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        val response = client.newCall(request).await()
        val bodyStr = response.body.string()
        if (!response.isSuccessful) {
            throw response.errorAsHttpException(bodyStr)
        }
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        val candidate = bodyJson["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("No candidates in response")
        // 安全拦截时服务端不给 content，只给 finishReason / promptFeedback.blockReason：
        // 先报拦截原因，而不是走到 parseGoogleMessage 里抛通用的 "No content"。
        val blockReason = (bodyJson["promptFeedback"] as? JsonObject)
            ?.get("blockReason")?.let { it as? JsonPrimitive }?.contentOrNull
        if (blockReason != null) {
            error("Prompt blocked: $blockReason")
        }
        if (candidate["content"] as? JsonObject == null) {
            val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
            error("No content in candidate (finishReason=$finishReason)")
        }
        TextGenerationResult(
            id = Uuid.random().toString(),
            model = params.model.modelId,
            message = parseGoogleMessage(candidate),
            finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull,
            usage = parseUsageMeta(bodyJson["usageMetadata"] as? JsonObject),
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = if (providerSetting.usesInteractionsApi()) {
        interactionsAPI.streamText(providerSetting, messages, params)
    } else {
        streamGenerateContent(providerSetting, messages, params)
    }

    private fun streamGenerateContent(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val requestBody = buildCompletionRequestBody(messages, params)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:streamGenerateContent"
            } else {
                "models/${params.model.modelId}:streamGenerateContent"
            }
        ).newBuilder().addQueryParameter("alt", "sse").build()

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
                .configureSessionHeaders(url.toString(), params.sessionId)
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        if (Logging.isDebugLoggingEnabled()) {
            AppLogger.i(TAG, "streamText: ${json.encodeToString(redactSecrets(requestBody))}")
        }

        val responseId = Uuid.random().toString()
        val decoder = GoogleStreamDecoder(responseId, params.model.modelId)

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            chunks.forEach { chunk ->
                trySend(chunk).onFailure { e ->
                    AppLogger.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        // 采样门：每个事件流一个，首条必记、其后每 100 条记 1 条。
        val eventSampling = SamplingGate(100)

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                // SSE event payloads carry model output / tool arguments / thinking
                // verbatim: only log the full text on debug builds; release builds
                // get length only so no user content reaches logcat.
                // Release 下每事件 1 条摘要行仍太多（一次长回答几百上千条）：采样
                // 只记首条与每 100 条 1 条，debug 全量记。
                if (Logging.isDebugLoggingEnabled()) {
                    AppLogger.d(TAG, "onEvent: $data")
                } else if (eventSampling.shouldLog()) {
                    AppLogger.d(TAG, "onEvent: len=${data.length}")
                }

                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: IllegalStateException) {
                    // Deliberate stream termination raised by the decoder itself
                    // (e.g. a prompt-feedback block reason), not a parse failure.
                    AppLogger.e(TAG, "Stream terminated: $data", e)
                    close(e)
                } catch (e: Throwable) {
                    // A single malformed/unparseable chunk must not escape this callback:
                    // an uncaught exception here propagates through OkHttp's SSE reader and
                    // aborts the whole stream instead of just skipping this one line.
                    AppLogger.w(TAG, "onEvent: skipping malformed chunk (${e.message})", e)
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                var exception = t
                var bodyParseFailed = false

                try {
                    if (t == null && response != null) {
                        val bodyStr = response.body.stringSafe()
                        if (!bodyStr.isNullOrEmpty()) {
                            val bodyElement = json.parseToJsonElement(bodyStr)
                            // 错误体只在 debug 下记全文：release 下它可能含全量错误体。
                            if (Logging.isDebugLoggingEnabled()) {
                                AppLogger.d(TAG, "onFailure: error body $bodyElement")
                            }
                            if (bodyElement is JsonObject) {
                                exception = Exception(
                                    bodyElement["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                                        ?: "unknown"
                                )
                            }
                        } else {
                            exception = Exception("Unknown error: ${response.code}")
                        }
                    }
                } catch (e: Throwable) {
                    // 解析失败时不记 body 原文：只在汇总行里留一句；exception 沿用
                    // 原有语义（解析异常本身成为流失败原因）。
                    bodyParseFailed = true
                    exception = e
                } finally {
                    // 一次流失败只留 1 条：HTTP 状态 + 最终异常摘要。汇总行必须在
                    // close() 之前落盘：close 会唤醒收集端，写在它之后汇总行可能
                    // 晚于流终止到达。
                    AppLogger.e(
                        TAG,
                        "onFailure: HTTP ${response?.code ?: "?"}: " +
                            "${exception?.logSafeMessage() ?: "Stream failed"}" +
                            if (bodyParseFailed) " (error body unparseable)" else "",
                        exception,
                    )
                    close(exception ?: Exception("Stream failed"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                AppLogger.d(TAG, "[onClosed] 连接已关闭")
                sendChunks(decoder.onClosed())
                close()
            }
        }

        val eventSource = EventSources.createFactory(client)
                .newEventSource(request, listener)

        awaitClose {
            AppLogger.d(TAG, "[awaitClose] 关闭eventSource")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    /**
     * Map one decoded `streamGenerateContent` payload onto a [MessageChunk], or null when it
     * carries no candidates yet.
     *
     * Public alongside [buildCompletionRequestBody] so the Cloud Code Assist transport can reuse
     * the Gemini wire format it wraps: that API takes the same request body under a `request` key
     * and returns the same candidates under a `response` key, so a second copy of the part
     * decoding would only give the two transports room to drift apart.
     */
    fun parseStreamCandidates(jsonData: JsonObject, model: Model): MessageChunk? {
        val candidates = jsonData["candidates"]?.jsonArray ?: return null
        if (candidates.isEmpty()) return null
        return MessageChunk(
            id = Uuid.random().toString(),
            model = model.modelId,
            choices = candidates.mapIndexed { index, candidate ->
                val candidateObj = candidate.jsonObject
                val content = candidateObj["content"]?.jsonObject
                val groundingMetadata = candidateObj["groundingMetadata"]?.jsonObject
                val finishReason = candidateObj["finishReason"]?.jsonPrimitive?.contentOrNull

                val message = content?.let {
                    parseGoogleMessage(buildJsonObject {
                        put("role", JsonPrimitive("model"))
                        put("content", it)
                        groundingMetadata?.let { groundingMetadata ->
                            put("groundingMetadata", groundingMetadata)
                        }
                    })
                }

                UIMessageChoice(
                    index = index,
                    delta = message,
                    message = null,
                    finishReason = finishReason
                )
            },
            usage = parseUsageMeta(jsonData["usageMetadata"] as? JsonObject)
        )
    }

    fun buildCompletionRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        safetyCategories: List<String> = GOOGLE_SAFETY_CATEGORIES,
    ): JsonObject = buildJsonObject {
        // System message if available
        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        if (systemMessage != null && !params.model.outputModalities.contains(Modality.IMAGE)) {
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put(
                            "text",
                            systemMessage.parts.filterIsInstance<UIMessagePart.Text>()
                                .joinToString { it.text })
                    })
                }
            })
        }

        // Generation config
        put("generationConfig", buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("topP", params.topP)
            if (params.maxTokens != null) put("maxOutputTokens", params.maxTokens)
            if (params.model.outputModalities.contains(Modality.IMAGE)) {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
            }
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", true)

                    val isGeminiPro =
                        params.model.modelId.contains(Regex("2\\.5.*pro", RegexOption.IGNORE_CASE))
                    val useThinkingLevel = ModelRegistry.GEMINI_3_SERIES.match(params.model.modelId) || ModelRegistry.GEMINI_4.match(params.model.modelId)

                    when (params.reasoningLevel) {
                        ReasoningLevel.AUTO -> {} // 自动模式，不设置参数

                        ReasoningLevel.OFF -> {
                            if (useThinkingLevel) {
                                put("thinkingLevel", "MINIMAL")
                            } else if (!isGeminiPro) {
                                put("thinkingBudget", 0)
                                put("includeThoughts", false)
                            }
                        }

                        else -> {
                            if (useThinkingLevel) {
                                when (params.reasoningLevel) {
                                    ReasoningLevel.LOW -> put("thinkingLevel", "LOW")
                                    ReasoningLevel.MEDIUM -> put("thinkingLevel", "MEDIUM")
                                    else -> put("thinkingLevel", "HIGH") // HIGH, XHIGH, MAX
                                }
                            } else {
                                put("thinkingBudget", params.reasoningLevel.budgetTokens)
                            }
                        }
                    }
                })
            }
        })

        // Contents (user messages)
        put(
            "contents",
            buildContents(messages)
        )

        // Tools — function tools and model built-in tools both live under the same
        // "tools" key. Writing them via two separate put("tools", ...) calls made the
        // second overwrite the first outright (JsonObjectBuilder.put replaces an
        // existing key), so built-in tools silently clobbered every function tool
        // whenever both were present. Build one array covering both.
        val hasFunctionTools = params.tools.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL)
        val hasBuiltInTools = params.model.tools.any { it == BuiltInTools.Search || it == BuiltInTools.UrlContext }
        if (hasFunctionTools || hasBuiltInTools) {
            put("tools", buildJsonArray {
                if (hasFunctionTools) {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            params.tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", JsonPrimitive(tool.name))
                                    put("description", JsonPrimitive(tool.description))
                                    val parameters = tool.parameters()
                                    if (parameters != null) {
                                        put(
                                            key = "parameters",
                                            element = json.encodeToJsonElement(parameters)
                                                .sanitizeForGeminiSchema()
                                        )
                                    }
                                })
                            }
                        })
                    })
                }
                if (hasBuiltInTools) {
                    params.model.tools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("googleSearch", buildJsonObject {})
                                })
                            }

                            BuiltInTools.UrlContext -> {
                                add(buildJsonObject {
                                    put("urlContext", buildJsonObject {})
                                })
                            }

                            else -> {}
                        }
                    }
                }
            })
        }
        if (hasFunctionTools && hasBuiltInTools) {
            put("toolConfig", buildJsonObject {
                put("includeServerSideToolInvocations", true)
            })
        }

        // Safety Settings
        putJsonArray("safetySettings") {
            safetyCategories.forEach { category ->
                add(buildJsonObject {
                    put("category", category)
                    put("threshold", "OFF")
                })
            }
        }
    }.mergeCustomBody(params.customBody)

    private fun buildContents(messages: List<UIMessage>): JsonArray {
        return buildJsonArray {
            messages
                .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
                .forEach { message ->
                    if (message.role == MessageRole.ASSISTANT) {
                        addModelMessage(message)
                    } else {
                        addGoogleUserMessage(message)
                    }
                }
        }
    }

    private fun JsonArrayBuilder.addModelMessage(message: UIMessage) {
        val modelParts = mutableListOf<JsonObject>()
        val responseParts = mutableListOf<JsonObject>()
        var carriedSig: String? = null

        fun flushModel() {
            if (modelParts.isEmpty()) return
            add(buildJsonObject {
                put("role", "model")
                putJsonArray("parts") { modelParts.forEach { add(it) } }
            })
            modelParts.clear()
        }

        fun flushResponses() {
            if (responseParts.isEmpty()) return
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") { responseParts.forEach { add(it) } }
            })
            responseParts.clear()
        }

        fun startNextModelContent() {
            if (responseParts.isNotEmpty()) {
                flushModel()
                flushResponses()
            }
        }

        for (part in message.parts) {
            when (part) {
                is UIMessagePart.Tool -> {
                    // A pending/unexecuted local tool is not a Google result and must
                    // not be replayed as an empty functionResponse.
                    if (!part.isExecuted) continue
                    // All assistant calls (client function and Google-hosted) belong to
                    // the model turn; their results follow in a user turn. Only this
                    // Tool type reaches the local tool execution pipeline.
                    val signature = part.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature
                    val effective = if (signature.isNullOrBlank() && !carriedSig.isNullOrBlank()) {
                        part.copy(metadata = GoogleThoughtMetadata(carriedSig).toMetadata())
                    } else part
                    modelParts.add(effective.toFunctionCallPart())
                    responseParts.add(effective.toFunctionResponsePart())
                    carriedSig = null
                }
                is UIMessagePart.ServerTool -> {
                    val metadata = part.metadataAs<ServerToolMetadata>()
                        ?.takeIf { it.protocol == ServerToolProtocol.GOOGLE_GENERATE_CONTENT }
                    // Replay wire parts verbatim. In particular, completed server tools
                    // remain ServerTool values and are never converted to local Tool calls.
                    metadata?.call?.let(modelParts::add)
                    metadata?.result?.let(responseParts::add)
                }
                is UIMessagePart.Reasoning -> {
                    val signature = part.metadata?.get("thoughtSignature")?.jsonPrimitive?.contentOrNull
                    if (!signature.isNullOrBlank()) carriedSig = signature
                    startNextModelContent()
                    part.toGooglePart()?.let(modelParts::add)
                }
                else -> {
                    startNextModelContent()
                    part.toGooglePart()?.let(modelParts::add)
                }
            }
        }
        flushModel()
        flushResponses()
    }
    private fun JsonArrayBuilder.addGoogleUserMessage(message: UIMessage) {
        val serverCalls = mutableListOf<JsonObject>()
        val userParts = mutableListOf<JsonObject>()
        message.parts.forEach { part ->
            if (part is UIMessagePart.ServerTool) {
                val metadata = part.metadataAs<ServerToolMetadata>()
                    ?.takeIf { it.protocol == ServerToolProtocol.GOOGLE_GENERATE_CONTENT }
                metadata?.call?.let(serverCalls::add)
                metadata?.result?.let(userParts::add)
            } else {
                part.toGooglePart()?.let(userParts::add)
            }
        }
        // A provider-owned server tool call is always a model part; its result is
        // always a user part, regardless of which UI message carried the metadata.
        if (serverCalls.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "model")
                putJsonArray("parts") { serverCalls.forEach { add(it) } }
            })
        }
        // Skip the turn entirely if every part was dropped (e.g. an unencodable
        // image) - an empty "parts" array is an invalid Google payload.
        if (userParts.isNotEmpty()) add(buildJsonObject {
            put("role", commonRoleToGoogleRole(message.role))
            putJsonArray("parts") { userParts.forEach { add(it) } }
        })
    }
    private fun UIMessagePart.toGooglePart(): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("text", text)
        }

        is UIMessagePart.Image -> {
            val result = encodeBase64(false)
            if (result.isFailure) {
                logDroppedPart("Image", url)
            }
            result.getOrNull()?.let { encoded ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", encoded.mimeType)
                        put("data", encoded.base64)
                    })
                    metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
                        put("thoughtSignature", it)
                    }
                }
            }
        }

        is UIMessagePart.Video -> {
            val result = encodeBase64(false)
            if (result.isFailure) {
                logDroppedPart("Video", url)
            }
            result.getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "video/mp4")
                        put("data", base64Data)
                    })
                }
            }
        }

        is UIMessagePart.Audio -> {
            val result = encodeBase64(false)
            if (result.isFailure) {
                logDroppedPart("Audio", url)
            }
            result.getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "audio/mp3")
                        put("data", base64Data)
                    })
                }
            }
        }

        else -> null
    }

    // Never log the payload itself - only the part type and the url scheme - so this stays safe
    // even though the caller passes a base64 payload's URL.
    private fun logDroppedPart(partType: String, url: String) {
        val scheme = url.substringBefore(':', missingDelimiterValue = "none")
        AppLogger.w(TAG, "toGooglePart: dropping unencodable $partType part, url scheme=$scheme")
    }

    private fun UIMessagePart.Tool.toFunctionCallPart() = buildJsonObject {
        put("functionCall", buildJsonObject {
            if (toolCallId.isNotBlank()) {
                put("id", toolCallId)
            }
            put("name", toolName)
            put("args", inputAsJson())
        })
        metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
            put("thoughtSignature", it)
        }
    }

    private fun UIMessagePart.Tool.toFunctionResponsePart() = buildJsonObject {
            put("functionResponse", buildJsonObject {
                if (toolCallId.isNotBlank()) {
                    put("id", toolCallId)
                }
                put("name", toolName)

                // 1. 拆分出纯文本部分
                val textParts = output.filterIsInstance<UIMessagePart.Text>()
                
                // 2. 提取所有的多模态(图片/视频/音频)，并直接转为 Google 要求的格式
                // 过滤出最终包含 inlineData 的数据块
                val mediaGoogleParts = output
                    .filter { it !is UIMessagePart.Text }
                    .mapNotNull { it.toGooglePart() }
                    .filter { it.containsKey("inlineData") } 

                // 3. 构建给模型看的结构化 response 节点
                put("response", buildJsonObject {
                    // 处理文本结果
                    if (textParts.isNotEmpty()) {
                        put(
                            "result", 
                            textParts.joinToString("\n") { it.text }
                        )
                    } else if (mediaGoogleParts.isEmpty()) {
                        // 如果工具啥都没返回，给个兜底成功状态
                        put("result", " ")
                    }

                    // 处理媒体数据（图片、音频、视频），打上 $ref 标签
                    mediaGoogleParts.forEachIndexed { index, _ ->
                        val refName = "media_ref_$index"
                        put(refName, buildJsonObject {
                            put("\$ref", refName)
                        })
                    }
                })

                // 4. 将真实的 Base64 多媒体数据挂载到 parts 中，并建立指针绑定
                if (mediaGoogleParts.isNotEmpty()) {
                    putJsonArray("parts") {
                        mediaGoogleParts.forEachIndexed { index, googlePart ->
                            val refName = "media_ref_$index"
                            val inlineData = googlePart["inlineData"]!!.jsonObject

                            add(buildJsonObject {
                                // 重新组装 inlineData，并在内部注入 displayName
                                put("inlineData", buildJsonObject {
                                    // 复制原有的 mimeType 和 data
                                    inlineData.forEach { (k, v) -> put(k, v) }
                                    // 添加能够让 $ref 认出它的唯一名称
                                    put("displayName", refName)
                                })
                                
                                // 保留可能存在的其他字段
                                googlePart.forEach { (k, v) ->
                                    if (k != "inlineData") put(k, v)
                                }
                            })
                        }
                    }
                }
            })
        }

    private fun parseUsageMeta(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) {
            return null
        }
        val promptTokens = jsonObject["promptTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val thoughtTokens = jsonObject["thoughtsTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedTokens = jsonObject["cachedContentTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val candidatesTokens = jsonObject["candidatesTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val totalTokens = jsonObject["totalTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = candidatesTokens + thoughtTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokens
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.Google) {
            "Expected Google provider setting"
        }

        val items = withContext(Dispatchers.IO) {
            val requestBody = buildJsonObject {
                putJsonArray("instances") {
                    add(buildJsonObject {
                        put("prompt", params.prompt)
                    })
                }
                putJsonObject("parameters") {
                    put("sampleCount", params.numOfImages)
                    put(
                        "aspectRatio", when (params.aspectRatio) {
                            ImageAspectRatio.SQUARE -> "1:1"
                            ImageAspectRatio.LANDSCAPE -> "16:9"
                            ImageAspectRatio.PORTRAIT -> "9:16"
                        }
                    )
                }
            }.mergeCustomBody(params.customBody)

            val url = buildUrl(
                providerSetting = providerSetting,
                path = if (providerSetting.vertexAI) {
                    "publishers/google/models/${params.model.modelId}:predict"
                } else {
                    "models/${params.model.modelId}:predict"
                }
            )

            val request = transformRequest(
                providerSetting = providerSetting,
                request = Request.Builder()
                    .url(url)
                    .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
                    .post(
                        json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                    )
                    .configureReferHeaders(providerSetting.baseUrl)
                    .build()
            )

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to generate image: ${response.code} ${response.body.string()}")
            }

            val bodyStr = response.body.string()
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

            val predictions = bodyJson["predictions"]?.jsonArray ?: error("No predictions in response")

            predictions.mapNotNull { prediction ->
                val predictionObj = prediction.jsonObject
                val bytesBase64Encoded = predictionObj["bytesBase64Encoded"]?.jsonPrimitive?.contentOrNull

                if (bytesBase64Encoded != null) {
                    ImageGenerationItem(
                        data = bytesBase64Encoded,
                        mimeType = "image/png"
                    )
                } else {
                    null
                }
            }
        }

        if (items.isEmpty()) error("No images in response (the model may have refused the prompt).")

        items.forEach { item ->
            emit(item)
        }
    }
}
