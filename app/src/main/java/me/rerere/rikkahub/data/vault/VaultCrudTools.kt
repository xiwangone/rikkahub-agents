package me.rerere.rikkahub.data.vault

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Vault 凭证工具（App 内 AI 凭证中枢）。
 *
 * 安全模型：凭证值只在 App 进程内存使用（JSch 字节加载 / 密码对象），
 * 工具返回值不含明文——只返回变量名、公钥、命令输出。
 */
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 更新凭证条目的元数据（名称/描述/分组），值不可被 AI 修改。改名=复制 value 密文到新名后删旧条目。 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught") // Schema保持集中；跨DataStore/Room的失败边界保留源凭证并显式重抛取消。
fun vaultCredentialUpdateTool(
    context: android.content.Context,
    repository: CredentialVaultRepository,
    settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
    sshHostRepository: me.rerere.rikkahub.data.repository.SshHostRepository,
    telegramBotPreferences: me.rerere.rikkahub.data.telegram.TelegramBotPreferences,
): Tool = Tool(
    name = "vault_credential_update",
    description =
        "Update a credential entry's metadata (rename / change description / change group). " +
            "The secret VALUE is never readable or writable by the AI — this tool only touches " +
            "name/description/group fields. Rename copies the existing encrypted value to the new name, " +
            "and also rewrites references to the old name across configurations (model/media/search/ASR/TTS " +
            "providers / global proxy / MCP headers / Telegram token and proxy / backup and upload S3 / " +
            "WebDAV / Web Bridge path and password / Web Server password / local MCP token / saved SSH hosts) " +
            "so they do not break silently. " +
            "Pass only the fields you want to change; omitted fields stay unchanged.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("name", buildJsonObject { put("type", "string"); put("description", "Current credential name (required)") })
                    put("new_name", buildJsonObject { put("type", "string"); put("description", "Optional new name (rename)") })
                    put("description", buildJsonObject { put("type", "string"); put("description", "Optional new description") })
                    put("group", buildJsonObject { put("type", "string"); put("description", "Optional new group: Git/AI/ECS/MCP/Notification/SSH/Other") })
                    put("public_key", buildJsonObject { put("type", "string"); put("description", "Optional SSH public key line (plaintext). Empty string clears it; omit to keep unchanged.") })
                    put("type", buildJsonObject { put("type", "string"); put("description", "Optional credential type id (e.g. ssh-key / api-key / basic-auth / totp / custom-fields / cloud-ak). Empty string resets to unclassified.") })
                    put("meta", buildJsonObject {
                        put("type", "object")
                        put(
                            "description",
                            "Optional NON-sensitive metadata, MERGED key-by-key: a provided key overwrites, an empty-string value deletes that key, omitted keys keep their current value. " +
                                "Allowed keys: account / access_key_id / user_id / domain_id / project_id / region / endpoint / path / header / prefix / username / algorithm / digits / period.",
                        )
                    })
                },
            required = listOf("name"),
        )
    },
    needsApproval = { true },
    execute = { params ->
        val o = params.jsonObject
        val name = o["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        if (name == null) {
            listOf(UIMessagePart.Text("❌ name is required"))
        } else {
            val sessionManager = VaultSessionManager(context)
            if (!sessionManager.hasActiveAuthorization()) {
                listOf(UIMessagePart.Text("❌ Not authorized: complete Vault authorization first (30 minutes or always valid), then call this tool"))
            } else {
                val existing = repository.getByName(name)
                if (existing == null) {
                    listOf(UIMessagePart.Text("❌ Credential does not exist: $name (use vault_credential_names to list available names)"))
                } else {
                    val newName = o["new_name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                    val desc = o["description"]?.jsonPrimitive?.contentOrNull
                    val group = o["group"]?.jsonPrimitive?.contentOrNull
                    val typeParam = o["type"]?.jsonPrimitive?.contentOrNull
                    // meta 三态显式区分：未传（保留原值）/ 传了但格式错（报错）/ 合法对象（按 key 合并）
                    val metaRaw = o["meta"]
                    val metaParam =
                        when {
                            metaRaw == null -> null
                            metaRaw is kotlinx.serialization.json.JsonObject -> metaRaw
                            else -> return@Tool listOf(UIMessagePart.Text("❌ meta must be a JSON object"))
                        }
                    // meta 按 key 合并：给的键覆盖；值为空串 → 删除该键；未给 meta 则保留原值
                    val mergedMetaJson =
                        if (metaParam != null) {
                            val cur = CredentialMeta.decode(existing.metaJson).toMutableMap()
                            metaParam.forEach { (k, v) ->
                                val s = (v as? JsonPrimitive)?.contentOrNull
                                if (s.isNullOrBlank()) cur.remove(k) else cur[k] = s
                            }
                            CredentialMeta.encode(cur)
                        } else {
                            existing.metaJson
                        }
                    val pubParam = o["public_key"]?.jsonPrimitive?.contentOrNull
                    val newPub: String? = pubParam?.trim()
                    val changed = mutableListOf<String>()
                    val targetName = newName ?: name
                    if (newName != null && newName != name) {
                        if (repository.getByName(newName) != null) {
                            return@Tool listOf(UIMessagePart.Text("❌ Target name already exists: $newName (rename conflict, please pick another name)"))
                        }
                        changed += "Name: $name → $newName"
                    }
                    if (desc != null && desc != existing.description) changed += "Description"
                    if (group != null && group != existing.grp) changed += "Group"
                    if (typeParam != null && typeParam != existing.type) changed += "Type"
                    if (metaParam != null && mergedMetaJson != existing.metaJson) changed += "Metadata"
                    if (pubParam != null && newPub != existing.publicKey) changed += if (newPub.isNullOrEmpty()) "Public key (cleared)" else "Public key"
                    if (changed.isEmpty()) {
                        listOf(UIMessagePart.Text("ℹ️ No fields to update (already up to date)"))
                    } else {
                        val value = repository.decryptValue(existing)
                            ?: return@Tool listOf(UIMessagePart.Text("❌ Decryption failed; update aborted to avoid overwriting the credential with empty values (the key may be temporarily unavailable, please retry later)"))
                        repository.save(
                            name = targetName,
                            value = value,
                            description = desc ?: existing.description,
                            group = group ?: existing.grp,
                            publicKey = newPub ?: existing.publicKey,
                            type = typeParam?.ifBlank { "" } ?: existing.type,
                            metaJson = mergedMetaJson,
                        )
                        var syncedRefs = 0
                        if (targetName != name) {
                            // 保留源凭证直到 DataStore 与所有 SSH 主机引用同步成功。
                            syncedRefs =
                                try {
                                    VaultReferenceSync.renameEverywhere(
                                        settingsStore = settingsStore,
                                        sshHostRepository = sshHostRepository,
                                        telegramBotPreferences = telegramBotPreferences,
                                        oldName = name,
                                        newName = targetName,
                                    )
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    return@Tool listOf(
                                        UIMessagePart.Text(
                                            "⚠️ Reference sync failed; source credential \"$name\" is kept, and target \"$targetName\" was already created, so references may be partially updated. Please verify the references before merging or cleaning up. Reason: ${error.message ?: error::class.simpleName}",
                                        ),
                                    )
                                }
                            repository.delete(existing)
                            repository.logAccess(name, "ai-tool", "rename_from")
                            repository.logAccess(targetName, "ai-tool", "rename_to")
                        } else {
                            repository.logAccess(name, "ai-tool", "update")
                        }
                        listOf(
                            UIMessagePart.Text(
                                "✅ Credential metadata updated: ${targetName}\nChanges: ${changed.joinToString("; ")}\n"
                                    + if (targetName != name) {
                                        "Reference sync: updated $syncedRefs config reference(s)" +
                                            if (syncedRefs == 0) "(no config references the old name)" else ""
                                    } else {
                                        ""
                                    }
                                    + "\n(Value unchanged; AI cannot read or write secret values)",
                            ),
                        )
                    }
                }
            }
        }
    },
)

/** 批量更新多条凭证的元数据（分组/描述/改名）。值不向模型返回；改名前先完整预检。 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "TooGenericExceptionCaught") // 批量边界要捕获每条记录的跨存储失败并继续其它项目。
fun vaultCredentialBulkUpdateTool(
    context: android.content.Context,
    repository: CredentialVaultRepository,
    settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
    sshHostRepository: me.rerere.rikkahub.data.repository.SshHostRepository,
    telegramBotPreferences: me.rerere.rikkahub.data.telegram.TelegramBotPreferences,
): Tool = Tool(
    name = "vault_credential_bulk_update",
    description =
        "Batch-update metadata (group / description / new_name / type) for MULTIPLE credentials in ONE call. " +
            "Same semantics as vault_credential_update, applied to every item of `updates`. " +
            "All items are validated before any write. The secret VALUE is never returned; a rename keeps the old entry until reference synchronization succeeds. " +
            "Returns a per-item result summary; if sync fails, both names are retained and the item is reported incomplete. " +
            "Use this instead of calling vault_credential_update N times (e.g. re-grouping 30 entries).",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put(
                        "updates",
                        buildJsonObject {
                            put("type", "array")
                            put("description", "List of updates; each item needs `name` plus any of group/description/new_name/type/meta")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "object")
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put("name", buildJsonObject { put("type", "string"); put("description", "Current credential name (required)") })
                                            put("new_name", buildJsonObject { put("type", "string"); put("description", "Optional new name (rename)") })
                                            put("description", buildJsonObject { put("type", "string"); put("description", "Optional new description") })
                                            put("group", buildJsonObject { put("type", "string"); put("description", "Optional new group") })
                                            put("type", buildJsonObject { put("type", "string"); put("description", "Optional credential type id (ssh-key / api-key / basic-auth / totp / custom-fields / cloud-ak)") })
                                            put("meta", buildJsonObject { put("type", "object"); put("description", "Optional NON-sensitive metadata, merged key-by-key (an empty-string value deletes that key). Allowed keys: account / access_key_id / user_id / domain_id / project_id / region / endpoint / path / header / prefix / username / algorithm / digits / period") })
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            required = listOf("updates"),
        )
    },
    needsApproval = { true },
    execute = { params ->
        val items = params.jsonObject["updates"] as? JsonArray
        if (items == null || items.isEmpty()) {
            listOf(UIMessagePart.Text("❌ updates is required and must not be empty (array)"))
        } else {
            val sessionManager = VaultSessionManager(context)
            if (!sessionManager.hasActiveAuthorization()) {
                listOf(UIMessagePart.Text("❌ Not authorized: complete Vault authorization first (30 minutes or always valid), then call this tool"))
            } else {
                data class PreparedUpdate(
                    val name: String,
                    val targetName: String,
                    val description: String,
                    val group: String,
                    val type: String,
                    val publicKey: String,
                    val metaJson: String,
                    val secretValue: String,
                    val expectedUpdatedAt: Long,
                    val changed: List<String>,
                )

                val preflightErrors = mutableListOf<String>()
                val prepared = mutableListOf<PreparedUpdate>()
                val seenNames = mutableSetOf<String>()
                val seenTargets = mutableSetOf<String>()

                fun readString(o: JsonObject, key: String, item: Int): String? {
                    if (key !in o) return null
                    val value = (o[key] as? JsonPrimitive)?.contentOrNull
                    if (value == null) preflightErrors += "Item ${item + 1}: $key must be a string"
                    return value
                }

                items.forEachIndexed { index, element ->
                    val o = element as? JsonObject
                    if (o == null) {
                        preflightErrors += "Item ${index + 1}: must be an object"
                        return@forEachIndexed
                    }
                    val name = readString(o, "name", index)?.ifBlank { null }
                    if (name == null) {
                        preflightErrors += "Item ${index + 1}: missing a valid name"
                        return@forEachIndexed
                    }
                    if (!seenNames.add(name)) {
                        preflightErrors += "Duplicate update source: $name"
                        return@forEachIndexed
                    }

                    val requestedName = readString(o, "new_name", index)?.ifBlank { null }
                    val descriptionParam = readString(o, "description", index)
                    val groupParam = readString(o, "group", index)
                    val typeParam = readString(o, "type", index)
                    val metaParam = if ("meta" in o) o["meta"] as? JsonObject else null
                    if ("meta" in o && metaParam == null) {
                        preflightErrors += "Item ${index + 1}: meta must be an object"
                    }
                    if (!CredentialVaultRepository.validateCredentialName(name)) {
                        preflightErrors += "Item ${index + 1}: source name does not follow the vault naming rules: $name"
                    }
                    if (typeParam != null && !CredentialType.isValid(typeParam)) {
                        preflightErrors += "Item ${index + 1}: unknown credential type '$typeParam'"
                    }

                    val existing = runCatching { repository.getByName(name) }.getOrElse {
                        preflightErrors += "Item ${index + 1}: cannot read credential $name"
                        return@forEachIndexed
                    }
                    if (existing == null) {
                        preflightErrors += "Item ${index + 1}: credential $name does not exist"
                        return@forEachIndexed
                    }

                    val targetName = requestedName ?: name
                    if (targetName != name && !CredentialVaultRepository.validateCredentialName(targetName)) {
                        preflightErrors += "Item ${index + 1}: new name is invalid (must be UPPER_SNAKE_CASE, max 64 chars): $targetName"
                    }
                    if (!seenTargets.add(targetName)) {
                        preflightErrors += "Multiple updates point to the same target name: $targetName"
                    }
                    if (targetName != name) {
                        val targetLookup = runCatching { repository.getByName(targetName) }
                        if (targetLookup.isFailure) {
                            preflightErrors += "Item ${index + 1}: cannot check whether the target name is available: $targetName"
                        } else if (targetLookup.getOrNull() != null) {
                            preflightErrors += "Item ${index + 1}: target name already exists: $targetName"
                        }
                    }

                    val description = descriptionParam?.ifBlank { existing.description } ?: existing.description
                    val group = groupParam?.ifBlank { existing.grp } ?: existing.grp
                    val type = typeParam?.ifBlank { existing.type } ?: existing.type
                    val metaJson = if (metaParam != null) {
                        val merged = CredentialMeta.decode(existing.metaJson).toMutableMap()
                        metaParam.forEach { (key, value) ->
                            val text = (value as? JsonPrimitive)?.contentOrNull
                            if (text.isNullOrBlank()) merged.remove(key) else merged[key] = text
                        }
                        CredentialMeta.encode(merged)
                    } else {
                        existing.metaJson
                    }
                    val changed = buildList {
                        if (targetName != name) add("Renamed → $targetName")
                        if (description != existing.description) add("Description")
                        if (group != existing.grp) add("Group → $group")
                        if (type != existing.type) add("Type → $type")
                        if (metaJson != existing.metaJson) add("Metadata")
                    }
                    val secretValue = if (changed.isEmpty() || existing.valueEncrypted.isBlank()) {
                        ""
                    } else {
                        repository.decryptValue(existing) ?: run {
                            preflightErrors += "Item ${index + 1}: cannot decrypt $name; aborted to avoid overwriting with empty values"
                            return@forEachIndexed
                        }
                    }
                    prepared += PreparedUpdate(
                        name = name,
                        targetName = targetName,
                        description = description,
                        group = group,
                        type = type,
                        publicKey = existing.publicKey,
                        metaJson = metaJson,
                        secretValue = secretValue,
                        expectedUpdatedAt = existing.updatedAt,
                        changed = changed,
                    )
                }

                if (preflightErrors.isNotEmpty()) {
                    listOf(
                        UIMessagePart.Text(
                            "Batch update pre-check failed: no writes were performed.\n" + preflightErrors.joinToString("\n") { "❌ $it" },
                        ),
                    )
                } else {
                    val lines = mutableListOf<String>()
                    var ok = 0
                    var noChange = 0
                    var failed = 0
                    for (update in prepared) {
                        if (update.changed.isEmpty()) {
                            noChange++
                            lines += "ℹ️ ${update.name} unchanged"
                            continue
                        }
                        var targetCreated = false
                        try {
                            val current = repository.getByName(update.name)
                            if (current == null || current.updatedAt != update.expectedUpdatedAt) {
                                failed++
                                lines += "❌ ${update.name} changed after the pre-check, please re-read and retry"
                                continue
                            }
                            if (update.targetName != update.name && repository.getByName(update.targetName) != null) {
                                failed++
                                lines += "❌ ${update.name} → ${update.targetName}: the target name was taken just before execution"
                                continue
                            }
                            if (update.targetName != update.name) {
                                repository.insertNew(
                                    name = update.targetName,
                                    value = update.secretValue,
                                    description = update.description,
                                    group = update.group,
                                    publicKey = update.publicKey,
                                    type = update.type,
                                    metaJson = update.metaJson,
                                    createdAt = current.createdAt,
                                )
                                targetCreated = true
                                // 保留源凭证，直到所有引用同步成功；失败/进程中断时至少旧、新引用均仍可解析。
                                VaultReferenceSync.renameEverywhere(
                                    settingsStore = settingsStore,
                                    sshHostRepository = sshHostRepository,
                                    telegramBotPreferences = telegramBotPreferences,
                                    oldName = update.name,
                                    newName = update.targetName,
                                )
                                repository.delete(current)
                                repository.logAccess(update.name, "ai-tool", "bulk_rename_from")
                                repository.logAccess(update.targetName, "ai-tool", "bulk_rename_to")
                            } else {
                                repository.save(
                                    name = update.name,
                                    value = update.secretValue,
                                    description = update.description,
                                    group = update.group,
                                    publicKey = update.publicKey,
                                    type = update.type,
                                    metaJson = update.metaJson,
                                )
                                repository.logAccess(update.name, "ai-tool", "bulk_update")
                            }
                            ok++
                            lines += "✅ ${update.name}（${update.changed.joinToString("、")}）"
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failed++
                            val sourceRemains = runCatching { repository.getByName(update.name) }.getOrNull() != null
                            val targetRemains = runCatching { repository.getByName(update.targetName) }.getOrNull() != null
                            lines += if (targetCreated && sourceRemains && targetRemains) {
                                "⚠️ ${update.name} → ${update.targetName} not completed: both old and new entries are kept and references may be partially synced; verify the references first, then manually clean up the old entry once they match."
                            } else if (targetCreated && targetRemains) {
                                "⚠️ ${update.name} → ${update.targetName}: the target entry exists and the source entry state needs verification; check the references and the Vault before proceeding."
                            } else {
                                "❌ ${update.name} update failed (${error::class.simpleName})"
                            }
                        }
                    }
                    listOf(
                        UIMessagePart.Text(
                            "Batch update done: succeeded $ok / unchanged $noChange / failed or pending recovery $failed\n" + lines.joinToString("\n"),
                        ),
                    )
                }
            }
        }
    },
)

/** AI 删除凭证条目。值不可见，仅按名称删除；删除不可逆，故强制审批。 */
fun vaultCredentialDeleteTool(
    context: android.content.Context,
    repository: CredentialVaultRepository,
): Tool = Tool(
    name = "vault_credential_delete",
    description =
        "Delete a credential entry from the vault by name. The secret value is never shown to the AI. " +
            "Deletion is IRREVERSIBLE — requires explicit user approval. " +
            "Use vault_credential_names first to confirm the exact name.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("name", buildJsonObject { put("type", "string"); put("description", "Credential name to delete") })
                },
            required = listOf("name"),
        )
    },
    needsApproval = { true },
    execute = { params ->
        val name = params.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        if (name == null) {
            listOf(UIMessagePart.Text("❌ name is required"))
        } else {
            val sessionManager = VaultSessionManager(context)
            if (!sessionManager.hasActiveAuthorization()) {
                listOf(UIMessagePart.Text("❌ Not authorized: complete Vault authorization first (30 minutes or always valid), then call this tool"))
            } else {
                val existing = repository.getByName(name)
                if (existing == null) {
                    listOf(UIMessagePart.Text("❌ Credential does not exist: $name (use vault_credential_names to list available names)"))
                } else {
                    repository.delete(existing)
                    repository.logAccess(name, "ai-tool", "delete")
                    listOf(UIMessagePart.Text("🗑️ Deleted credential: $name"))
                }
            }
        }
    },
)

/** 查询单条凭证的完整元数据（含公钥全文与指纹；值永不明文返回）。 */
fun vaultCredentialMetaTool(repository: CredentialVaultRepository): Tool = Tool(
    name = "vault_credential_meta",
    description =
        "Show one credential entry's full metadata: name / group / description / value length / " +
            "created & updated time / SSH public key (full line + SHA256 fingerprint). " +
            "The secret VALUE is never returned. Public keys are plaintext by design so the AI " +
            "can match them against server authorized_keys to identify which key is which.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("name", buildJsonObject { put("type", "string"); put("description", "Credential name") })
                },
            required = listOf("name"),
        )
    },
    execute = { params ->
        val name = params.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        if (name == null) {
            listOf(UIMessagePart.Text("❌ name is required"))
        } else {
            val entry = repository.getByName(name)
            if (entry == null) {
                listOf(UIMessagePart.Text("❌ Credential does not exist: $name (use vault_credential_names to list available names)"))
            } else {
                // 失败时给出**具体原因**：只说"(解析失败)"会让人以为是 App 读不动，
                // 实际多数是库里那一行公钥本身写坏了（指纹/判重/部署全都会连带失效）
                val fp = when {
                    entry.publicKey.isBlank() -> "(no public key)"
                    else -> SshKeyGenerator.fingerprint(entry.publicKey)
                        ?: "(unparsable: ${SshKeyGenerator.publicKeyLineIssue(entry.publicKey)})"
                }
                val dtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).withZone(ZoneId.systemDefault())
                val created = dtFmt.format(Instant.ofEpochMilli(entry.createdAt))
                val updated = dtFmt.format(Instant.ofEpochMilli(entry.updatedAt))
                listOf(
                    UIMessagePart.Text(
                        buildString {
                            append("Credential: ${entry.name} [${entry.grp}]\n")
                            append("Description: ${entry.description.ifEmpty { "(empty)" }}\n")
                            append("Value length: ${entry.valueLength} (stored encrypted, invisible to AI)\n")
                            append("Created: $created | Updated: $updated\n")
                            append("Public key fingerprint: $fp\n")
                            if (entry.publicKey.isNotBlank()) append("Public key (full):\n${entry.publicKey}\n")
                            // 非敏感明文元数据（endpoint/path/header/username/custom.*）：白名单键，秘密不在其中
                            val meta = CredentialMeta.decode(entry.metaJson)
                            if (meta.isNotEmpty()) {
                                append("Metadata (plaintext, non-sensitive):\n")
                                meta.forEach { (k, v) -> append("  $k = $v\n") }
                            }
                        },
                    ),
                )
            }
        }
    },
)

/** 查询密钥使用审计记录（谁在何时调用了哪些凭证）。只读，无需授权。 */
fun vaultCredentialAuditTool(repository: CredentialVaultRepository): Tool = Tool(
    name = "vault_credential_audit",
    description =
        "Query vault audit log: who called which credential and when (view/export/ssh_exec/gen_key/" +
            "update/delete/env_inject etc). Read-only; values never shown. " +
            "Use to trace suspicious usage or answer 'which key is used by whom'.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("name", buildJsonObject { put("type", "string"); put("description", "Optional filter: only show entries for this credential name") })
                    put("limit", buildJsonObject { put("type", "integer"); put("description", "Max rows to return (default 50, max 200)") })
                },
        )
    },
    execute = { params ->
        val name = params.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val limit = (params.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val logs = repository.recentAudit(limit)
            .filter { name == null || it.credentialName == name }
            .sortedByDescending { it.tsMs }
            .take(limit)
        if (logs.isEmpty()) {
            listOf(UIMessagePart.Text(if (name != null) "($name has no audit records)" else "(audit log is empty)"))
        } else {
            val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", java.util.Locale.getDefault()).withZone(ZoneId.systemDefault())
            listOf(
                UIMessagePart.Text(
                    buildString {
                        append("Secret usage records (latest ${logs.size}${if (name != null) ", credential: $name" else ""}):\n")
                        logs.forEach { l ->
                            append("${fmt.format(java.time.Instant.ofEpochMilli(l.tsMs))} | ${l.caller} | ${l.action} | ${l.credentialName}\n")
                        }
                    },
                ),
            )
        }
    },
)
