package me.rerere.agenttools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 键值配置即工具.
 *
 * AI 可读/改会话级配置(一个 JSON 存储), 每次变更自动写审计日志, 并支持按 key 回滚.
 *
 * 存储布局 (由 [AgentWorkspaceIO] 决定, 通常落在绑定工作区内):
 *   config/settings.json   当前值
 *   config/audit.jsonl     追加式审计 (每行一个 {id, action, key, value, at})
 *
 * 说明: 这是"会话/助手级键值配置"的通用实现. 若某键需要读写 App 全局真实偏好,
 * 由宿主在 [AgentWorkspaceIO] 适配层对接 settingsStore 即可, 工具契约不变.
 */
private val configJson = Json { prettyPrint = false }

private const val SETTINGS_PATH = "config/settings.json"
private const val AUDIT_PATH = "config/audit.jsonl"

fun createConfigTools(
    io: AgentWorkspaceIO,
    approvalOverrides: Map<String, Boolean>,
): List<Tool> {
    fun needsApproval(name: String) = resolveApproval(approvalOverrides, name)
    val store = ConfigStore(io)
    return listOf(
        createGetAllTool(store, ::needsApproval),
        createSetTool(store, ::needsApproval),
        createRevertTool(store, ::needsApproval),
    )
}

private class ConfigStore(private val io: AgentWorkspaceIO) {
    suspend fun load(): JsonObject {
        return try {
            val raw = io.readText(SETTINGS_PATH)
            (configJson.parseToJsonElement(raw) as? JsonObject) ?: JsonObject(emptyMap())
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
    }

    suspend fun set(key: String, valueJson: String, note: String?): JsonObject {
        val current = load().toMutableMap()
        current[key] = configJson.parseToJsonElement(valueJson)
        save(current)
        appendAudit("set", key, valueJson, note)
        return jsonFor(key, current[key])
    }

    suspend fun revert(key: String): JsonObject {
        val last = readAudit().asReversed().firstOrNull { it["key"]?.jsonPrimitive?.contentOrNull == key }
        val current = load().toMutableMap()
        val prior = last?.get("value")
        if (prior != null && prior !is JsonNull) {
            // 工具描述承诺"恢复最近一次旧值"：把审计里的 prior 写回，而不是只删
            current[key] = prior
        } else {
            current.remove(key)
        }
        val result = if (prior != null && prior !is JsonNull) "restored prior value from audit log" else "no prior value; removed if present"
        save(current)
        appendAudit("revert", key, JsonNull.toString(), null)
        return buildJsonObject {
            put("key", key)
            put("result", result)
        }
    }

    suspend fun readAudit(): List<JsonObject> {
        return try {
            io.readText(AUDIT_PATH).lines().filter { it.isNotBlank() }.mapNotNull {
                runCatching { configJson.parseToJsonElement(it) as? JsonObject }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun save(map: Map<String, JsonElement>) {
        io.writeText(SETTINGS_PATH, configJson.encodeToString(JsonElement.serializer(), objOf(map)), true)
    }

    private suspend fun appendAudit(action: String, key: String, valueJson: String, note: String?) {
        val entry = buildJsonObject {
            put("id", System.currentTimeMillis())
            put("action", action)
            put("key", key)
            put("value", runCatching { configJson.parseToJsonElement(valueJson) }.getOrElse { JsonNull })
            put("at", System.currentTimeMillis())
            if (note != null) put("note", note)
        }
        val existing = try { io.readText(AUDIT_PATH) } catch (_: Exception) { "" }
        val next = if (existing.isBlank()) entry.toString() + "\n" else existing.trimEnd() + "\n" + entry.toString() + "\n"
        io.writeText(AUDIT_PATH, next, true)
    }

    private fun objOf(map: Map<String, JsonElement>): JsonObject = buildJsonObject {
        map.forEach { (k, v) -> put(k, v) }
    }

    private fun jsonFor(key: String, value: JsonElement?) = buildJsonObject {
        put("key", key)
        put("value", value ?: JsonNull)
    }
}

private fun createGetAllTool(store: ConfigStore, needsApproval: (String) -> Boolean) = Tool(
    name = "config_get_all",
    description = """
        Read the full session-level configuration as a JSON object (config/settings.json).
        Use this to inspect current config before deciding to set.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = listOf()) },
    needsApproval = { needsApproval("config_get_all") },
    execute = { listOf(UIMessagePart.Text(store.load().toString())) },
)

private fun createSetTool(store: ConfigStore, needsApproval: (String) -> Boolean) = Tool(
    name = "config_set",
    description = """
        Set a session-level config key to a JSON value. Writes config/settings.json and appends
        an audit entry. Every set is logged and revertable via config_revert.
        Provide `value` as a JSON-encoded value (string "x", number 1, bool true, object {...}, array [...]).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("key", buildJsonObject { put("type", "string"); put("description", "Config key, e.g. 'ui.lang', 'agent.verbosity'") })
                put("value", buildJsonObject { put("type", "string"); put("description", "JSON-encoded value to store (parsed). e.g. \"\\\"zh\\\"\" for a string, \"3\" for a number") })
                put("note", buildJsonObject { put("type", "string"); put("description", "Optional human note recorded in the audit log") })
            },
            required = listOf("key", "value"),
        )
    },
    needsApproval = { needsApproval("config_set") },
    execute = {
        val params = it.jsonObject
        val key = params["key"]?.jsonPrimitive?.contentOrNull ?: error("key is required")
        val valueJson = params["value"]?.jsonPrimitive?.contentOrNull ?: error("value is required")
        val note = params["note"]?.jsonPrimitive?.contentOrNull
        listOf(UIMessagePart.Text(store.set(key, valueJson, note).toString()))
    },
)

private fun createRevertTool(store: ConfigStore, needsApproval: (String) -> Boolean) = Tool(
    name = "config_revert",
    description = """
        Remove the current value of a config key, restoring the most recent prior value.
        Writes an audit entry. Use when a configured value turns out wrong.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("key", buildJsonObject { put("type", "string"); put("description", "Config key to revert") })
            },
            required = listOf("key"),
        )
    },
    needsApproval = { needsApproval("config_revert") },
    execute = {
        val key = it.jsonObject["key"]?.jsonPrimitive?.contentOrNull ?: error("key is required")
        listOf(UIMessagePart.Text(store.revert(key).toString()))
    },
)