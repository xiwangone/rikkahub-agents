package me.rerere.agenttools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.optLong
import me.rerere.common.http.optString
import me.rerere.common.log.AppLogger
import java.io.IOException
import java.util.UUID

/**
 * 键值配置即工具.
 *
 * AI 可读/改会话级配置(一个 JSON 存储), 每次变更自动写审计日志, 并支持按 key 回滚.
 *
 * 存储布局 (由 [AgentWorkspaceIO] 决定, 通常落在绑定工作区内):
 *   config/settings.json   当前值
 *   config/audit.jsonl     追加式审计 (每行一个 {id, action, key, value, prior, at})
 *
 * 说明: 这是"会话/助手级键值配置"的通用实现. 若某键需要读写 App 全局真实偏好,
 * 由宿主在 [AgentWorkspaceIO] 适配层对接 settingsStore 即可, 工具契约不变.
 */
private val configJson = Json { prettyPrint = false }

private const val SETTINGS_PATH = "config/settings.json"
private const val SETTINGS_BACKUP_PATH = "config/settings.json.bak"
private const val AUDIT_PATH = "config/audit.jsonl"
private const val TAG = "ConfigTools"

// 审计文件轮转上限: 超限时只保留最近的条目, 避免文件永久增长且每次追加都全量读写.
// 完整修复需要宿主接口提供追加写, 仅靠读-改-写无法消除全量读写, 这里只做进程内
// 串行化与轮转.
private const val MAX_AUDIT_BYTES = 1024 * 1024
private const val AUDIT_KEEP_BYTES = 512 * 1024

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
    // 串行化配置读-改-写与审计追加: set/revert 的 load→save 若无互斥,
    // 并发调用会在窗口内互相覆盖（后写覆盖前写丢更新）.
    private val auditMutex = Mutex()
    /**
     * 读当前配置.
     *
     * 文件不存在是首次使用的正常状态, 返回空对象. 文件存在但读不出来, 或内容
     * 无法解析, 则抛异常: 调用方不得把这种情况当作空配置, 继续全量覆盖.
     */
    suspend fun load(): JsonObject {
        val raw = try {
            io.readText(SETTINGS_PATH)
        } catch (e: java.io.IOException) {
            return loadFallback(e) ?: JsonObject(emptyMap())
        } catch (e: IllegalArgumentException) {
            return loadFallback(e) ?: JsonObject(emptyMap())
        } catch (e: IllegalStateException) {
            return loadFallback(e) ?: JsonObject(emptyMap())
        }
        return parseSettings(raw)
    }

    /**
     * 配置文件读失败时的统一处理: 文件存在却读不出来是数据损坏, 抛异常拒绝静默丢弃;
     * 文件不存在是首次使用的正常情况, 返回 null 由调用方按"空配置"处理.
     */
    private suspend fun loadFallback(e: Exception): JsonObject? {
        if (fileExists(SETTINGS_PATH)) {
            throw IllegalStateException("config/settings.json exists but cannot be read: ${e.message}", e)
        }
        return null
    }

    /**
     * 解析配置文本. 解析失败时先尝试补齐截断末尾 (写一半的文件),
     * 仍无法解析则抛异常, 禁止调用方用空对象覆盖已有配置.
     */
    private fun parseSettings(raw: String): JsonObject {
        val parsed = runCatching { configJson.parseToJsonElement(raw) }.getOrNull()
        if (parsed is JsonObject) return parsed
        val salvaged = runCatching { configJson.parseToJsonElement(closeTruncated(raw)) }.getOrNull()
        if (salvaged is JsonObject) return salvaged
        // 两次解析都失败：配置已损坏，拒绝用空对象覆盖，记一条 w 再抛。
        AppLogger.w(TAG, "parseSettings: $SETTINGS_PATH is corrupt and cannot be parsed (${raw.length} bytes)")
        throw IllegalStateException("config/settings.json is corrupt and cannot be parsed; refusing to overwrite it with an empty config")
    }

    /**
     * 补齐截断 JSON 文本末尾未闭合的字符串与括号/方括号.
     * 扫描时跳过字符串内的括号, 转义字符按字面处理. 只修复"末尾截断":
     * 截断发生在键、数字或字面量中间时, 补齐后仍无法解析, 由调用方按损坏处理.
     */
    private fun closeTruncated(raw: String): String {
        val openers = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (ch in raw) {
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && (ch == '{' || ch == '[') -> openers.addLast(ch)
                !inString && (ch == '}' || ch == ']') -> if (openers.isNotEmpty()) openers.removeLast()
            }
        }
        return buildString {
            append(raw)
            // 末尾悬空反斜杠：字符串在转义字符处截断时，直接补引号会被这个反斜杠
            // 吞掉（变成转义引号），字符串仍未闭合、补救失败；先补一个反斜杠把它
            // 自身转义掉，再补引号。
            if (escaped) append('\\')
            if (inString) append('"')
            while (openers.isNotEmpty()) append(if (openers.removeLast() == '{') '}' else ']')
        }
    }

    /**
     * 用 listFiles 判断文件是否存在: readText 在"文件不存在"与"读失败"时都抛
     * 异常, 无法仅凭异常区分, 因此读失败后用列表核对.
     */
    private suspend fun fileExists(path: String): Boolean {
        val dir = path.substringBeforeLast('/', "")
        return runCatching { io.listFiles(dir) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrElse {
            // 列表失败时无法区分「文件不存在」与「读失败」：按不存在处理，
            // 但记一条 w，否则损坏的配置会被静默当成空配置。
            AppLogger.w(TAG, "fileExists check failed for $path, treating as not-exists", it)
            null
        }?.any { it.path == path } ?: false
    }

    suspend fun set(key: String, valueJson: String, note: String?): JsonObject {
        val parsed = try {
            configJson.parseToJsonElement(valueJson)
        } catch (e: SerializationException) {
            throw IllegalArgumentException(
                "value must be a JSON-encoded value: for a string pass \"zh\" (including the quotes), " +
                    "for a number pass 3, for a boolean pass true, for an object pass {\"a\": 1}. " +
                    "Failed to parse: ${e.message?.lineSequence()?.firstOrNull()}",
                e,
            )
        }
        return auditMutex.withLock {
            val current = load().toMutableMap()
            val prior = current[key] ?: JsonNull
            current[key] = parsed
            save(current)
            appendAuditLocked("set", key, parsed, prior, note)
            jsonFor(key, current[key])
        }
    }

    suspend fun revert(key: String): JsonObject {
        auditMutex.withLock {
            val entries = readAudit()
            // 只撤销最近一条尚未被撤销的 set：每次 revert 消费掉它撤销的那条 set
            // 记录（见 isReverted），否则重复 revert 会按同一条 set 记录把值
            // 来回恢复——第二次 revert 本应无事可做。
            val lastSet = entries.asReversed().firstOrNull { entry ->
                entry.optString("key") == key &&
                    entry.optString("action") == "set" &&
                    !isReverted(entry, entries)
            }
            val current = load().toMutableMap()
            val existed = current.containsKey(key)
            if (lastSet == null) {
                // 区分"从来没 set 过"与"set 记录都已被 revert 消费掉"：后者不是
                // 无历史，是历史已用完，第二次 revert 不应再动键。
                val hadSetHistory = entries.any {
                    it.optString("key") == key && it.optString("action") == "set"
                }
                return buildJsonObject {
                    put("key", key)
                    put("existed", existed)
                    put(
                        "result",
                        if (hadSetHistory) "All set history already reverted, nothing to revert"
                        else "No set history, nothing to revert",
                    )
                }
            }
            val prior = lastSet["prior"]
            if (prior == null) {
                // 该 set 记录早于 prior 字段：旧值未知，键保持不变，绝不靠猜删键。
                return buildJsonObject {
                    put("key", key)
                    put("existed", existed)
                    put("result", "Old value unknown (this entry predates prior records); key left unchanged")
                }
            }
            val before = current[key] ?: JsonNull
            val restored: JsonElement
            val result: String
            if (prior is JsonNull) {
                // 该键在最近一次 set 之前不存在：恢复即删键。
                current.remove(key)
                restored = JsonNull
                result = "removed key; it did not exist before the last set"
            } else {
                current[key] = prior
                restored = prior
                result = "restored prior value from audit log"
            }
            save(current)
            appendAuditLocked(
                "revert",
                key,
                restored,
                before,
                null,
                revertedId = lastSet.optString("id"),
            )
            return buildJsonObject {
                put("key", key)
                put("existed", existed)
                put("result", result)
            }
        }
    }

    /**
     * 判断一条 set 审计记录是否已被某次 revert 消费（撤销）过。
     *
     * 配对规则：revert 记录写 `revertedId`（被撤销那条 set 记录的 id），id 能
     * 对上即已消费。时间戳兜底：旧记录没有 id（或旧 revert 没有 revertedId）
     * 时配对不上，此时发生在该 set 之后、且本身没有 revertedId 的 revert 即
     * 视为消费了它——旧记录下这会多消费同键更早的 set，但宁可少撤销一次，
     * 也不把已撤销的值再恢复回来。
     */
    private fun isReverted(setEntry: JsonObject, entries: List<JsonObject>): Boolean {
        val key = setEntry.optString("key")
        val reverts = entries.filter {
            it.optString("key") == key && it.optString("action") == "revert"
        }
        val setId = setEntry.optString("id")
        if (setId != null && reverts.any { it.optString("revertedId") == setId }) {
            return true
        }
        val setAt = setEntry.optLong("at")
        if (setAt != null) {
            // 只有没配对字段的旧 revert 才参与兜底：带 revertedId 的 revert
            // 只消费它配对的那条 set，不消费同键更早的记录。
            if (reverts.any {
                    it.optString("revertedId") == null &&
                        (it.optLong("at") ?: Long.MIN_VALUE) > setAt
                }
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 审计文件读失败时的统一处理: 文件存在却读不出来是数据损坏, 抛异常拒绝静默丢弃;
     * 文件不存在是首次使用的正常情况, 返回 null 由调用方按"无审计记录"处理.
     */
    private suspend fun auditReadFallback(e: Exception): String? {
        if (fileExists(AUDIT_PATH)) {
            throw IllegalStateException("config/audit.jsonl exists but cannot be read: ${e.message}", e)
        }
        return null
    }

    /**
     * 读审计日志. 文件不存在返回空列表. 文件存在但读不出来, 或任一非空行无法
     * 解析, 则抛异常: 调用方不得把这种情况当作"无旧值"处理, 否则回滚会删键.
     */
    suspend fun readAudit(): List<JsonObject> {
        val raw: String? = try {
            io.readText(AUDIT_PATH)
        } catch (e: IOException) {
            auditReadFallback(e)
        } catch (e: IllegalArgumentException) {
            auditReadFallback(e)
        } catch (e: IllegalStateException) {
            auditReadFallback(e)
        }
        if (raw == null) return emptyList()
        return raw.lines().filter { it.isNotBlank() }.map { line ->
            (runCatching { configJson.parseToJsonElement(line) }.getOrNull() as? JsonObject)
                ?: run {
                    // 审计文件含损坏行：拒绝用它回滚，记一条 w 再抛。
                    AppLogger.w(TAG, "readAudit: $AUDIT_PATH contains a corrupt entry; refusing to use it for revert")
                    throw IllegalStateException("config/audit.jsonl contains a corrupt entry; refusing to use it for revert")
                }
        }
    }

    /**
     * 全量写回配置. 覆盖前先把旧文件原样备份, 写入中途失败时旧内容仍可找回.
     */
    private suspend fun save(map: Map<String, JsonElement>) {
        val existing = runCatching { io.readText(SETTINGS_PATH) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        if (existing == null && fileExists(SETTINGS_PATH)) {
            // 旧配置存在但读不出来：备份跳过，本次覆盖会丢旧内容，记一条 w。
            AppLogger.w(TAG, "save: existing config could not be read, skipping backup before overwrite")
        }
        if (!existing.isNullOrBlank()) {
            io.writeText(SETTINGS_BACKUP_PATH, existing, true)
        }
        io.writeText(SETTINGS_PATH, configJson.encodeToString(JsonElement.serializer(), objOf(map)), true)
    }

    // 调用方必须已持有 auditMutex（set/revert 全程持锁），此函数不加锁防重入挂死。
    private suspend fun appendAuditLocked(
        action: String,
        key: String,
        value: JsonElement,
        prior: JsonElement,
        note: String?,
        revertedId: String? = null,
    ) {
        val entry = buildJsonObject {
            put("id", UUID.randomUUID().toString())
            put("action", action)
            put("key", key)
            put("value", value)
            put("prior", prior)
            put("at", System.currentTimeMillis())
            if (revertedId != null) put("revertedId", revertedId)
            if (note != null) put("note", note)
        }
        run {
            // 审计文件存在但读不出来时不直接覆盖: 否则一次追加会丢掉整段历史
            val existing = try {
                io.readText(AUDIT_PATH)
            } catch (e: IOException) {
                auditReadFallback(e)
            } catch (e: IllegalArgumentException) {
                auditReadFallback(e)
            } catch (e: IllegalStateException) {
                auditReadFallback(e)
            } ?: ""
            val kept = rotateAudit(existing)
            val next = if (kept.isBlank()) entry.toString() + "\n" else kept.trimEnd() + "\n" + entry.toString() + "\n"
            io.writeText(AUDIT_PATH, next, true)
        }
    }

    /**
     * 审计轮转: 未超限原样返回; 超限时按行丢弃最旧条目, 只保留最近约
     * [AUDIT_KEEP_BYTES] 的内容, 保证每行仍是完整条目.
     */
    private fun rotateAudit(existing: String): String {
        if (existing.toByteArray(Charsets.UTF_8).size <= MAX_AUDIT_BYTES) return existing
        val lines = existing.lines().filter { it.isNotBlank() }
        var keptBytes = 0
        val kept = ArrayDeque<String>()
        for (line in lines.asReversed()) {
            val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
            if (kept.isNotEmpty() && keptBytes + lineBytes > AUDIT_KEEP_BYTES) break
            kept.addFirst(line)
            keptBytes += lineBytes
        }
        return kept.joinToString("\n")
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
        Workspace sandbox only: this is the workspace's own key-value file, not the app's settings.
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
        Workspace sandbox only: this is the workspace's own key-value file, not the app's settings.
        Provide `value` as a JSON-encoded value (string "x", number 1, bool true, object {...}, array [...]).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("key", buildJsonObject { put("type", "string"); put("description", "Config key, e.g. 'ui.lang', 'agent.verbosity'") })
                put("value", buildJsonObject { put("type", "string"); put("description", "JSON-encoded value to store. For a string pass \"zh\" (including the quotes), for a number pass 3, for a boolean pass true, for an object pass {\"a\": 1}") })
                put("note", buildJsonObject { put("type", "string"); put("description", "Optional human note recorded in the audit log") })
            },
            required = listOf("key", "value"),
        )
    },
    needsApproval = { needsApproval("config_set") },
    execute = {
        val params = it.jsonObject
        val key = params.optString("key") ?: error("key is required")
        require(key.isNotBlank()) { "key must not be blank" }
        val valueJson = params.optString("value") ?: error("value is required")
        val note = params.optString("note")
        listOf(UIMessagePart.Text(store.set(key, valueJson, note).toString()))
    },
)

private fun createRevertTool(store: ConfigStore, needsApproval: (String) -> Boolean) = Tool(
    name = "config_revert",
    description = """
        Remove the current value of a config key, restoring the most recent prior value.
        Writes an audit entry. Use when a configured value turns out wrong.
        Workspace sandbox only: this is the workspace's own key-value file, not the app's settings.
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
        val key = it.jsonObject.optString("key") ?: error("key is required")
        listOf(UIMessagePart.Text(store.revert(key).toString()))
    },
)