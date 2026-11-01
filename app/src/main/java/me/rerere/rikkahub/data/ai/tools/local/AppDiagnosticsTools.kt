package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.ToolErrors
import me.rerere.rikkahub.data.repository.ConversationRepository
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
import me.rerere.rikkahub.ui.pages.setting.doctor.DoctorChecks
import java.util.concurrent.ConcurrentHashMap

/**
 * AI 自诊断/自管理工具（第一批，纯读，风险零）。
 *
 * 复用现有组件：
 *  - get_app_health: DoctorChecks.runAll() 的 8 类检查快照
 *  - read_app_logs:   AppLog 缓冲 + LogRedactor 脱敏
 *  - test_model:      提供商「测试连接」核心逻辑（非流式/流式/工具调用）
 */

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


// ---------- grouped entry point ----------

private val DIAGNOSTICS_KINDS = listOf(
    "health", "build", "enabled_tools", "usage", "settings", "assistants", "logs", "logs_file", "requests", "crash",
    "lifecycle", "conversation", "generation", "perf", "models", "audit", "tool_scope", "runs",
)

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
            put("models_limit", buildJsonObject {
                put("type", "integer")
                put("description", "models only: max models per provider (default 30; 0 = no limit).")
            })
            put("assistant_id", buildJsonObject {
                put("type", "string")
                put("description", "assistants only: show a single assistant by its uuid; omit for all.")
            })
            put("full", buildJsonObject {
                put("type", "string")
                put("description", "assistants only: \"true\" returns the full system prompt; default truncates it to ~1200 chars.")
            })
            put("lines", buildJsonObject {
                put("type", "integer")
                put("description", "lifecycle / logs_file only: trailing line count (lifecycle default 60 max 500; logs_file default 200 max 2000).")
            })
            put("days", buildJsonObject {
                put("type", "integer")
                put("description", "logs_file only: include this many most recent calendar days (default 7; maximum 7, matching disk retention).")
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
            put("min_calls", buildJsonObject {
                put("type", "integer")
                put("description", "usage only: minimum call count for advice entries (default 10).")
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
// kind 分发是平铺 when：每加一个 kind 就 +1 复杂度，但各分支互不相干、无嵌套逻辑，
// 拆成 map 只是把分支搬个位置。取值已在阈值边界，故显式抑制并留此说明。
@Suppress("CyclomaticComplexMethod")
fun diagnosticsTool(
    context: Context,
    settingsStore: SettingsStore,
    doctorChecks: DoctorChecks,
    conversationRepo: ConversationRepository,
): Tool = Tool(
    name = "diagnostics",
    // 描述只留用途与关键用法：kind 全量枚举已在 parameters.kind（enum + joinToString）给出，
    // 再抄一遍是纯冗余（该工具 token 开销全场最大，相当一部分来自这层重复枚举）。
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
            "assistants" -> assistantsPayload(settingsStore, params)
            "logs" -> appLogsPayload(context, params)
            "logs_file" -> fileLogsPayload(context, params)
            "requests" -> requestLogsPayload(context, params)
            "crash" -> crashSnapshotPayload(context, params)
            "lifecycle" -> lifecycleLogsPayload(context, params)
            "conversation" -> conversationsPayload(conversationRepo, settingsStore, params)
            "generation" -> generationPayload(conversationRepo, settingsStore, params)
            "perf" -> perfPayload(context)
            "models" -> modelsPayload(settingsStore, params)
            "audit" -> auditPayload(params)
            "tool_scope" -> toolScopePayload(context, settingsStore, params)
            "runs" -> runsPayload(context, params)
            else -> ToolErrors.envelopeFor(error = "invalid_argument", message = "unknown kind '$kind'", hint = "kind must be one of: ${DIAGNOSTICS_KINDS.joinToString(" | ")}").toString()
        }
        listOf(UIMessagePart.Text(text))
    }
)
