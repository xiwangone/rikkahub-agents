package me.rerere.ai.provider.providers.reasonix

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
 * Reasonix Provider — RikkaHub 直连 Reasonix serve（阶段5融合）。
 *
 * 架构：RikkaHub 作为 Reasonix 的「远程 UI」——会话由服务端管理（历史/压缩/checkpoint
 * 全部继承），每次对话开始 POST /new，streamText 只发增量（最后一条用户消息）→ POST /submit，
 * 然后监听 GET /events SSE 事件流并映射为 [StreamChunk]。
 *
 * SSE 事件映射（跟随上游 ai 模块重构后的事件模型）：
 * - text        → TextStart/TextDelta/TextEnd
 * - reasoning   → ReasoningStart/ReasoningDelta/ReasoningEnd
 * - tool_dispatch/tool_result → ServerToolStart/ServerToolEnd（服务端执行工具）
 * - usage       → Usage
 * - turn_done   → Finish（该 turn 响应结束；多 turn 自动任务继续流，下一 turn 重新开始事件）
 */
class ReasonixProvider(
    private val clientFactory: (ProviderSetting.Reasonix) -> ReasonixApi,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val cliExecutor: CliCommandExecutor? = null,
) : Provider<ProviderSetting.Reasonix> {

    constructor(cliExecutor: CliCommandExecutor? = null) : this(
        clientFactory = { setting ->
            ReasonixApi(
                baseUrl = setting.baseUrl,
                username = setting.username,
                password = setting.password,
                token = setting.token,
            )
        },
        cliExecutor = cliExecutor,
    )

    // custom 类型复用 OpenAI 兼容协议（baseUrl + token 作为 apiKey）
    private val chatCompletionsAPI = ChatCompletionsAPI(client = httpClient, keyRoulette = KeyRoulette.default())

    private fun api(setting: ProviderSetting.Reasonix): ReasonixApi = clientFactory(setting)

    override suspend fun listModels(providerSetting: ProviderSetting.Reasonix): List<Model> {
        val models = api(providerSetting).getModels()
        if (models.isEmpty()) {
            // 无法拉取时给一个默认占位（Reasonix 默认模型）
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
        providerSetting: ProviderSetting.Reasonix,
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
        error("Image generation is not supported by Reasonix")
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Reasonix,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        // 协议分发（方案 B）：reasonix 走专有 SSE；custom 走 OpenAI 兼容；cli 后续实现
        when (providerSetting.backendType) {
            "custom" -> {
                // 自定义 HTTP 后端：复用 OpenAI 兼容协议（baseUrl + token 作为 apiKey）
                val openaiSetting =
                    ProviderSetting.OpenAI(
                        baseUrl = providerSetting.baseUrl,
                        apiKey = providerSetting.token,
                    )
                chatCompletionsAPI.streamText(openaiSetting, messages, params).collect { emit(it) }
                return@flow
            }

            "cli" -> {
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
                return@flow
            }
        }

        val api = api(providerSetting)
        // 会话：当前无历史时新建会话（服务端管会话；本 Provider 单会话场景）
        val lastUserInput =
            messages.lastOrNull { it.role == MessageRole.USER }?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("") { it.text }
                ?: return@flow

        // 必须先 POST /new（新建会话）+ POST /submit（提交增量输入），
        // 服务端才会开始生成并向 /events 推送；否则两端 App 无限转圈。
        api.newSession()
        api.submit(lastUserInput)

        // 通过 SSE 建立连接后提交增量输入
        val sse =
            ReasonixSseClient(
                baseUrl = providerSetting.baseUrl,
                username = providerSetting.username,
                password = providerSetting.password,
                token = providerSetting.token,
            )
        val events = sse.connect()
        var usage: TokenUsage? = null
        var textStarted = false
        var reasoningStarted = false

        // reasonix serve 的 /events 是长连接（keep-alive），不会自然关流。
        // 多 turn 自动任务（工具调用循环）会在同一热流里连续发：
        //   turn_started → tool → tool_result → ... → turn_done → turn_started → ...
        // 收尾策略：内容事件刷新 idle；turn_done 后超过 TURN_DONE_IDLE_TIMEOUT_MS
        // 无任何内容事件 → 任务真正完成 → 结束 flow（UI 收尾，不再「working」）。
        // 实现：热流支持多次 first()（不重建连接），withTimeoutOrNull 提供超时。
        var turnDone = false

        while (true) {
            val event =
                kotlinx.coroutines.withTimeoutOrNull(
                    if (turnDone) TURN_DONE_IDLE_TIMEOUT_MS else Long.MAX_VALUE
                ) {
                    events.first()
                } ?: break

            val isContent =
                event.kind in
                    setOf(
                        "text", "reasoning", "tool_dispatch", "tool_result", "usage",
                        "message", "turn_started",
                    )
            if (isContent) {
                turnDone = false
            }

            when (event.kind) {
                "text" -> {
                    val t = event.text ?: continue
                    if (!textStarted) {
                        emit(StreamChunk.TextStart(id = TEXT_ID))
                        textStarted = true
                    }
                    emit(StreamChunk.TextDelta(id = TEXT_ID, text = t))
                }

                "reasoning" -> {
                    val r = event.reasoning ?: continue
                    if (!reasoningStarted) {
                        emit(StreamChunk.ReasoningStart(id = REASONING_ID))
                        reasoningStarted = true
                    }
                    emit(StreamChunk.ReasoningDelta(id = REASONING_ID, text = r))
                }

                "tool_dispatch" -> {
                    val tool = event.tool
                    if (tool != null) {
                        emit(
                            StreamChunk.ServerToolStart(
                                id = tool.id,
                                toolName = tool.name,
                                input = parseJsonOrNull(tool.args ?: tool.arguments),
                            )
                        )
                    }
                }

                "tool_result" -> {
                    val tool = event.tool
                    if (tool != null) {
                        val output = tool.output ?: tool.err ?: ""
                        emit(
                            StreamChunk.ServerToolEnd(
                                id = tool.id,
                                input = parseJsonOrNull(tool.args ?: tool.arguments),
                                output = parseJsonOrText(output),
                                status =
                                    if (tool.err.isNullOrBlank()) {
                                        ServerToolStatus.COMPLETED
                                    } else {
                                        ServerToolStatus.FAILED
                                    },
                            )
                        )
                    }
                }

                "usage" -> {
                    val u = event.usage
                    if (u != null) {
                        usage =
                            TokenUsage(
                                promptTokens = u.promptTokens.toInt(),
                                completionTokens = u.completionTokens.toInt(),
                                cachedTokens = u.cacheHitTokens.toInt(),
                                totalTokens = u.totalTokens.toInt(),
                                cost = u.costUsd,
                            )
                        emit(StreamChunk.Usage(usage))
                    }
                }

                "turn_done" -> {
                    turnDone = true
                    if (textStarted) {
                        emit(StreamChunk.TextEnd(id = TEXT_ID))
                        textStarted = false
                    }
                    if (reasoningStarted) {
                        emit(StreamChunk.ReasoningEnd(id = REASONING_ID))
                        reasoningStarted = false
                    }
                    emit(StreamChunk.Finish(finishReason = "stop"))
                    // 不结束：多 turn 自动任务可能马上开始下一轮。
                    // 下一轮 first() 带 TURN_DONE_IDLE_TIMEOUT_MS 超时：
                    // 有新内容则继续，无则 withTimeoutOrNull 返回 null → break 收尾。
                }

                else -> {
                    // notice/phase/approval_request/ask_request 等非内容事件：忽略
                }
            }
        }

        // events 流结束（超时 break 或连接关闭）未补收尾，视作最后一个 turn 完成
        if (textStarted) {
            emit(StreamChunk.TextEnd(id = TEXT_ID))
            textStarted = false
        }
        if (reasoningStarted) {
            emit(StreamChunk.ReasoningEnd(id = REASONING_ID))
            reasoningStarted = false
        }
        if (!turnDone) {
            emit(StreamChunk.Finish(finishReason = "stop"))
        }
    }

    private fun defaultModel(): Model =
        Model(
            modelId = "deepseek-v4-flash",
            displayName = "DeepSeek V4 Flash (default)",
            type = ModelType.CHAT,
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        )

    /** 把工具参数（JSON 字符串）解析为结构化 JsonElement；解析失败返回 null。 */
    private fun parseJsonOrNull(s: String?): JsonElement? =
        s?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.parseToJsonElement(it) }.getOrNull()
        }

    /** 工具输出优先解析为 JSON；自由文本回退为 JsonPrimitive。 */
    private fun parseJsonOrText(s: String): JsonElement =
        runCatching { json.parseToJsonElement(s) }.getOrElse { JsonPrimitive(s) }
}

private const val TURN_DONE_IDLE_TIMEOUT_MS = 15_000L
private const val TEXT_ID = "text"
private const val REASONING_ID = "reasoning"