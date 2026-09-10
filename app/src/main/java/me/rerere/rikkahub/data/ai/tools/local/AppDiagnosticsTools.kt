package me.rerere.rikkahub.data.ai.tools.local

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
fun getAppHealthTool(
    doctorChecks: DoctorChecks,
    context: Context,
): Tool = Tool(
    name = "get_app_health",
    description = """
        Return the app's diagnostic health snapshot. Reuses the built-in Doctor checks and
        returns a structured list of every check item (permissions, services, assistant info,
        database, network, termux, maintenance, diagnostics) with its severity
        (OK / INFO / WARN / FAIL). Use this to detect misconfigured or broken subsystems.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
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
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

// ---------- read_app_logs ----------

/**
 * 按级别 / 关键字 / 条数读取应用日志，返回前先过 [LogRedactor.maskText] 脱敏，
 * 防止崩溃堆栈泄露 API key / 连接串。
 */
fun readAppLogsTool(context: Context): Tool = Tool(
    name = "read_app_logs",
    description = """
        Read the in-memory application log buffer. Optionally filter by level
        (D / I / W / E), by case-insensitive keyword, and cap the return count.
        The output is de-sensitised through LogRedactor before being returned,
        so any API keys / tokens / URLs in the log are masked.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("level", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional log level filter: D (debug) / I (info) / W (warn) / E (error). Omit for all.")
                })
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional case-insensitive substring to match against tag or message. Omit for all.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max number of log lines to return (default 50).")
                })
            },
            required = emptyList(),
        )
    },
    execute = {
        val params = it.jsonObject
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
        listOf(UIMessagePart.Text(LogRedactor.maskText(raw)))
    },
)

// ---------- read_request_logs ----------

/**
 * 读取 HTTP 请求/响应摘要日志（common Logging 的 RequestLog，纯内存 100 条），
 * 返回前过 [LogRedactor.maskText] 脱敏。用于排查 API 调用失败/限流/端点错误等
 * 一手证据——read_app_logs 只覆盖 AppLog，请求日志是唯一能看到实际请求结果的入口。
 */
fun readRequestLogsTool(context: Context): Tool = Tool(
    name = "read_request_logs",
    description = """Read the in-memory HTTP request/response summary log (RequestLog, up to 100 entries). Each line: time method url code duration error. Output is de-sensitised through LogRedactor. Use when debugging provider/API failures, rate limits, endpoint errors."""".trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max number of entries to return (default 50).")
                })
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional case-insensitive substring to match against url/error. Omit for all.")
                })
            },
            required = emptyList(),
        )
    },
    execute = {
        val params = it.jsonObject
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
        listOf(UIMessagePart.Text(LogRedactor.maskText(raw)))
    },
)

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
        Test a specific model endpoint by provider + model id. Runs three probes:
        non-streaming text generation, streaming text generation, and tool-call support.
        Results are cached per endpoint+model so repeat tests are instant. Set force=true
        to bypass the cache. Returns per-probe status (ok / fail) with a short summary.
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
