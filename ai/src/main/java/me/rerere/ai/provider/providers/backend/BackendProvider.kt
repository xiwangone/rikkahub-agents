package me.rerere.ai.provider.providers.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.AskOption
import me.rerere.ai.ui.AskQuestion
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import java.util.UUID
import kotlin.uuid.Uuid

/**
 * Backend Provider — RikkaHub Agents 直连 Backend serve（阶段5融合）。
 *
 * 架构：RikkaHub Agents 作为 Backend 的「远程 UI」——会话由服务端管理（历史/压缩/checkpoint
 * 全部继承），每次对话开始 POST /new，streamText 只发增量（最后一条用户消息）→ POST /submit，
 * 然后监听 GET /events SSE 事件流并映射为 [StreamChunk]。
 *
 * SSE 事件映射（对齐 ai 模块的事件模型）：
 * - text        → TextStart/TextDelta/TextEnd
 * - reasoning   → ReasoningStart/ReasoningDelta/ReasoningEnd
 * - tool_dispatch/tool_result → ServerToolStart/ServerToolEnd（服务端执行工具）
 * - usage       → Usage
 * - turn_done   → Finish（该 turn 响应结束；多 turn 自动任务继续流，下一 turn 重新开始事件）
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

    private fun api(setting: ProviderSetting.Backend): BackendApi = clientFactory(setting)

    /**
     * 对话 → serve 会话路径 的映射（会话复用）。
     *
     * 键 = [me.rerere.ai.provider.TextGenerationParams.sessionId]（对话级，即 conversationId）：
     * 新对话（新 id）→ 无映射 → `POST /new`；同一对话续聊 → 命中映射 → `POST /resume {path}`。
     *
     * 本表是进程内缓存（同进程内读取最快）；未命中时回落到 [sessionPathStore]，
     * 由宿主决定是否跨重启保留 —— 宿主提供持久化实现时，App 重启后同一对话仍能续接，
     * 否则退化为新建会话。
     */
    private val sessionPaths = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 会话路径变更回调（供 UI/诊断读取，可选） */
    @Volatile
    var lastSessionPath: String? = null
        private set

    override suspend fun listModels(providerSetting: ProviderSetting.Backend): List<Model> {
        val models = api(providerSetting).getModels()
        if (models.isEmpty()) {
            // 无法拉取时给一个默认占位（Backend 默认模型）
            return listOf(defaultModel())
        }
        return models.mapIndexed { index, info ->
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
        // 收集首个 turn 的完整事件序列（Finish 终止收集）
        val chunks =
            streamText(providerSetting, messages, params)
                .takeWhile { it !is StreamChunk.Finish }
                .toList()

        val textSb = StringBuilder()
        val reasoningSb = StringBuilder()
        val tools = mutableListOf<UIMessagePart.ServerTool>()
        var usage: TokenUsage? = null

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
                        // ServerToolEnd 事件不含 toolName（工具名由 ServerToolStart 提供），
                        // 此兜底分支仅在 start 缺席时触发，展示名留空
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

                is StreamChunk.Usage -> usage = chunk.usage
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
            message = UIMessage(role = MessageRole.ASSISTANT, parts = parts, usage = usage),
            finishReason = "stop",
            usage = usage,
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
        // 协议分发（方案 B）：backend 走专有 SSE；custom 走 OpenAI 兼容；cli 后续实现
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

        val fullInput = buildFullInput(messages) ?: return@flow

        // 先建立 SSE 连接再 POST /new + /submit:连接就绪后提交,
        // 避免服务端早期事件(turn_started/usage 等)在订阅前发出而丢失。
        val sse =
            BackendSseClient(
                baseUrl = providerSetting.baseUrl,
                username = providerSetting.username,
                password = providerSetting.password,
                token = providerSetting.token,
                // 断流重连恢复时补拉 /history 差值，弥合断流窗口丢失的文本/推理
                historyLoader = { runBlocking { api.getHistory() } },
                statusLoader = { runBlocking { api.getStatus() } },
            )
        val events = sse.connect()

        // 必须先 POST /new(新建会话)+ POST /submit(提交增量输入),
        // 服务端才会开始生成并向 /events 推送;否则两端 App 无限转圈。
        // 会话复用：以**对话级 sessionId**（= conversationId）为键映射服务端会话路径。
        // 不用「首条用户消息 hashCode」：哈希碰撞、且首个 turn 之后键就漂移；
        // 对话 id 稳定 —— 同一对话重进自然 resume，新建对话（新 id）自然新建会话。
        val sessionKey = params.sessionId
        // 内存优先（同一进程内快）；未命中则查持久化（跨重启续接同一对话）
        val persistedPath = if (sessionKey != null) sessionPathStore.get(sessionKey) else null
        val existingPath = sessionKey?.let { sessionPaths[it] ?: persistedPath }
        if (sessionKey == null || existingPath.isNullOrBlank()) {
            api.newSession()
        } else {
            runCatching { api.resumeSession(existingPath) }.onFailure {
                android.util.Log.w("BackendProvider", "resume 会话失败，将新建", it)
                runCatching { api.newSession() }
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
                apiKey = providerSetting.token,
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

    /**
     * 上下文注入：直连模式下 serve 会话是"一次性"的，每回合 POST /new 新建、只提交增量输入，
     * 服务端没有历史 → 多轮对话失忆。把除最后一条用户消息外的全部历史（含系统提示）
     * 序列化为带角色标签的纯文本前缀，与本次输入一起 submit。
     * 无用户输入时返回 null。
     */
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
                // 工具结果不做纯文本化（体量大、结构化信息失真），首版跳过
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

    /**
     * 拉不到模型列表时的兜底占位：不绑定具体模型实现 —— 实际用哪个模型由服务端决定，
     * 这里只提供一个中性可选项，供配置页与消息记录使用。
     */
    private fun defaultModel(): Model =
        Model(
            modelId = "default",
            displayName = "Default (server-side)",
            type = ModelType.CHAT,
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        )
}