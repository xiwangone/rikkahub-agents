package me.rerere.rikkahub.data.ai.tools.local

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
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
import me.rerere.rikkahub.data.ai.tools.ToolUsageTracker
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.RikkaHubApp
import kotlinx.serialization.json.booleanOrNull
import me.rerere.ai.core.InputSchema
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

// ---------- read_app_logs ----------

/**
 * 按级别 / 关键字 / 条数读取应用日志，返回前先过 [LogRedactor.maskText] 脱敏，
 * 防止崩溃堆栈泄露 API key / 连接串。
 */
internal fun appLogsPayload(context: Context, params: JsonObject): String {
        val level = params["level"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.uppercase(Locale.US)?.take(1)
        val keyword = params["keyword"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.lowercase(Locale.getDefault())?.takeIf { s -> s.isNotEmpty() }
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 50

        val entries = AppLog.getLogs()
            .filter { e -> level == null || e.level.toString() == level }
            .filter { e ->
                keyword == null ||
                    e.tag.lowercase(Locale.getDefault()).contains(keyword) ||
                    e.message.lowercase(Locale.getDefault()).contains(keyword)
            }
            .take(limit.coerceIn(1, 500))

        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val raw = entries.joinToString("\n") { e ->
            "${fmt.format(e.timestamp)} ${e.level} ${e.tag}: ${e.message}"
        }
    return LogRedactor.maskText(raw)
}

// ---------- read_request_logs ----------

/**
 * 读取 HTTP 请求/响应摘要日志（common Logging 的 RequestLog，纯内存 100 条），
 * 返回前过 [LogRedactor.maskText] 脱敏。用于排查 API 调用失败/限流/端点错误等
 * 一手证据——read_app_logs 只覆盖 AppLog，请求日志是唯一能看到实际请求结果的入口。
 */
internal fun requestLogsPayload(context: Context, params: JsonObject): String {
        val keyword = params["keyword"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.lowercase(Locale.getDefault())?.takeIf { s -> s.isNotEmpty() }
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 50

        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val entries = Logging.getRequestLogs()
            .filter { e -> keyword == null || e.url.lowercase(Locale.getDefault()).contains(keyword) || (e.error?.lowercase(Locale.getDefault())?.contains(keyword) == true) }
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
        val out = buildJsonObject {
            put("assistants", buildJsonArray {
                settings.assistants.forEach { a ->
                    add(buildJsonObject {
                        put("id", a.id.toString())
                        put("name", a.name)
                        put("chat_model_id", a.chatModelId?.toString() ?: "inherit")
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
internal suspend fun conversationsPayload(
    conversationRepo: ConversationRepository,
    settingsStore: SettingsStore,
    params: JsonObject,
): String {
    val idRaw = params["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (idRaw.isEmpty()) {
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getCurrentAssistant()
        val limit = params["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 50) ?: 20
        val list = conversationRepo.getRecentConversations(assistant.id, limit)
        return buildJsonObject {
            put("assistantId", assistant.id.toString())
            put("count", list.size)
            put("conversations", JsonArray(list.map { c ->
                buildJsonObject {
                    put("id", c.id.toString())
                    put("title", c.title)
                    put("pinned", c.isPinned)
                    put("updatedAt", c.updateAt.toString())
                }
            }))
        }.toString()
    }
    val uuid = runCatching { kotlin.uuid.Uuid.parse(idRaw) }.getOrElse {
        return buildJsonObject { put("error", "invalid conversation id '$idRaw'") }.toString()
    }
    val conversation = conversationRepo.getConversationById(uuid)
        ?: return buildJsonObject { put("error", "conversation not found: $idRaw") }.toString()
    var totalChars = 0
    val messagesJson = buildJsonArray {
        loop@ for ((index, node) in conversation.messageNodes.withIndex()) {
            for (m in node.messages) {
                val text = m.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
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
    return buildJsonObject {
        put("id", conversation.id.toString())
        put("title", conversation.title)
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

// ---------- grouped entry point ----------

private val DIAGNOSTICS_KINDS = listOf(
    "health", "build", "enabled_tools", "usage", "settings", "logs", "requests", "crash", "lifecycle",
    "conversation", "perf",
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
    description = """
        Inspect this app itself. Choose one kind: health (built-in Doctor checks), build (installed
        build identity and signing certificate), enabled_tools (which tool options are on), usage
        (per-tool call counts), settings (configuration summary), logs (in-memory app log), requests
        (HTTP request summary log), crash (last crash snapshot), lifecycle (process lifecycle log),
        conversation (list recent chats, or export one conversation's messages by id), or perf
        (process uptime / heap / threads).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("kind", buildJsonObject {
                    put("type", "string")
                    put("description", "What to inspect: ${DIAGNOSTICS_KINDS.joinToString(" | ")}")
                    put("enum", JsonArray(DIAGNOSTICS_KINDS.map { JsonPrimitive(it) }))
                })
                put("level", buildJsonObject {
                    put("type", "string")
                    put("description", "logs only: level filter D / I / W / E.")
                })
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put("description", "logs and requests: case-insensitive substring filter.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "logs, requests and usage: max entries to return.")
                })
                put("lines", buildJsonObject {
                    put("type", "integer")
                    put("description", "lifecycle only: trailing line count (default 60, max 500).")
                })
                put("which", buildJsonObject {
                    put("type", "string")
                    put("description", "crash only: latest (default), 1 or 2.")
                })
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "conversation only: conversation UUID. Omit to list recent chats.")
                })
                put("reset", buildJsonObject {
                    put("type", "boolean")
                    put("description", "usage only: clear the counters after reporting.")
                })
            },
            required = listOf("kind")
        )
    },
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
            "perf" -> perfPayload(context)
            else -> buildJsonObject {
                put("error", "unknown kind '$kind'")
                put("hint", "kind must be one of: ${DIAGNOSTICS_KINDS.joinToString(" | ")}")
            }.toString()
        }
        listOf(UIMessagePart.Text(text))
    }
)
