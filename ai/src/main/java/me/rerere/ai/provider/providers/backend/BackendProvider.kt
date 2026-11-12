package me.rerere.ai.provider.providers.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.common.log.AppLogger
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Backend Provider — RikkaHub Agents 直连 Backend serve（阶段5融合）。
 *
 * 架构：RikkaHub Agents 作为 Backend 的「远程 UI」——会话由服务端管理（历史/压缩/checkpoint
 * 全部继承），每次对话开始 POST /new，streamText 只发增量（最后一条用户消息）→ POST /submit，
 * 然后监听 GET /events SSE 事件流并映射为 [StreamChunk]。
 *
 * 并发说明：
 * - `backendType == "custom"` 走无状态 OpenAI 兼容 HTTP，天然支持多对话完全并发；
 * - `backendType == "backend"` 走有状态的单会话 `serve` 守护进程（`/new`、`/resume`、`/submit`、`/events`
 *   共享服务端当前活跃会话）。当多个对话同时发往同一 `baseUrl` 时，按 `baseUrl` 串行排队并自动
 *   resume 各自会话，避免第二个对话直接因 409 `/submit 失败` 报错或串流。
 */
class BackendProvider(
    private val clientFactory: (ProviderSetting.Backend) -> BackendApi,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val cliExecutor: CliCommandExecutor? = null,
    private val interactionHandler: BackendInteractionHandler = BackendInteractionHandler.NOOP,
    private val sessionPathStore: SessionPathStore = SessionPathStore.NOOP,
) : Provider<ProviderSetting.Backend> {

    constructor(
        cliExecutor: CliCommandExecutor? = null,
        interactionHandler: BackendInteractionHandler = BackendInteractionHandler.NOOP,
        sessionPathStore: SessionPathStore = SessionPathStore.NOOP,
    ) : this(
        clientFactory = { setting ->
            BackendApi(
                baseUrl = setting.baseUrl,
                username = setting.username,
                password = setting.password,
                token = setting.token,
            )
        },
        cliExecutor = cliExecutor,
        interactionHandler = interactionHandler,
        sessionPathStore = sessionPathStore,
    )

    // custom 类型复用 OpenAI 兼容协议（baseUrl + token 作为 apiKey）
    private val chatCompletionsAPI = ChatCompletionsAPI(client = httpClient, keyRoulette = KeyRoulette.default())

    private fun api(setting: ProviderSetting.Backend): BackendApi {
        if (setting.password.trimStart().startsWith("enc:v1:") || setting.token.trimStart().startsWith("enc:v1:")) {
            throw IllegalStateException(
                "Backend provider credential is in degraded state (ciphertext could not be decrypted; original ciphertext is preserved). Please re-enter the password or token in Settings.",
            )
        }
        return clientFactory(setting)
    }

    /**
     * 对话 → serve 会话路径 的映射（会话复用）。
     */
    private val sessionPaths = ConcurrentHashMap<String, String>()

    /**
     * 按 serve 端点（baseUrl）串行化有状态会话切换与事件消费，防止多对话并发时
     * 第二个对话在服务端正忙时 `/submit` 失败或 `/events` 串流。
     */
    private val endpointLocks = ConcurrentHashMap<String, Mutex>()

    private fun mutexForEndpoint(baseUrl: String): Mutex =
        endpointLocks.computeIfAbsent(baseUrl.trim().trimEnd('/')) { Mutex() }

    /** 会话路径变更回调（供 UI/诊断读取，可选） */
    @Volatile
    var lastSessionPath: String? = null
        private set

    override suspend fun listModels(providerSetting: ProviderSetting.Backend): List<Model> {
        val models = api(providerSetting).getModels()
        if (models.isEmpty()) {
            // 后端未返回可用模型时返回空，不再凭空造一个假模型：假模型选中后发请求才暴露服务端不认它。
            return emptyList()
        }
        return models.mapIndexed { _, info ->
            Model(
                modelId = info.ref.ifBlank { info.model },
                displayName = info.model.ifBlank { info.ref },
                id = Uuid.random(),
                type = ModelType.CHAT,
                abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
                contextLength = 1_000_000,
            )
        }
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Backend,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val chunks =
            streamText(providerSetting, messages, params)
                .takeWhile { it !is StreamChunk.Finish }
                .toList()

        val textSb = StringBuilder()
        val reasoningSb = StringBuilder()
        val tools = mutableListOf<UIMessagePart.ServerTool>()
        var usage: TokenUsage? = null
        var lastUsage: TokenUsage? = null
        var requestCount = 1

        for (chunk in chunks) {
            when (chunk) {
                is StreamChunk.TextDelta -> textSb.append(chunk.text)
                is StreamChunk.ReasoningDelta -> reasoningSb.append(chunk.text)
                is StreamChunk.ServerToolStart ->
                    tools +=
                        UIMessagePart.ServerTool(
                            toolCallId = chunk.id,
                            toolName = chunk.toolName,
                            input = chunk.input,
                            output = null,
                            status = ServerToolStatus.IN_PROGRESS,
                        )

                is StreamChunk.ServerToolEnd -> {
                    val index = tools.indexOfLast { it.toolCallId == chunk.id }
                    if (index >= 0) {
                        tools[index] =
                            tools[index].copy(
                                output = chunk.output,
                                status = chunk.status,
                            )
                    } else {
                        tools +=
                            UIMessagePart.ServerTool(
                                toolCallId = chunk.id,
                                toolName = "",
                                input = chunk.input,
                                output = chunk.output,
                                status = chunk.status,
                            )
                    }
                }

                is StreamChunk.Usage -> {
                    usage = chunk.usage
                    lastUsage = if (chunk.latestUsageReported) chunk.lastUsage ?: chunk.usage else null
                    requestCount = maxOf(requestCount, chunk.requestCount)
                }
                else -> {}
            }
        }

        val parts =
            buildList {
                if (reasoningSb.isNotEmpty()) {
                    add(UIMessagePart.Reasoning(reasoning = reasoningSb.toString()))
                }
                if (textSb.isNotEmpty()) {
                    add(UIMessagePart.Text(text = textSb.toString()))
                }
                addAll(tools)
            }

        return TextGenerationResult(
            id = UUID.randomUUID().toString(),
            model = params.model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = parts,
                usage = usage,
                lastUsage = lastUsage,
                stepCount = if (usage != null) requestCount else 0,
            ),
            finishReason = "stop",
            usage = usage,
            lastUsage = lastUsage,
            requestCount = requestCount,
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported by Backend")
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Backend,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        when (providerSetting.backendType) {
            "custom" -> {
                streamCustomBackend(providerSetting, messages, params)
                return@flow
            }

            "cli" -> {
                streamCliBackend(providerSetting, messages)
                return@flow
            }
        }

        val api = api(providerSetting)
        val fullInput = buildFullInput(messages)
            ?: throw IllegalStateException("Backend streamText：消息中没有用户文本，无法构造提交内容")
        val endpointLock = mutexForEndpoint(providerSetting.baseUrl)

        endpointLock.withLock {
            val sse =
                BackendSseClient(
                    baseUrl = providerSetting.baseUrl,
                    username = providerSetting.username,
                    password = providerSetting.password,
                    token = providerSetting.token,
                    historyLoader = { runBlocking { api.getHistory() } },
                    statusLoader = { runBlocking { api.getStatus() } },
                )
            try {
                val events = sse.connect()

                val sessionKey = params.sessionId
                val persistedPath = if (sessionKey != null) sessionPathStore.get(sessionKey) else null
                val existingPath = sessionKey?.let { sessionPaths[it] ?: persistedPath }
                if (sessionKey == null || existingPath.isNullOrBlank()) {
                    api.newSession()
                } else {
                    runCatching { api.resumeSession(existingPath) }.onFailure {
                        AppLogger.e("BackendProvider", "resume 会话失败，将新建", it)
                        runCatching { api.newSession() }
                            .onSuccess { created ->
                                if (!created) {
                                    AppLogger.w("BackendProvider", "resume 失败后新建会话失败：服务端未返回成功")
                                }
                            }
                            .onFailure {
                                AppLogger.e("BackendProvider", "resume 失败后新建会话失败", it)
                            }
                    }
                }
                if (!api.submit(fullInput)) {
                    throw java.io.IOException("Backend /submit 失败：网络不通或服务端非 2xx")
                }

                BackendEventProcessor(
                    providerSetting = providerSetting,
                    events = events,
                    interactionHandler = interactionHandler,
                    sessionContext = BackendSessionContext(
                        sessionKey = sessionKey,
                        sessionPaths = sessionPaths,
                        sessionPathStore = sessionPathStore,
                        onSessionPath = { lastSessionPath = it },
                    ),
                ).run { process() }
            } finally {
                sse.close()
            }
        }
    }

    /** 自定义 HTTP 后端：复用 OpenAI 兼容协议（baseUrl + token 作为 apiKey）。 */
    private suspend fun FlowCollector<StreamChunk>.streamCustomBackend(
        providerSetting: ProviderSetting.Backend,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ) {
        val openaiSetting =
            ProviderSetting.OpenAI(
                baseUrl = providerSetting.baseUrl,
                // 自定义后端可以不设鉴权：空 token 不能原样传给 KeyRoulette（空 key 会直接抛错），
                // 给一个占位值，服务端不校验时会被忽略，需要鉴权的服务端仍会返回 401。
                apiKey = providerSetting.token.ifBlank { "unused" },
            )
        chatCompletionsAPI.streamText(openaiSetting, messages, params).collect { emit(it) }
    }

    /** CLI 后端：拼命令执行，一次性返回输出。 */
    private suspend fun FlowCollector<StreamChunk>.streamCliBackend(
        providerSetting: ProviderSetting.Backend,
        messages: List<UIMessage>,
    ) {
        val executor = cliExecutor ?: error("CLI 执行器未注入")
        val prompt =
            messages.lastOrNull { it.role == MessageRole.USER }?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("") { it.text }
                ?: ""
        val command = providerSetting.cliCommand.replace("{prompt}", prompt)
        val output = executor.execute(command, prompt, providerSetting.cliSshHost.ifBlank { null })
        emit(StreamChunk.TextStart(id = "text"))
        emit(StreamChunk.TextDelta(id = "text", text = output))
        emit(StreamChunk.TextEnd(id = "text"))
        emit(StreamChunk.Finish(finishReason = "stop"))
    }

    private fun buildFullInput(messages: List<UIMessage>): String? {
        fun UIMessage.textContent(): String =
            parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

        val historyPrefix = StringBuilder()
        for (m in messages.dropLast(1)) {
            val text = m.textContent()
            if (text.isBlank()) continue
            when (m.role) {
                MessageRole.USER -> historyPrefix.append("[user] ")
                MessageRole.ASSISTANT -> historyPrefix.append("[assistant] ")
                MessageRole.SYSTEM -> historyPrefix.append("[system] ")
                MessageRole.TOOL -> continue
            }
            historyPrefix.append(text).append('\n')
        }
        if (historyPrefix.isNotEmpty()) {
            historyPrefix.append('\n')
        }
        val lastUserInput =
            messages.lastOrNull { it.role == MessageRole.USER }?.textContent()
                ?: return null
        return historyPrefix.append(lastUserInput).toString()
    }
}