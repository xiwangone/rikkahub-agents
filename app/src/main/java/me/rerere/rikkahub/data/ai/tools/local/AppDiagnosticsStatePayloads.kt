package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.ToolErrors
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findChatModelFor
import me.rerere.rikkahub.data.datastore.resolveAssistantFor
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import org.koin.java.KoinJavaComponent.getKoin
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar

/**
 * AI 自诊断/自管理工具（第一批，纯读，风险零）。
 *
 * 复用现有组件：
 *  - get_app_health: DoctorChecks.runAll() 的 8 类检查快照
 *  - read_app_logs:   AppLog 缓冲 + LogRedactor 脱敏
 *  - test_model:      提供商「测试连接」核心逻辑（非流式/流式/工具调用）
 */

// ---------- get_app_settings ----------

/**
 * 读取应用/助手/供应商配置摘要（纯读，零风险）。
 * - 助手：id/name/chatModelId/systemPrompt 长度/localTools 组数/enabledSkills 名单
 * - 供应商：id/name/enabled/builtIn/模型数（**不含任何 key/密钥**，AI 全程不见明文）
 * - 关键偏好：默认聊天模型/自动压缩开关/流式重试/工具输出限制等
 * 对齐规划 #6「配置真相」+ get_providers V1 读侧：AI 能看见自己配了什么，才能谈管理。
 */
internal suspend fun appSettingsPayload(settingsStore: SettingsStore): String {
        val settings = runCatching { settingsStore.settingsFlow.first() }.getOrNull()
            ?: return "{\"error\":\"settings_unavailable\"}"
        // 模型索引：把 chatModelId（UUID）解析成「提供商 / 模型名」，否则 AI 只看到一串 id，
        // 无法判断自己跑在哪个模型上、成本与能力如何。
        val modelIndex = settings.providers.flatMap { p -> p.models.map { p to it } }.associateBy { it.second.id }
        val out = buildJsonObject {
            put("assistants", buildJsonArray {
                settings.assistants.forEach { a ->
                    add(buildJsonObject {
                        put("id", a.id.toString())
                        put("name", a.name)
                        put("chat_model_id", a.chatModelId?.toString() ?: "inherit")
                        val resolved = a.chatModelId?.let { id -> modelIndex[id] }
                        put(
                            "chat_model",
                            resolved?.let { (p, m) -> "${p.name} / ${m.displayName.ifBlank { m.modelId }}" }
                                ?: "inherit",
                        )
                        put("provider", resolved?.first?.name ?: "inherit")
                        put("local_tool_groups", a.localTools.size)
                        put("enabled_skills", buildJsonArray { a.enabledSkills.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            })
            put("providers", buildJsonArray {
                settings.providers.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id.toString())
                        put("name", p.name)
                        put("enabled", p.enabled)
                        put("built_in", p.builtIn)
                        put("model_count", p.models.size)
                        // 绝不含 key/privateKey/password/token
                    })
                }
            })
            put("preferences", buildJsonObject {
                put("default_chat_model", settings.chatModelId.toString())
                put("execution_backend", settings.executionBackend)
                put("auto_compress_enabled", settings.autoCompressEnabled)
                put("auto_compress_threshold", settings.autoCompressThreshold)
                // 上下文窗口（单位 K，0 = 未填、改用模型上报值）。此前诊断面不暴露这个值，
                // 只看这条输出会误判成“没设窗口”。
                put("context_window_size", settings.contextWindowSize)
                put("stream_max_retries", settings.responseStreamMaxRetries)
                put("auto_retry_enabled", settings.enableAutoRetry)
                put("tool_output_enabled", settings.toolOutputEnabled)
                put("tool_output_max_chars", settings.toolOutputMaxChars)
                put("assistant_count", settings.assistants.size)
                put("provider_count", settings.providers.size)
            })
        }
    return out.toString()
}

// ---------- assistants ----------

/** 提示词默认预览长度（full=true 才给全文）：够判断"是不是同一版"，又不吃上下文。 */
private const val PROMPT_PREVIEW_CHARS = 1200

/**
 * 助手的**配置快照**（不含任何凭证值）。
 *
 * 存在意义：助手配置存在 App 私有存储里，沙箱/shell 侧读不到 —— "当前生效的系统提示词是什么、
 * 四份工具名单填了哪些、预算上限多少"原本只能靠用户手工粘贴。本 payload 把非敏感部分直接交给
 * AI：既能自查配置，也能据此把配置归档进知识库（补上"助手配置无版本化"的洞）。
 *
 * 参数：`assistant_id`（只看一个）、`full`（提示词全文，默认截断）、`limit`（条数上限）。
 *
 * 脱敏纪律：`custom_headers` / `custom_bodies` **只给键名与值长度**，绝不给值 —— 它们可能被
 * 用户填成明文 key。provider 侧 key 是 "$$凭证名" 引用，天然安全。
 */
internal suspend fun assistantsPayload(
    settingsStore: SettingsStore,
    params: JsonObject,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): String {
    val settings = runCatching { settingsStore.settingsFlow.first() }.getOrNull()
        ?: return "{\"error\":\"settings_unavailable\"}"
    val onlyId = params["assistant_id"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    val full = params["full"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true
    val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 200).coerceIn(1, 200)
    val current = settings.resolveAssistantFor(invocationContext.callerAssistantUuid).id
    val modelIndex = settings.providers.flatMap { p -> p.models.map { p to it } }.associateBy { it.second.id }
    val selected = settings.assistants
        .filter { onlyId.isEmpty() || it.id.toString() == onlyId }
        .take(limit)

    return buildJsonObject {
        put("current_assistant_id", current.toString())
        put("assistant_count", settings.assistants.size)
        put("shown", selected.size)
        put("prompt_full", full)
        put("assistants", buildJsonArray {
            selected.forEach { a ->
                val chatModel = a.chatModelId?.let { id -> modelIndex[id] }
                    ?.let { (p, m) -> "${p.name} / ${m.displayName.ifBlank { m.modelId }}" } ?: "inherit"
                add(assistantConfigJson(a, chatModel, a.id == current, full))
            }
        })
    }.toString()
}

/** 单个助手的**核心配置**（模型/采样/开关/提示词）；工具面与关联计数见 [assistantToolsJson]。 */
private fun assistantConfigJson(
    a: Assistant,
    chatModel: String,
    isCurrent: Boolean,
    full: Boolean,
): JsonObject = buildJsonObject {
    put("id", a.id.toString())
    put("is_current", isCurrent)
    put("name", a.name)
    put("avatar", when (val av = a.avatar) {
        is Avatar.Dummy -> "dummy"
        is Avatar.Emoji -> "emoji:${av.content}"
        is Avatar.Image -> "image"
    })
    put("system_prompt_chars", a.systemPrompt.length)
    put(
        "system_prompt",
        if (full || a.systemPrompt.length <= PROMPT_PREVIEW_CHARS) a.systemPrompt
        else a.systemPrompt.take(PROMPT_PREVIEW_CHARS) + "…(truncated; full=true for the full text)",
    )
    put("sub_agent_prompt_chars", a.subAgentSystemPrompt.length)
    if (full) put("sub_agent_system_prompt", a.subAgentSystemPrompt)
    put("chat_model_id", a.chatModelId?.toString() ?: "inherit")
    put("chat_model", chatModel)
    put("temperature", a.temperature?.toString() ?: "inherit")
    put("top_p", a.topP?.toString() ?: "inherit")
    put("frequency_penalty", a.frequencyPenalty?.toString() ?: "inherit")
    put("presence_penalty", a.presencePenalty?.toString() ?: "inherit")
    put("max_tokens", a.maxTokens?.toString() ?: "inherit")
    put("context_message_limit", a.contextMessageLimit)
    put("stream_output", a.streamOutput)
    put("reasoning_level", a.reasoningLevel.toString())
    put("enable_memory", a.enableMemory)
    put("use_global_memory", a.useGlobalMemory)
    put("enable_recent_chats_reference", a.enableRecentChatsReference)
    put("enable_web_search", a.enableWebSearch)
    put("enable_time_reminder", a.enableTimeReminder)
    put("token_budget_soft_cap", a.tokenBudgetSoftCap?.toString() ?: "none")
    put("token_budget_hard_cap", a.tokenBudgetHardCap?.toString() ?: "none")
    // 工具面与关联计数（buildJsonObject 没有 putAll，逐项并入）
    assistantToolsJson(a).forEach { (key, value) -> put(key, value) }
}

/** 单个助手的**工具面与关联计数**（名单逐个列出；关联项只给数量；header/body 值只给长度）。 */
private fun assistantToolsJson(a: Assistant): JsonObject = buildJsonObject {
    put("extra_cold_tools", buildJsonArray { a.extraColdTools.forEach { add(JsonPrimitive(it)) } })
    put("only_tools", buildJsonArray { a.onlyTools.forEach { add(JsonPrimitive(it)) } })
    put("compact_tools", buildJsonArray { a.toolOutputCompactTools.forEach { add(JsonPrimitive(it)) } })
    put("digest_tools", buildJsonArray { a.toolOutputDigestTools.forEach { add(JsonPrimitive(it)) } })
    put("digest_keywords", buildJsonArray { a.toolOutputDigestKeywords.forEach { add(JsonPrimitive(it)) } })
    put("enabled_skills", buildJsonArray { a.enabledSkills.forEach { add(JsonPrimitive(it)) } })
    put("local_tools", a.localTools.size)
    put("sub_agent_model_id", a.subAgentModelId?.toString() ?: "inherit")
    put("max_concurrent_sub_agents", a.maxConcurrentSubAgents)
    put("workspace_id", a.workspaceId?.toString() ?: "none")
    put("regexes", a.regexes.size)
    put("preset_messages", a.presetMessages.size)
    put("quick_messages", a.quickMessageIds.size)
    put("mode_injections", a.modeInjectionIds.size)
    put("lorebooks", a.lorebookIds.size)
    put("mcp_servers", a.mcpServers.size)
    put("tags", a.tags.size)
    put("has_background", a.background != null)
    put("background_opacity", a.backgroundOpacity.toString())
    put("use_gradient_background", a.useGradientBackground)
    put("message_template", a.messageTemplate)
    put("custom_headers", buildJsonArray {
        a.customHeaders.forEach { h ->
            add(buildJsonObject {
                put("name", h.name)
                put("value_chars", h.value.length)
            })
        }
    })
    put("custom_bodies", buildJsonArray {
        a.customBodies.forEach { b ->
            add(buildJsonObject {
                put("key", b.key)
                put("value_chars", b.value.toString().length)
            })
        }
    })
}

// ---------- conversations ----------

/**
 * conversation kind: 无 id 时列出当前助手最近会话; 携带 id 时导出该会话的消息文本。
 * 单条超 2000 字符截断、总量 40k 字符封顶——用于回溯历史会话现场(崩溃/截断排查)。
 */
/**
 * 单轮耗时（ms）：消息自带 `createdAt` / `finishedAt` —— **无需新增采集、不加列**。
 * 缺任一端（进行中/未收尾）返回 `null`，不猜。（注意：这是「一轮总耗时」，
 * 思考段/生成段的拆分才需写端打点，属后续项。）
 */
private fun turnDurationMs(m: UIMessage?): JsonElement {
    val created = m?.createdAt ?: return JsonNull
    val finished = m?.finishedAt ?: return JsonNull
    val tz = TimeZone.currentSystemDefault()
    return JsonPrimitive(finished.toInstant(tz).toEpochMilliseconds() - created.toInstant(tz).toEpochMilliseconds())
}

/**
 * 无 id 分支：列最近会话（含节点数 + 末条**已收尾** assistant 的模型与耗时）。
 *
 * 抽成独立函数是为了把 [conversationsPayload] 的圈复杂度压回 detekt 阈值（内联时 21 > 20）。
 */
private suspend fun recentConversationsJson(
    conversationRepo: ConversationRepository,
    settingsStore: SettingsStore,
    params: JsonObject,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): String {
    val settings = settingsStore.settingsFlow.first()
    val assistant = settings.resolveAssistantFor(invocationContext.callerAssistantUuid)
    val limit = params["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 50) ?: 20
    val list = conversationRepo.getRecentConversations(assistant.id, limit)
    return buildJsonObject {
        put("assistantId", assistant.id.toString())
        put("count", list.size)
        put(
            "conversations",
            JsonArray(
                list.map { c ->
                    buildJsonObject {
                        put("id", c.id.toString())
                        put("title", c.title)
                        put("pinned", c.isPinned)
                        put("updatedAt", c.updateAt.toString())
                        put("nodes", c.messageNodes.size)
                        // 尾部轻扫（≤3 节点）：避免大会话全量遍历；只要「最近一条已收尾 assistant」的模型与耗时
                        val tail = c.messageNodes.asReversed().take(3).flatMap { it.messages.asReversed() }
                        val lastAssistant = tail.firstOrNull { it.role == MessageRole.ASSISTANT && it.finishedAt != null }
                        put("lastModelUuid", lastAssistant?.modelId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        put(
                            "lastModelLabel",
                            modelLabel(settings, lastAssistant?.modelId)?.let { JsonPrimitive(it) } ?: JsonNull,
                        )
                        put("lastTurnMs", turnDurationMs(lastAssistant))
                    }
                },
            ),
        )
    }.toString()
}

internal suspend fun conversationsPayload(
    conversationRepo: ConversationRepository,
    settingsStore: SettingsStore,
    params: JsonObject,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): String {
    val settings = settingsStore.settingsFlow.first()
    val idRaw = params["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    // 无 id = 列最近会话（抽成独立函数：[conversationsPayload] 的圈复杂度已逼近 detekt 阈值 20）
    if (idRaw.isEmpty()) return recentConversationsJson(conversationRepo, settingsStore, params, invocationContext)
    val uuid = runCatching { kotlin.uuid.Uuid.parse(idRaw) }.getOrElse {
        return ToolErrors.envelopeFor(error = "invalid_argument", message = "invalid conversation id '$idRaw'", hint = "Check the parameter values and retry with corrected arguments.").toString()
    }
    val conversation = conversationRepo.getConversationById(uuid)
        ?: return ToolErrors.envelopeFor(error = "not_found", message = "conversation not found: $idRaw", hint = "Verify the name or id and retry.").toString()
    // compact=true：只回**结构**（role / finishReason / 部件数 / 工具名与审批状态），不带正文。
    // 用于「某条消息在不在、那个工具是什么状态」这类核对——否则只能拉整段正文（上万 token）。
    val compact =
        params["compact"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true
    var totalChars = 0
    val messagesJson = buildJsonArray {
        loop@ for ((index, node) in conversation.messageNodes.withIndex()) {
            for (m in node.messages) {
                val text = m.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                val tools = m.parts.filterIsInstance<UIMessagePart.Tool>()
                if (compact) {
                    if (text.isBlank() && tools.isEmpty()) continue
                    add(
                        buildJsonObject {
                            put("index", index)
                            put("role", m.role.name.lowercase())
                            put("finishReason", m.finishReason)
                            put("parts", m.parts.size)
                            put("chars", text.length)
                            if (tools.isNotEmpty()) {
                                put(
                                    "tools",
                                    JsonArray(
                                        tools.map { t ->
                                            buildJsonObject {
                                                put("name", t.toolName)
                                                put("state", t.approvalState::class.simpleName.orEmpty())
                                                put("executed", t.isExecuted)
                                                put("outputParts", t.output.size)
                                            }
                                        },
                                    ),
                                )
                            }
                        },
                    )
                    continue
                }
                if (text.isBlank()) continue
                if (totalChars > 40_000) break@loop
                val clipped = if (text.length > 2000) text.take(2000) + "…(truncated)" else text
                totalChars += clipped.length
                add(buildJsonObject {
                    put("index", index)
                    put("role", m.role.name.lowercase())
                    put("finishReason", m.finishReason)
                    put("text", clipped)
                })
            }
        }
    }
    // 四档口径（立项 §一·补 ③）：消息 / 回合(user) / 生成(assistant) / 工具调用；
    // 另给「出现过的模型 uuid」（回答“当时跑的是哪个模型”）+ 末轮耗时（消息自带时间戳，无需采集）。
    val allMessages = conversation.messageNodes.flatMap { it.messages }
    val lastAssistant = allMessages.lastOrNull { it.role == MessageRole.ASSISTANT && it.finishedAt != null }
    return buildJsonObject {
        put("id", conversation.id.toString())
        put("title", conversation.title)
        put("counts", turnCountsJson(allMessages))
        put(
            "modelUuids",
            JsonArray(allMessages.mapNotNull { it.modelId }.distinct().map { JsonPrimitive(it.toString()) }),
        )
        // 同一份去重列表的可读版：UUID 自己认不出是哪个模型，解析失败标 unresolved 而不是丢项。
        put(
            "modelLabels",
            JsonArray(
                allMessages.mapNotNull { it.modelId }.distinct()
                    .map { JsonPrimitive(modelLabel(settings, it) ?: "unresolved:$it") },
            ),
        )
        put("lastTurnMs", turnDurationMs(lastAssistant))
        put("messages", messagesJson)
        put("truncated", totalChars > 40_000)
    }.toString()
}

/**
 * 最近一轮生成的**自省信息**：生效参数 + 该轮 usage（含缓存命中与费用）。
 *
 * 存在意义：AI 需要知道「我此刻跑在哪个模型、什么参数下、这一轮花了多少 token、
 * 缓存命中多少、花了多少钱」，否则谈成本与自我诊断都是空的。
 *
 * 口径纪律：usage 由 provider 上报 —— **未上报时返回字符串 "unknown"，不用 0 冒充**，
 * 否则跨平台对比会得出错误结论（原生平台详细 / 兼容端点只回总量 / 部分代理不回）。
 */
internal suspend fun generationPayload(
    conversationRepo: ConversationRepository,
    settingsStore: SettingsStore,
    params: JsonObject,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): String {
    val settings = settingsStore.settingsFlow.first()
    val idRaw = params["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val conversation =
        resolveGenerationConversation(conversationRepo, settings, idRaw, invocationContext)
            ?: return ToolErrors.envelopeFor(error = "conversation_not_found", message = "conversation_not_found").toString()

    // 注意：当前这一轮往往已有一条 assistant 消息（记录工具调用），但它尚未收尾、usage 仍为空。
    // 若直接取「最后一条 assistant」，自省会永远读到 unknown —— 因此优先取最近一条**带 usage** 的，
    // 找不到才退回最后一条（并如实标注 usage_source）。
    val assistantMessages =
        conversation.messageNodes.asReversed()
            .flatMap { node -> node.messages.asReversed() }
            .filter { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
    val lastAssistant = assistantMessages.firstOrNull { it.usage != null } ?: assistantMessages.firstOrNull()

    // 两个口径必须分开报：`model` 原先取的是 assistant.chatModelId（助手默认），AI 自省时会
    // 误把默认当成本会话在跑的模型。现在 model = 会话实际生效（与生成链路同口径），
    // 默认值单列 assistant_default_model，并用 model_source 标明取自哪一级。
    val conversationAssistant = settings.resolveAssistantFor(conversation.assistantId)
    // 与生成链路同序同源（会话级 → 助手 → 全局），避免诊断口径与真实取值漂移。
    // 取值与来源判定抽到 resolveModelScope，以免把 generationPayload 的圈复杂度推过 detekt 阈值。
    val scope = resolveModelScope(settings, conversation.chatModelId, conversation.assistantId)
    val effectiveModelId = scope.modelId
    // 消息里实际出现过的模型（最硬证据）：会话中途换过模型时会多于一条。
    val observedModelIds =
        conversation.messageNodes.flatMap { it.messages }.mapNotNull { it.modelId }.distinct()

    return buildJsonObject {
        put("conversation_id", conversation.id.toString())
        put("conversation_title", conversation.title)
        put("assistant", conversationAssistant.name)
        put("model", effectiveModelId?.let { id -> modelLabel(settings, id) ?: "unresolved:$id" } ?: "unresolved")
        put("model_id", effectiveModelId?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
        put("model_source", scope.source)
        put(
            "assistant_default_model",
            conversationAssistant.chatModelId?.let { modelLabel(settings, it) ?: it.toString() } ?: "inherit",
        )
        put(
            "observed_models",
            JsonArray(observedModelIds.map { JsonPrimitive(modelLabel(settings, it) ?: "unresolved:$it") }),
        )
        put("settings", assistantSettingsJson(conversationAssistant))
        val lastTurn = lastTurnJsonOf(lastAssistant)
        if (lastTurn == null) {
            put("last_turn", JsonNull)
            put("note", "no assistant message in this conversation yet")
        } else {
            put("last_turn", lastTurn)
        }
    }.toString()
}

/**
 * 模型 UUID → `提供商 / 模型名`。解析不到时回 null，由调用方标注 `unresolved:<uuid>` ——
 * 不静默编造名称（模型被删除 / 配置换机器后 UUID 可能对不上）。
 */
internal fun modelLabel(settings: Settings, id: kotlin.uuid.Uuid?): String? {
    if (id == null) return null
    return settings.providers.firstNotNullOfOrNull { p ->
        p.models.firstOrNull { it.id == id }?.let { m -> "${p.name} / ${m.displayName.ifBlank { m.modelId }}" }
    }
}

/**
 * 生成参数（温度 / top_p / 上限 / 上下文窗口 / 推理档）—— 抽出来只为压 [generationPayload] 的圈复杂度。
 */
private fun assistantSettingsJson(assistant: Assistant): JsonObject = buildJsonObject {
    put("temperature", assistant.temperature?.let { JsonPrimitive(it) } ?: JsonNull)
    put("top_p", assistant.topP?.let { JsonPrimitive(it) } ?: JsonNull)
    put("max_tokens", assistant.maxTokens?.let { JsonPrimitive(it) } ?: JsonNull)
    put("context_message_limit", assistant.contextMessageLimit)
    put("reasoning_level", assistant.reasoningLevel.name)
}

/**
 * 会话实际生效的模型 + 来源（会话级 / 助手默认 / 全局）。
 *
 * 取值走 `findChatModelFor`（与生成链路同序）；来源需回推一级，否则无法解释「为什么是这个模型」。
 */
internal class ModelScope(val assistant: Assistant, val modelId: kotlin.uuid.Uuid?, val source: String)

internal fun resolveModelScope(
    settings: Settings,
    conversationChatModelId: kotlin.uuid.Uuid?,
    conversationAssistantId: kotlin.uuid.Uuid,
): ModelScope {
    val assistant = settings.resolveAssistantFor(conversationAssistantId)
    val perConversation = conversationChatModelId?.takeIf { assistant.allowPerConversationModel }
    val configuredId = perConversation ?: assistant.chatModelId ?: settings.chatModelId
    val resolvedId = settings.findChatModelFor(conversationChatModelId, conversationAssistantId)?.id
    // UUID 悬空（模型被删 / 提供商被移除）：findChatModelFor 返回 null，但配置值还在 ——
    // 保留 configuredId 并把来源标成 unresolved，否则调用方只能看到一个空值，无从判断是「没配」还是「配了但已失效」。
    val source = when {
        resolvedId == null -> "unresolved"
        perConversation != null -> "conversation"
        assistant.chatModelId != null -> "assistant"
        else -> "global"
    }
    return ModelScope(
        assistant = assistant,
        modelId = resolvedId ?: configuredId,
        source = source,
    )
}

/**
 * 四档口径（消息 / 回合 / 生成 / 工具调用）。
 *
 * 抽成独立函数是为了把 [conversationsPayload] 的圈复杂度压回 detekt 阈值内（内联时 21 > 20）。
 */

private fun turnCountsJson(messages: List<UIMessage>): JsonObject =
    buildJsonObject {
        put("messages", messages.size)
        put("userTurns", messages.count { it.role == MessageRole.USER })
        put("generations", messages.count { it.role == MessageRole.ASSISTANT })
        put("toolCalls", messages.sumOf { m -> m.parts.count { it is UIMessagePart.Tool } })
    }

/**
 * audit kind：凭证使用审计的**只读**查询 —— 目标是替掉“拷 218MB 私有库手查”。
 *
 * 只回**非敏感元数据**（凭证名 / caller / action / 次数 / 首次与末次时间 / 归属 id 前 8 位 / 来源），
 * **永不回凭证明文或密文**；机械取用（provider 取 key 等）已按聚合行去重计次
 * （见 `VaultAuditDefaults.ROLLUP_ACTIONS`），所以默认看到的就是降噪后的视图。
 */
internal suspend fun auditPayload(params: JsonObject): String {
    val credential = params["credential"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    val action = params["action"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    val windowMinutes = params["window_minutes"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 43_200) ?: 1_440
    val limit = params["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 200) ?: 50
    // min_count：过滤“只发生过一次”的长尾（聚合行 count = 窗口内次数），默认 1 = 不过滤
    val minCount = params["min_count"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 10_000) ?: 1
    val repository =
        runCatching { getKoin().get<CredentialVaultRepository>() }.getOrNull()
            ?: return ToolErrors.envelopeFor(error = "invalid_argument", message = "vault repository unavailable", hint = "Check the parameter values and retry with corrected arguments.").toString()
    val now = System.currentTimeMillis()
    // 排序：次数优先（聚合行在前），其次按“最近一次发生” —— 否则 50 条额度会被 count=1 的长尾占满
    val logs =
        repository.queryAudit(credential, action, now - windowMinutes * 60_000L, limit)
            .filter { it.count >= minCount }
            .sortedWith(
                compareByDescending { log: VaultAuditLogEntity -> log.count }
                    .thenByDescending { it.lastTsMs ?: it.tsMs },
            )
    return buildJsonObject {
        put("window_minutes", windowMinutes)
        put("min_count", minCount)
        put("count", logs.size)
        put("note", "aggregated rows; count = occurrences in the rollup window; values are never returned")
        put(
            "entries",
            JsonArray(
                logs.map { log ->
                    buildJsonObject {
                        put("credentialName", log.credentialName)
                        put("caller", log.caller)
                        put("action", log.action)
                        put("count", log.count)
                        put("tsMs", log.tsMs)
                        log.lastTsMs?.let { put("lastTsMs", it) }
                        put("ageMinutes", ((now - (log.lastTsMs ?: log.tsMs)) / 60_000).toInt())
                        log.conversationId?.let { put("conversationIdPrefix", it.take(8)) }
                        log.source?.let { put("source", it) }
                    }
                },
            ),
        )
    }.toString()
}

/**
 * models kind：列出 provider → 模型清单（uuid / modelId / 显示名 / 能力 / 是否被当前助手绑定 / 是否收藏）。
 *
 * 为什么：以前要回答“某会话当时跑的是哪个模型”只能拷 `rikka_hub` 手查。
 * **只读白名单字段** —— 本函数不读 apiKey / privateKey / baseUrl 等敏感或标识字段，
 * 靠“只写白名单”而不是“记得别写”（provider 配置主体是加密存储的，这里只取明文元数据）。
 */
private suspend fun resolveGenerationConversation(
    conversationRepo: ConversationRepository,
    settings: Settings,
    idRaw: String,
    invocationContext: ToolInvocationContext,
): me.rerere.rikkahub.data.model.Conversation? {
    if (idRaw.isNotBlank()) {
        val uuid = runCatching { kotlin.uuid.Uuid.parse(idRaw) }.getOrNull() ?: return null
        return runCatching { conversationRepo.getConversationById(uuid) }.getOrNull()
    }
    invocationContext.callerConversationUuid?.let { callerUuid ->
        runCatching { conversationRepo.getConversationById(callerUuid) }.getOrNull()?.let { return it }
    }
    val assistant = settings.resolveAssistantFor(invocationContext.callerAssistantUuid)
    return runCatching { conversationRepo.getRecentConversations(assistant.id, 1).firstOrNull() }.getOrNull()
}

internal suspend fun modelsPayload(
    settingsStore: SettingsStore,
    params: JsonObject,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): String {
    val settings = settingsStore.settingsFlow.first()
    val assistant = settings.resolveAssistantFor(invocationContext.callerAssistantUuid)
    val bound = assistant.chatModelId
    // 过滤：默认只回**启用**的 provider（全量 37 个 ≈ 59KB，会把上下文吃掉）；
    // 禁用的仅在 include_disabled=true 时给，或用 provider=名称/uuid 精确取。
    val providerFilter = params["provider"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    val includeDisabled =
        params["include_disabled"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true
    // 每个 provider 的模型条目上限（默认 30）：有的 provider 挂几百个模型，整段回会吃掉上下文。
    // 与 logs/usage 的 limit 同一套语义：默认摘要、按需展开；需要全量带 models_limit=0。
    val modelsLimit = (params["models_limit"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(0, 1000)
    val shown = settings.providers.filter { p ->
        (providerFilter.isEmpty() || p.name.equals(providerFilter, ignoreCase = true) || p.id.toString() == providerFilter) &&
            (includeDisabled || p.enabled)
    }
    return buildJsonObject {
        put("assistant", assistant.name)
        put("assistant_model_uuid", bound?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("favorite_model_uuids", JsonArray(settings.favoriteModels.map { JsonPrimitive(it.toString()) }))
        put("provider_count", settings.providers.size)
        put("providers_shown", shown.size)
        put(
            "providers",
            JsonArray(
                shown.map { p ->
                    val visibleModels = if (modelsLimit > 0) p.models.take(modelsLimit) else p.models
                    buildJsonObject {
                        put("id", p.id.toString())
                        put("name", p.name)
                        put("enabled", p.enabled)
                        put("type", p::class.simpleName.orEmpty())
                        put("builtIn", p.builtIn)
                        put(
                            "models",
                            JsonArray(
                                visibleModels.map { m ->
                                    buildJsonObject {
                                        put("uuid", m.id.toString())
                                        put("modelId", m.modelId)
                                        put("displayName", m.displayName)
                                        put("type", m.type.name)
                                        put("abilities", JsonArray(m.abilities.map { JsonPrimitive(it.name) }))
                                        put("bound_to_assistant", m.id == bound)
                                        put("favorite", m.id in settings.favoriteModels)
                                    }
                                },
                            ),
                        )
                        if (visibleModels.size < p.models.size) {
                            put("models_truncated", p.models.size - visibleModels.size)
                        }
                    }
                },
            ),
        )
    }.toString()
}

/** 单次请求的用量。命中率口径：OpenAI/DeepSeek 的 prompt_tokens 已包含缓存命中部分，
 *  故 ratio = cached / prompt（写成 cached/(prompt+cached) 会把 99.7% 显示成 49.9%）。
 *  Anthropic 系 input 不含 cache，此时比值会 >1，仅标口径而不给数值，避免跨平台误读。 */
private fun usageJsonOf(usage: me.rerere.ai.core.TokenUsage): JsonObject =
    buildJsonObject {
        put("prompt_tokens", usage.promptTokens)
        put("completion_tokens", usage.completionTokens)
        put("cached_tokens", usage.cachedTokens)
        put("total_tokens", usage.totalTokens)
        put("cost_usd", usage.cost?.let { JsonPrimitive(it) } ?: JsonNull)
        val ratio =
            if (usage.promptTokens > 0 && usage.cachedTokens in 1..usage.promptTokens) {
                (usage.cachedTokens * 1000.0 / usage.promptTokens).toInt() / 1000.0
            } else {
                null
            }
        put("cache_hit_ratio", ratio?.let { JsonPrimitive(it) } ?: JsonNull)
        put("cache_ratio_basis", "cached_tokens / prompt_tokens")
    }

/** 最近一轮：消息标识 + finish_reason + usage（未上报时标 unknown）；无消息返回 null。 */
private fun lastTurnJsonOf(message: UIMessage?): JsonObject? =
    message?.let { msg ->
        buildJsonObject {
            put("message_id", msg.id.toString())
            put("finish_reason", msg.finishReason ?: "unknown")
            val usage = msg.usage
            if (usage == null) {
                put("usage", "unknown")
                put("usage_source", "provider did not report")
            } else {
                put("usage", usageJsonOf(usage))
                put("usage_source", "provider reported")
            }
        }
    }
