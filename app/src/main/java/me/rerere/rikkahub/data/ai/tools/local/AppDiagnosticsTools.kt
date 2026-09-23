package me.rerere.rikkahub.data.ai.tools.local

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import android.content.pm.PackageManager
import me.rerere.rikkahub.BuildConfig
import java.security.MessageDigest
import me.rerere.rikkahub.data.ai.tools.LocalToolCatalog
import me.rerere.rikkahub.data.ai.tools.SurfaceTier
import me.rerere.rikkahub.data.ai.tools.ToolSurfacePolicy
import me.rerere.rikkahub.data.ai.tools.ToolUsageTracker
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.RikkaHubApp
import org.koin.java.KoinJavaComponent.getKoin
import kotlinx.serialization.json.booleanOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.rikkahub.ui.pages.setting.doctor.DoctorChecks
import me.rerere.rikkahub.ui.pages.setting.doctor.Severity
import me.rerere.rikkahub.utils.LogRedactor
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * AI 自诊断/自管理工具（第一批，纯读，风险零）。
 *
 * 复用现有组件：
 *  - get_app_health: DoctorChecks.runAll() 的 8 类检查快照
 *  - read_app_logs:   AppLog 缓冲 + LogRedactor 脱敏
 *  - test_model:      提供商「测试连接」核心逻辑（非流式/流式/工具调用）
 */

// ---------- get_app_health ----------

/**
 * 对 [DoctorChecks.runAll] 的完整诊断快照做结构化输出。
 * 复用 Doctor 页全部检查项，返回 JSON：健康计数 + 每项 id/category/label/detail/severity。
 */
internal suspend fun appHealthPayload(
    doctorChecks: DoctorChecks,
    context: Context,
): String {
        val checks = doctorChecks.runAll()
        val ok = checks.count { it.severity == Severity.OK }
        val info = checks.count { it.severity == Severity.INFO }
        val warn = checks.count { it.severity == Severity.WARN }
        val fail = checks.count { it.severity == Severity.FAIL }
        val payload = buildJsonObject {
            put("ok", ok)
            put("info", info)
            put("warn", warn)
            put("fail", fail)
            put("total", checks.size)
            put("checks", JsonArray(checks.map { check ->
                buildJsonObject {
                    put("id", check.id)
                    put("category", check.category.name)
                    put("label", context.getString(check.labelRes))
                    put("detail", check.detail)
                    put("severity", check.severity.name)
                }
            }))
        }
    return payload.toString()
}

/**
 * keyword 支持**多个**（逗号 / 中文逗号 / 分号 / 空白分隔，命中任一即可）：排查时常要同时看
 * 几条链路（如 `ChatService,GenerationLoop`），分开调用既费往返又容易丢时间线。
 */
private fun parseKeywords(params: JsonObject): List<String> =
    params["keyword"]?.jsonPrimitive?.contentOrNull
        ?.split(',', '，', ';', ' ', '\t')
        ?.map { it.trim().lowercase(Locale.getDefault()) }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

// ---------- read_app_logs ----------

/**
 * 按级别 / 关键字 / 条数读取应用日志，返回前先过 [LogRedactor.maskText] 脱敏，
 * 防止崩溃堆栈泄露 API key / 连接串。
 */
internal fun appLogsPayload(context: Context, params: JsonObject): String {
        // 默认**排除 D 级**：逐 token / 流式分片是噪音大户（已单独缓冲），抓错误时会把有效行冲走；
        // 要看详细仍可显式 `level=D`。I/W/E 是诊断主线（消息收发 / 落库 / 取消 / 审批），默认可见。
        val level = params["level"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.uppercase(Locale.US)?.take(1)
        val keywords = parseKeywords(params)
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 50
        // summary=true：只回统计（level 分布 / Top tag / 时间范围 / 总数），不给原始行——
        // 让 AI 先看摘要再按 keyword 精准取行，避免一上来拉原始日志把上下文淹掉。
        val summaryOnly =
            params["summary"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true

        val matched = AppLog.getLogs()
            .filter { e ->
                if (level == null) e.level.toString() != "D" else e.level.toString() == level
            }
            .filter { e ->
                keywords.isEmpty() ||
                    keywords.any { k ->
                        e.tag.lowercase(Locale.getDefault()).contains(k) ||
                            e.message.lowercase(Locale.getDefault()).contains(k)
                    }
            }

        if (summaryOnly) {
            val levelCounts = matched.groupingBy { it.level.toString() }.eachCount()
            val topTags =
                matched.groupingBy { it.tag }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(10)
            return buildJsonObject {
                put("total", matched.size)
                put(
                    "levelCounts",
                    buildJsonObject { levelCounts.forEach { (k, v) -> put(k, v) } },
                )
                put(
                    "topTags",
                    buildJsonArray {
                        topTags.forEach { (tag, count) ->
                            add(buildJsonObject { put("tag", tag); put("count", count) })
                        }
                    },
                )
                if (matched.isNotEmpty()) {
                    val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                    put("firstAt", fmt.format(matched.first().timestamp))
                    put("lastAt", fmt.format(matched.last().timestamp))
                }
                put("hint", "仅统计；默认不含 D 级（要详细传 level=D）；要具体行时带 keyword/level 再调用（limit 默认 50）")
            }.toString()
        }

        val entries = matched.take(limit.coerceIn(1, 500))

        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val raw = entries.joinToString("\n") { e ->
            "${fmt.format(e.timestamp)} ${e.level} ${e.tag}: ${e.message}"
        }
        // 头部给「命中多少 / 实际回多少」+ 当前筛选口径：截断不再靠猜，也不会把
        // 「没匹配」与「被 limit 截掉」混为一谈。
        val header = buildString {
            append("[app logs] matched=${matched.size} shown=${entries.size}")
            append(" · level=${level ?: "default(I/W/E; use level=D for verbose)"}")
            if (keywords.isNotEmpty()) append(" · keyword=${keywords.joinToString("|")}")
            if (entries.size < matched.size) append(" · truncated: narrow with keyword/level/limit")
            append('\n')
        }
    return LogRedactor.maskText(header + raw)
}

// ---------- read_request_logs ----------

/**
 * 读取 HTTP 请求/响应摘要日志（common Logging 的 RequestLog，纯内存 100 条），
 * 返回前过 [LogRedactor.maskText] 脱敏。用于排查 API 调用失败/限流/端点错误等
 * 一手证据——read_app_logs 只覆盖 AppLog，请求日志是唯一能看到实际请求结果的入口。
 */
internal fun requestLogsPayload(context: Context, params: JsonObject): String {
        val keywords = parseKeywords(params)
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 50

        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val entries = Logging.getRequestLogs()
            .filter { e ->
                keywords.isEmpty() || keywords.any { k ->
                    e.url.lowercase(Locale.getDefault()).contains(k) ||
                        (e.error?.lowercase(Locale.getDefault())?.contains(k) == true)
                }
            }
            .take(limit.coerceIn(1, 100))

        val raw = entries.joinToString("\n") { e ->
            "${fmt.format(e.timestamp)} [HTTP] ${e.method} ${e.url} code=${e.responseCode ?: "-"} dur=${e.durationMs ?: "-"}ms err=${e.error ?: "-"}"
        }
    return LogRedactor.maskText(raw)
}

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

// ---------- test_model ----------

private val TEST_MODEL_CACHE = ConcurrentHashMap<String, String>()

/**
 * 对指定 provider + model 跑探测（非流式 / 流式 / 工具调用）。
 * 复用「提供商编辑页测试连接」核心逻辑；结果按「端点+模型」缓存，免重复现测。
 * 并发约束：单请求 max_tokens 50 + 10s 超时。
 */
fun testModelTool(
    providerManager: ProviderManager,
    settingsStore: SettingsStore,
    context: Context,
): Tool = Tool(
    name = "test_model",
    description = """
        Test a model endpoint (provider + model id): non-streaming, streaming, and tool-call support. Cached; force=true bypasses cache. Use to verify a provider works before relying on it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("provider", buildJsonObject {
                    put("type", "string")
                    put("description", "Provider name, e.g. 'OpenAI' / 'Google' / 'Claude'. Case-insensitive.")
                })
                put("model_id", buildJsonObject {
                    put("type", "string")
                    put("description", "The model id to test. Omit to test the provider's first CHAT model.")
                })
                put("force", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Bypass the result cache for this call. Default false.")
                })
            },
            required = listOf("provider"),
        )
    },
    execute = {
        val params = it.jsonObject
        val providerName = params["provider"]?.jsonPrimitive?.contentOrNull ?: error("provider is required")
        val modelId = params["model_id"]?.jsonPrimitive?.contentOrNull
        val force = params["force"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        val settings = settingsStore.settingsFlow.first()
        val providerSetting = settings.providers.firstOrNull { p ->
            p.name.equals(providerName, ignoreCase = true) || p.id.toString().equals(providerName, ignoreCase = true)
        } ?: error("Provider not found: $providerName")

        val model = providerSetting.models.firstOrNull { m ->
            modelId == null || m.modelId == modelId || m.id.toString() == modelId || m.displayName.equals(modelId, ignoreCase = true)
        } ?: error("Model not found in provider $providerName${modelId?.let { " ($it)" } ?: ""}")

        val cacheKey = "${providerSetting.id}|${model.modelId}"
        if (!force) {
            TEST_MODEL_CACHE[cacheKey]?.let { cached ->
                return@Tool listOf(UIMessagePart.Text(cached))
            }
        }

        val provider = providerManager.getProviderByType(providerSetting)
        val results = probeProvider(provider, providerSetting, model)
        val payload = buildJsonObject {
            put("provider", providerName)
            put("model", model.modelId)
            put("non_streaming", probeToJson(results.nonStreaming))
            put("streaming", probeToJson(results.streaming))
            put("tool_call", probeToJson(results.toolCall))
        }.toString()
        TEST_MODEL_CACHE[cacheKey] = payload
        listOf(UIMessagePart.Text(payload))
    },
)

// ---- test_model 内部辅助 ----

private data class ProbeResult(val ok: Boolean, val summary: String, val error: String? = null)

private data class ProviderProbeResults(
    val nonStreaming: ProbeResult,
    val streaming: ProbeResult,
    val toolCall: ProbeResult,
)

private fun probeToJson(r: ProbeResult): JsonObject = buildJsonObject {
    put("ok", r.ok)
    put("summary", r.summary)
    if (r.error != null) put("error", r.error)
}

private suspend fun <T : ProviderSetting> probeProvider(
    provider: Provider<T>,
    providerSetting: T,
    model: Model,
): ProviderProbeResults {
    // Non-streaming
    val nonStreaming: ProbeResult = runCatching {
        withTimeout(10_000) {
            provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system("You are a helpful assistant"),
                    UIMessage.user("hello"),
                ),
                params = TextGenerationParams(
                    model = model,
                    maxTokens = 50,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
        }
        ProbeResult(ok = true, summary = "ok")
    }.getOrElse { ProbeResult(ok = false, summary = "failed", error = it.message) }

    // Streaming
    val streaming: ProbeResult = runCatching {
        withTimeout(10_000) {
            var received = 0L
            provider.streamText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system("You are a helpful assistant"),
                    UIMessage.user("hello"),
                ),
                params = TextGenerationParams(
                    model = model,
                    maxTokens = 50,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            ).collect { chunk ->
                if (chunk is StreamChunk.TextDelta) received += chunk.text.length
            }
            if (received > 0) ProbeResult(ok = true, summary = "ok ($received chars)")
            else ProbeResult(ok = true, summary = "ok (empty stream)")
        }
    }.getOrElse { ProbeResult(ok = false, summary = "failed", error = it.message) }

    // Tool call
    val toolCall: ProbeResult = runCatching {
        withTimeout(10_000) {
            val testTool = Tool(
                name = "get_current_time",
                description = "Get the current date and time.",
                execute = { emptyList() },
            )
            val result = provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system("You are a helpful assistant"),
                    UIMessage.user("Use the get_current_time tool."),
                ),
                params = TextGenerationParams(
                    model = model,
                    maxTokens = 50,
                    tools = listOf(testTool),
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
            val toolCall = result.message.parts.filterIsInstance<UIMessagePart.Tool>().firstOrNull()
            if (toolCall != null) ProbeResult(ok = true, summary = "tool called: ${toolCall.toolName}")
            else ProbeResult(ok = true, summary = "no tool call (may not support tools)")
        }
    }.getOrElse { ProbeResult(ok = false, summary = "failed", error = it.message) }

    return ProviderProbeResults(nonStreaming, streaming, toolCall)
}


// ---------- read_crash_snapshot ----------

/**
 * 读取崩溃快照（主线程未捕获异常的现场）：堆栈 + AppLog 尾部 + 请求尾部 + 生命周期尾部。
 *
 * 快照保留最近 3 次（crash-latest / crash-1 / crash-2），便于回溯复发问题；文件由
 * CrashHandler 在崩溃时同步写出，因此即使进程随即退出也可读。
 */
internal fun crashSnapshotPayload(context: Context, params: JsonObject): String {
        val which = params["which"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.lowercase(Locale.US)?.takeIf { s -> s.isNotEmpty() } ?: "latest"
        val dir = context.getDir("crash", Context.MODE_PRIVATE)
        // 快照按时间归档（crash-<时间戳>.txt），取修改时间最新的几份；which=latest/1/2 依次新→旧
        val snapshots = dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }?.toList().orEmpty()
        val file = when (which) {
            "1" -> snapshots.getOrNull(1)
            "2" -> snapshots.getOrNull(2)
            else -> snapshots.firstOrNull()
        }
        val text =
            file?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrDefault("") } ?: ""
        val available = snapshots.take(6).joinToString(", ") { it.name }
        val header = "[${file?.name ?: "-"}] available snapshots: ${available.ifEmpty { "(none)" }}"
        val body = text.take(20_000)
        // 快照写入时已脱敏，这里再兜底一次（旧快照或异常路径可能未覆盖）
        val out = if (body.isBlank()) "$header\n(no crash snapshot)" else "$header\n$body"
    return LogRedactor.maskText(out)
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
): String {
    val settings = settingsStore.settingsFlow.first()
    val assistant = settings.getCurrentAssistant()
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
): String {
    val idRaw = params["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    // 无 id = 列最近会话（抽成独立函数：[conversationsPayload] 的圈复杂度已逼近 detekt 阈值 20）
    if (idRaw.isEmpty()) return recentConversationsJson(conversationRepo, settingsStore, params)
    val uuid = runCatching { kotlin.uuid.Uuid.parse(idRaw) }.getOrElse {
        return buildJsonObject { put("error", "invalid conversation id '$idRaw'") }.toString()
    }
    val conversation = conversationRepo.getConversationById(uuid)
        ?: return buildJsonObject { put("error", "conversation not found: $idRaw") }.toString()
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
        put("lastTurnMs", turnDurationMs(lastAssistant))
        put("messages", messagesJson)
        put("truncated", totalChars > 40_000)
    }.toString()
}

// ---------- perf ----------

/**
 * perf kind: 进程运行时长 / 堆占用 / 线程数。启动时刻由 Application 进程级记录（见 RikkaHubApp）。
 */
internal fun perfPayload(context: Context): String {
    val runtime = Runtime.getRuntime()
    val uptimeMs = android.os.SystemClock.elapsedRealtime() - RikkaHubApp.processStartElapsedMs
    return buildJsonObject {
        put("uptimeMinutes", uptimeMs / 60_000)
        put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576)
        put("heapMaxMb", runtime.maxMemory() / 1_048_576)
        put("activeThreads", Thread.activeCount())
    }.toString()
}

// ---------- read_lifecycle_logs ----------

/**
 * 读取进程生命周期记录：每次进程启动的原因（正常启动 / 疑似被系统杀死 / 崩溃重启）、
 * 前台后台切换、以及内存回收级别（TRIM_UI_HIDDEN 等）。
 *
 * 这是判断「退到后台再回来像重启、会话历史不见」类问题的直接证据：若日志里出现
 * PROCESS_START 且原因为「疑似被系统杀死」，说明是进程级回收而非界面问题。
 */
internal fun lifecycleLogsPayload(context: Context, params: JsonObject): String {
        val lines = (params["lines"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 500)
        val raw =
            me.rerere.rikkahub.data.log.FileLogSink.recentLines(
                me.rerere.rikkahub.data.log.FileLogSink.KIND_LIFECYCLE,
                lines,
            )
    return if (raw.isBlank()) "(no lifecycle records yet)" else raw
}

// ---------- get_build_info ----------

/**
 * 当前安装包的构建身份：版本号、是否 debug、安装/更新时间、签名指纹 SHA-256。
 * 用于 AI 在测试前确认"测的是哪个包"，以及排查"改了没生效"（装错包 / 进程跑旧码）。
 */
@SuppressLint("PackageManagerGetSignatures")
internal fun buildInfoPayload(context: Context): String {
            val pm = context.packageManager
            val packageName = context.packageName
            val info =
                runCatching { pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val signerSha256 =
                runCatching {
                    val cert = info?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                    cert?.let { bytes ->
                        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b) }
                    }
                }.getOrNull()
            val payload =
                buildJsonObject {
                    put("packageName", packageName)
                    put("versionName", BuildConfig.VERSION_NAME)
                    put("versionCode", BuildConfig.VERSION_CODE)
                    put("isDebugBuild", BuildConfig.DEBUG)
                    put("firstInstallTime", info?.firstInstallTime?.let { formatter.format(it) } ?: "unknown")
                    put("lastUpdateTime", info?.lastUpdateTime?.let { formatter.format(it) } ?: "unknown")
                    put("signerSha256", signerSha256 ?: "unknown")
                    put("processId", android.os.Process.myPid())
                }
    return payload.toString()
}

// ---------- list_enabled_tools ----------

/**
 * 当前启用的本地工具选项（按分类）。只读。
 * 用于回答"我现在能用哪些工具"，也可与 [tool_usage_stats] 对照找出"启用了却从未用过"的项。
 */
internal suspend fun enabledToolsPayload(settingsStore: SettingsStore): String {
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getCurrentAssistant()
            val enabled = assistant.localTools
            val byCategory =
                buildJsonObject {
                    LocalToolCatalog.groups().forEach { (category, options) ->
                        val names = options.filter { it in enabled }.map { it.toString() }
                        if (names.isNotEmpty()) {
                            put(category.id, buildJsonArray { names.forEach { add(JsonPrimitive(it)) } })
                        }
                    }
                }
            val payload =
                buildJsonObject {
                    put("assistant", assistant.name)
                    put("enabledOptionCount", enabled.size)
                    put("byCategory", byCategory)
                    put(
                        "note",
                        "本地工具按「设置 → 工具」的分类勾选；未勾选的工具不会注入给模型。选项名对应一组工具，具体工具名见 tool_surface_report。",
                    )
                }
    return payload.toString()
}

// ---------- tool_usage_stats ----------

/**
 * 本地工具的调用统计（次数/失败数/平均耗时/最近调用时间），可 reset 清零。
 * 只记录工具名与计数，不含任何参数。与 tool_surface_report 对照可得出"从未使用"清单。
 */
internal suspend fun usageStatsPayload(
    context: Context,
    settingsStore: SettingsStore,
    params: JsonObject,
): String {
            val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 200)
            val reset = params["reset"]?.jsonPrimitive?.booleanOrNull ?: false
            if (reset) ToolUsageTracker.clear(context)
            val snapshot = ToolUsageTracker.snapshot(context)
            val injected = ToolUsageTracker.injectedNames(context)
            val settings = settingsStore.settingsFlow.first()
            val payload =
                buildJsonObject {
                    put("assistant", settings.getCurrentAssistant().name)
                    put("trackedToolCount", snapshot.size)
                    put("totalCalls", snapshot.sumOf { it.count })
                    put("resetApplied", reset)
                    put(
                        "top",
                        buildJsonArray {
                            snapshot.take(limit).forEach { entry ->
                                add(
                                    buildJsonObject {
                                        put("name", entry.name)
                                        // 是否已注入给模型（最近一次装配结果）——「开了没用」一眼可见。
                                        put("enabled", entry.name in injected)
                                        put("count", entry.count)
                                        put("failures", entry.failures)
                                        put("avgMs", entry.avgMs)
                                        put("lastUsedAt", entry.lastUsedAt)
                                    },
                                )
                            }
                        },
                    )
                    put("injectedToolCount", injected.size)
                    put(
                        "enabledButNeverCalled",
                        buildJsonArray {
                            injected
                                .filter { name -> snapshot.none { it.name == name } }
                                .sorted()
                                .forEach { add(JsonPrimitive(it)) }
                        },
                    )
                    put(
                        "hint",
                        "enabledButNeverCalled = 已注入给模型但从未被调用的工具；injectedToolCount 为最近一次注入的工具总数。",
                    )
                }
    return payload.toString()
}

// ---------- tool_scope ----------

/**
 * 当前助手的**工具面视图**（只读）：本地工具选项 / 白名单 / 助手级降温名单，
 * 以及每个工具实际生效的档位与**档位来源**，并标注白名单模式下不可移除的保命工具。
 *
 * 口径与来源：
 * - 工具清单 = 最近一次装配出的注入集合（[ToolUsageTracker.injectedNames]），与模型实际看到的一致；
 *   白名单**同时是执行边界**（执行按注入列表查工具），故这就是“能调什么”的真实快照。
 * - 档位/来源 = [ToolSurfacePolicy.decide]（判据与装配共用，避免两套逻辑漂移）。
 * - 保命工具 = [ToolSurfacePolicy.ALWAYS_KEEP_TOOL_NAMES]：零副作用的自救层
 *   （求援 / 列工具 / 取参数表），白名单模式下始终注入，不随名单收窄而消失。
 * - 只读：不修改任何配置。
 */
internal suspend fun toolScopePayload(
    context: Context,
    settingsStore: SettingsStore,
    params: JsonObject,
): String {
    val settings = settingsStore.settingsFlow.first()
    val assistant = settings.getCurrentAssistant()
    val onlyTools = assistant.onlyTools
    val extraCold = assistant.extraColdTools.toSet()
    val injected = ToolUsageTracker.injectedNames(context)
    val trimEnabled = settings.displaySetting.toolSurfaceTrimming && ToolSurfacePolicy.TRIM_ENABLED
    val tierFilter = params["tier"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 200).coerceIn(1, 500)

    data class Row(val name: String, val tier: SurfaceTier, val source: String, val alwaysKept: Boolean)

    val alwaysKeptToolNames = ToolSurfacePolicy.ALWAYS_KEEP_TOOL_NAMES
    val rows =
        injected.sorted().map { name ->
            val decision = ToolSurfacePolicy.decide(name, extraCold)
            Row(name, decision.tier, decision.source.name, name in alwaysKeptToolNames)
        }
    val visible = if (tierFilter == null) rows else rows.filter { it.tier.name == tierFilter }

    val payload =
        buildJsonObject {
            put("assistant", assistant.name)
            put("trimEnabled", trimEnabled)
            put("toolCount", rows.size)
            put(
                "counts",
                buildJsonObject {
                    SurfaceTier.values().forEach { tier ->
                        put(tier.name.lowercase(), rows.count { it.tier == tier })
                    }
                },
            )
            put(
                "tools",
                buildJsonArray {
                    visible.take(limit).forEach { row ->
                        add(
                            buildJsonObject {
                                put("name", row.name)
                                put("tier", row.tier.name)
                                put("tierSource", row.source)
                                put("alwaysKept", row.alwaysKept)
                            },
                        )
                    }
                },
            )
            if (visible.size > limit) put("truncated", visible.size - limit)
            put(
                "localToolOptions",
                buildJsonObject {
                    LocalToolCatalog.groups().forEach { (category, options) ->
                        val names = options.filter { it in assistant.localTools }.map { it.toString() }
                        if (names.isNotEmpty()) {
                            put(category.id, buildJsonArray { names.forEach { add(JsonPrimitive(it)) } })
                        }
                    }
                },
            )
            put("onlyTools", buildJsonArray { onlyTools.forEach { add(JsonPrimitive(it)) } })
            put("extraColdTools", buildJsonArray { extraCold.sorted().forEach { add(JsonPrimitive(it)) } })
            put("alwaysKeptTools", buildJsonArray { alwaysKeptToolNames.sorted().forEach { add(JsonPrimitive(it)) } })
            put(
                "onlyToolsNotInjected",
                buildJsonArray {
                    onlyTools.filterNot { it in injected }.sorted().forEach { add(JsonPrimitive(it)) }
                },
            )
            put(
                "hint",
                "只读快照。tools 来自最近一次装配的注入集合（白名单同时是执行边界：名单外的工具既不可见也不可调用）。" +
                    "tierSource: ASSISTANT_EXTRA_COLD=助手级降温 / POLICY_HOT=策略热档 / POLICY_COLD_EXTRA=策略冷档单件 / " +
                    "POLICY_COLD_PREFIX=策略冷档家族前缀 / DEFAULT_WARM=默认温档。alwaysKept 的三条是零副作用自救层" +
                    "（求援 / 列工具 / 取参数表），白名单模式下不可移除；onlyToolsNotInjected = 白名单里未出现在当前注入集的项" +
                    "（拼错或与其它限制冲突）；trimEnabled=false 时档位不改变注入形态。",
            )
        }
    return payload.toString()
}

// ---------- grouped entry point ----------

private val DIAGNOSTICS_KINDS = listOf(
    "health", "build", "enabled_tools", "usage", "settings", "logs", "requests", "crash", "lifecycle",
    "conversation", "generation", "perf", "models", "audit", "tool_scope",
)

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
): String {
    val settings = settingsStore.settingsFlow.first()
    val assistant = settings.getCurrentAssistant()
    val idRaw = params["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    // 仓库没有「按 id 直取」，从最近若干条里找（缺省即最近一条）
    val recent = runCatching { conversationRepo.getRecentConversations(assistant.id, 20) }.getOrElse { emptyList() }
    val conversation =
        (if (idRaw.isNotBlank()) recent.firstOrNull { it.id.toString() == idRaw } else recent.firstOrNull())
            ?: return buildJsonObject { put("error", "conversation_not_found") }.toString()

    // 注意：当前这一轮往往已有一条 assistant 消息（记录工具调用），但它尚未收尾、usage 仍为空。
    // 若直接取「最后一条 assistant」，自省会永远读到 unknown —— 因此优先取最近一条**带 usage** 的，
    // 找不到才退回最后一条（并如实标注 usage_source）。
    val assistantMessages =
        conversation.messageNodes.asReversed()
            .flatMap { node -> node.messages.asReversed() }
            .filter { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
    val lastAssistant = assistantMessages.firstOrNull { it.usage != null } ?: assistantMessages.firstOrNull()

    return buildJsonObject {
        put("conversation_id", conversation.id.toString())
        put("conversation_title", conversation.title)
        put("assistant", assistant.name)
        put(
            "model",
            assistant.chatModelId?.let { id ->
                settings.providers.firstNotNullOfOrNull { p -> p.models.firstOrNull { it.id == id }?.let { m -> "${p.name} / ${m.displayName.ifBlank { m.modelId }}" } }
            } ?: "inherit",
        )
        put("settings", buildJsonObject {
            put("temperature", assistant.temperature?.let { JsonPrimitive(it) } ?: JsonNull)
            put("top_p", assistant.topP?.let { JsonPrimitive(it) } ?: JsonNull)
            put("max_tokens", assistant.maxTokens?.let { JsonPrimitive(it) } ?: JsonNull)
            put("context_message_limit", assistant.contextMessageLimit)
            put("reasoning_level", assistant.reasoningLevel.name)
        })
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
            ?: return buildJsonObject { put("error", "vault repository unavailable") }.toString()
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
internal suspend fun modelsPayload(settingsStore: SettingsStore, params: JsonObject): String {
    val settings = settingsStore.settingsFlow.first()
    val assistant = settings.getCurrentAssistant()
    val bound = assistant.chatModelId
    // 过滤：默认只回**启用**的 provider（全量 37 个 ≈ 59KB，会把上下文吃掉）；
    // 禁用的仅在 include_disabled=true 时给，或用 provider=名称/uuid 精确取。
    val providerFilter = params["provider"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    val includeDisabled =
        params["include_disabled"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true
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
                    buildJsonObject {
                        put("id", p.id.toString())
                        put("name", p.name)
                        put("enabled", p.enabled)
                        put("type", p::class.simpleName.orEmpty())
                        put("builtIn", p.builtIn)
                        put(
                            "models",
                            JsonArray(
                                p.models.map { m ->
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

private fun diagnosticsParameters(): InputSchema =
    InputSchema.Obj(
        properties = buildJsonObject {
            put("kind", buildJsonObject {
                put("type", "string")
                put("description", "What to inspect: ${DIAGNOSTICS_KINDS.joinToString(" | ")}")
                put("enum", JsonArray(DIAGNOSTICS_KINDS.map { JsonPrimitive(it) }))
            })
            put("level", buildJsonObject {
                put("type", "string")
                put("description", "logs only: level filter D / I / W / E; omit to exclude verbose D (I/W/E only).")
            })
            put("keyword", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "logs and requests: case-insensitive substring filter; several terms may be " +
                        "given separated by comma/space (matches any).",
                )
            })
            put("limit", buildJsonObject {
                put("type", "integer")
                put("description", "logs, requests, usage and tool_scope: max entries to return.")
            })
            put("lines", buildJsonObject {
                put("type", "integer")
                put("description", "lifecycle only: trailing line count (default 60, max 500).")
            })
            put("which", buildJsonObject {
                put("type", "string")
                put("description", "crash only: latest (default), 1 or 2.")
            })
            put("credential", buildJsonObject {
                put("type", "string")
                put("description", "audit only: exact credential name filter; omit for all.")
            })
            put("action", buildJsonObject {
                put("type", "string")
                put("description", "audit only: exact action filter (e.g. env_inject / http_exec); omit for all.")
            })
            put("window_minutes", buildJsonObject {
                put("type", "integer")
                put("description", "audit only: look-back window in minutes (default 1440, max 43200).")
            })
            put("min_count", buildJsonObject {
                put("type", "integer")
                put("description", "audit only: drop rows with fewer than this many occurrences (default 1).")
            })
            put("provider", buildJsonObject {
                put("type", "string")
                put("description", "models only: provider name or uuid filter; omit for all enabled providers.")
            })
            put("include_disabled", buildJsonObject {
                put("type", "string")
                put("description", "models only: set \"true\" to also list disabled providers.")
            })
            put("id", buildJsonObject {
                put("type", "string")
                put("description", "conversation only: conversation UUID. Omit to list recent chats.")
            })
            put("summary", buildJsonObject {
               put("type", "boolean")
               put(
                   "description",
                   "logs only: return statistics only — level counts + top tags + time range + total — " +
                       "instead of raw lines. Prefer this first, then fetch specific lines with keyword/level.",
               )
            })
            put("compact", buildJsonObject {
                put("type", "boolean")
                put(
                    "description",
                    "conversation only: return message structure (role/finishReason/parts/tools) " +
                        "without message text — for checking presence or tool state cheaply.",
                )
            })
            put("reset", buildJsonObject {
                put("type", "boolean")
                put("description", "usage only: clear the counters after reporting.")
            })
            put("tier", buildJsonObject {
                put("type", "string")
                put("description", "tool_scope only: filter rows by effective tier (HOT | WARM | COLD); omit for all.")
            })
        },
        required = listOf("kind")
    )

/**
 * App diagnostics and logs behind a single tool so the tool surface stays small.
 *
 * Each kind delegates to the per-area payload function above; the payload shapes are unchanged.
 * Read-only.
 */
fun diagnosticsTool(
    context: Context,
    settingsStore: SettingsStore,
    doctorChecks: DoctorChecks,
    conversationRepo: ConversationRepository,
): Tool = Tool(
    name = "diagnostics",
    // 描述只留用途与关键用法：kind 全量枚举已在 parameters.kind（enum + joinToString）给出，
    // 再抄一遍是纯冗余（实测该工具 626 token 全场最大，相当一部分来自这层重复枚举）。
    description =
        "Inspect this app itself (kind list and semantics are in the `kind` enum). " +
            "For logs prefer summary:true or level/keyword filters — raw logs are noisy.",
    parameters = { diagnosticsParameters() },
    execute = { input ->
        val params = input.jsonObject
        val kind = params["kind"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().lowercase()
        val text = when (kind) {
            "health" -> appHealthPayload(doctorChecks, context)
            "build" -> buildInfoPayload(context)
            "enabled_tools" -> enabledToolsPayload(settingsStore)
            "usage" -> usageStatsPayload(context, settingsStore, params)
            "settings" -> appSettingsPayload(settingsStore)
            "logs" -> appLogsPayload(context, params)
            "requests" -> requestLogsPayload(context, params)
            "crash" -> crashSnapshotPayload(context, params)
            "lifecycle" -> lifecycleLogsPayload(context, params)
            "conversation" -> conversationsPayload(conversationRepo, settingsStore, params)
            "generation" -> generationPayload(conversationRepo, settingsStore, params)
            "perf" -> perfPayload(context)
            "models" -> modelsPayload(settingsStore, params)
            "audit" -> auditPayload(params)
            "tool_scope" -> toolScopePayload(context, settingsStore, params)
            else -> buildJsonObject {
                put("error", "unknown kind '$kind'")
                put("hint", "kind must be one of: ${DIAGNOSTICS_KINDS.joinToString(" | ")}")
            }.toString()
        }
        listOf(UIMessagePart.Text(text))
    }
)
