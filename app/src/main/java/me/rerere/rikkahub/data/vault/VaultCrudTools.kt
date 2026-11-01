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
            listOf(UIMessagePart.Text("❌ name 必填"))
        } else {
            val sessionManager = VaultSessionManager(context)
            if (!sessionManager.hasActiveAuthorization()) {
                listOf(UIMessagePart.Text("❌ 未授权：请先完成 Vault 授权（30 分钟或一直有效）再调用本工具"))
            } else {
                val existing = repository.getByName(name)
                if (existing == null) {
                    listOf(UIMessagePart.Text("❌ 凭证不存在: $name（用 vault_credential_names 查看可用名称）"))
                } else {
                    val newName = o["new_name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                    val desc = o["description"]?.jsonPrimitive?.contentOrNull
                    val group = o["group"]?.jsonPrimitive?.contentOrNull
                    val typeParam = o["type"]?.jsonPrimitive?.contentOrNull
                    val metaParam = o["meta"]?.let { runCatching { it.jsonObject }.getOrNull() }
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
                            return@Tool listOf(UIMessagePart.Text("❌ 目标名称已存在: $newName（改名冲突，请选其他名称）"))
                        }
                        changed += "名称: $name → $newName"
                    }
                    if (desc != null && desc != existing.description) changed += "描述"
                    if (group != null && group != existing.grp) changed += "分组"
                    if (typeParam != null && typeParam != existing.type) changed += "类型"
                    if (metaParam != null && mergedMetaJson != existing.metaJson) changed += "元数据"
                    if (pubParam != null && newPub != existing.publicKey) changed += if (newPub.isNullOrEmpty()) "公钥(清空)" else "公钥"
                    if (changed.isEmpty()) {
                        listOf(UIMessagePart.Text("ℹ️ 没有需要更新的字段（当前已是最新）"))
                    } else {
                        val value = repository.decryptValue(existing)
                            ?: return@Tool listOf(UIMessagePart.Text("❌ 解密失败，已中止更新以避免用空值覆盖凭据（密钥可能临时不可用，请稍后重试）"))
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
                                            "⚠️ 引用同步失败；源凭证「$name」仍保留，目标「$targetName」也已创建，引用可能部分更新。请核对引用后再执行合并/清理。原因：${error.message ?: error::class.simpleName}",
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
                                "✅ 已更新凭证元数据：${targetName}\n变更：${changed.joinToString("；")}\n"
                                    + if (targetName != name) {
                                        "引用同步：已更新 $syncedRefs 处配置引用" +
                                            if (syncedRefs == 0) "（没有任何配置引用旧名）" else ""
                                    } else {
                                        ""
                                    }
                                    + "\n（值未改动，AI 不可读写密钥值）",
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
            listOf(UIMessagePart.Text("❌ updates 必填且不能为空（数组）"))
        } else {
            val sessionManager = VaultSessionManager(context)
            if (!sessionManager.hasActiveAuthorization()) {
                listOf(UIMessagePart.Text("❌ 未授权：请先完成 Vault 授权（30 分钟或一直有效）再调用本工具"))
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
                    if (value == null) preflightErrors += "第${item + 1}条：$key 必须是字符串"
                    return value
                }

                items.forEachIndexed { index, element ->
                    val o = element as? JsonObject
                    if (o == null) {
                        preflightErrors += "第${index + 1}条：必须是对象"
                        return@forEachIndexed
                    }
                    val name = readString(o, "name", index)?.ifBlank { null }
                    if (name == null) {
                        preflightErrors += "第${index + 1}条：缺少有效 name"
                        return@forEachIndexed
                    }
                    if (!seenNames.add(name)) {
                        preflightErrors += "重复更新来源：$name"
                        return@forEachIndexed
                    }

                    val requestedName = readString(o, "new_name", index)?.ifBlank { null }
                    val descriptionParam = readString(o, "description", index)
                    val groupParam = readString(o, "group", index)
                    val typeParam = readString(o, "type", index)
                    val metaParam = if ("meta" in o) o["meta"] as? JsonObject else null
                    if ("meta" in o && metaParam == null) {
                        preflightErrors += "第${index + 1}条：meta 必须是对象"
                    }
                    if (!CredentialVaultRepository.validateCredentialName(name)) {
                        preflightErrors += "第${index + 1}条：来源名称不符合凭证库命名规范：$name"
                    }
                    if (typeParam != null && !CredentialType.isValid(typeParam)) {
                        preflightErrors += "第${index + 1}条：未知凭证类型 '$typeParam'"
                    }

                    val existing = runCatching { repository.getByName(name) }.getOrElse {
                        preflightErrors += "第${index + 1}条：无法读取凭证 $name"
                        return@forEachIndexed
                    }
                    if (existing == null) {
                        preflightErrors += "第${index + 1}条：不存在凭证 $name"
                        return@forEachIndexed
                    }

                    val targetName = requestedName ?: name
                    if (targetName != name && !CredentialVaultRepository.validateCredentialName(targetName)) {
                        preflightErrors += "第${index + 1}条：新名称不合规（须大写蛇形且不超过 64 字符）：$targetName"
                    }
                    if (!seenTargets.add(targetName)) {
                        preflightErrors += "多个更新指向同一目标名称：$targetName"
                    }
                    if (targetName != name) {
                        val targetLookup = runCatching { repository.getByName(targetName) }
                        if (targetLookup.isFailure) {
                            preflightErrors += "第${index + 1}条：无法检查目标名称是否可用：$targetName"
                        } else if (targetLookup.getOrNull() != null) {
                            preflightErrors += "第${index + 1}条：目标名称已存在：$targetName"
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
                        if (targetName != name) add("改名→$targetName")
                        if (description != existing.description) add("描述")
                        if (group != existing.grp) add("分组→$group")
                        if (type != existing.type) add("类型→$type")
                        if (metaJson != existing.metaJson) add("元数据")
                    }
                    val secretValue = if (changed.isEmpty() || existing.valueEncrypted.isBlank()) {
                        ""
                    } else {
                        repository.decryptValue(existing) ?: run {
                            preflightErrors += "第${index + 1}条：无法解密 $name；为避免覆盖成空值已中止"
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
                            "批量更新预检失败：未执行任何写入。\n" + preflightErrors.joinToString("\n") { "❌ $it" },
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
                            lines += "ℹ️ ${update.name} 无变化"
                            continue
                        }
                        var targetCreated = false
                        try {
                            val current = repository.getByName(update.name)
                            if (current == null || current.updatedAt != update.expectedUpdatedAt) {
                                failed++
                                lines += "❌ ${update.name} 在预检后已变化，请重新读取后重试"
                                continue
                            }
                            if (update.targetName != update.name && repository.getByName(update.targetName) != null) {
                                failed++
                                lines += "❌ ${update.name} → ${update.targetName}：目标名称在执行前已被占用"
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
                                "⚠️ ${update.name} → ${update.targetName} 未完成：新旧条目均保留且引用可能部分同步；先核对引用，确认一致后再人工清理旧条目。"
                            } else if (targetCreated && targetRemains) {
                                "⚠️ ${update.name} → ${update.targetName}：目标条目存在，源条目状态需核对；请检查引用和 Vault 后再处理。"
                            } else {
                                "❌ ${update.name} 更新失败（${error::class.simpleName}）"
                            }
                        }
                    }
                    listOf(
                        UIMessagePart.Text(
                            "批量更新完成：成功 $ok / 无变化 $noChange / 失败或待恢复 $failed\n" + lines.joinToString("\n"),
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
            listOf(UIMessagePart.Text("❌ name 必填"))
        } else {
            val sessionManager = VaultSessionManager(context)
            if (!sessionManager.hasActiveAuthorization()) {
                listOf(UIMessagePart.Text("❌ 未授权：请先完成 Vault 授权（30 分钟或一直有效）再调用本工具"))
            } else {
                val existing = repository.getByName(name)
                if (existing == null) {
                    listOf(UIMessagePart.Text("❌ 凭证不存在: $name（用 vault_credential_names 查看可用名称）"))
                } else {
                    repository.delete(existing)
                    repository.logAccess(name, "ai-tool", "delete")
                    listOf(UIMessagePart.Text("🗑️ 已删除凭证: $name"))
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
            listOf(UIMessagePart.Text("❌ name 必填"))
        } else {
            val entry = repository.getByName(name)
            if (entry == null) {
                listOf(UIMessagePart.Text("❌ 凭证不存在: $name（用 vault_credential_names 查看可用名称）"))
            } else {
                // 失败时给出**具体原因**：只说"(解析失败)"会让人以为是 App 读不动，
                // 实际多数是库里那一行公钥本身写坏了（指纹/判重/部署全都会连带失效）
                val fp = when {
                    entry.publicKey.isBlank() -> "(无公钥)"
                    else -> SshKeyGenerator.fingerprint(entry.publicKey)
                        ?: "(无法解析：${SshKeyGenerator.publicKeyLineIssue(entry.publicKey)})"
                }
                val dtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).withZone(ZoneId.systemDefault())
                val created = dtFmt.format(Instant.ofEpochMilli(entry.createdAt))
                val updated = dtFmt.format(Instant.ofEpochMilli(entry.updatedAt))
                listOf(
                    UIMessagePart.Text(
                        buildString {
                            append("凭证: ${entry.name}  [${entry.grp}]\n")
                            append("描述: ${entry.description.ifEmpty { "(空)" }}\n")
                            append("值长度: ${entry.valueLength}（已加密存储，AI 不可见）\n")
                            append("创建: $created | 更新: $updated\n")
                            append("公钥指纹: $fp\n")
                            if (entry.publicKey.isNotBlank()) append("公钥全文:\n${entry.publicKey}\n")
                            // 非敏感明文元数据（endpoint/path/header/username/custom.*）：白名单键，秘密不在其中
                            val meta = CredentialMeta.decode(entry.metaJson)
                            if (meta.isNotEmpty()) {
                                append("元数据（明文，非敏感）:\n")
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
            listOf(UIMessagePart.Text(if (name != null) "（$name 无审计记录）" else "（审计记录为空）"))
        } else {
            val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", java.util.Locale.getDefault()).withZone(ZoneId.systemDefault())
            listOf(
                UIMessagePart.Text(
                    buildString {
                        append("密钥调用记录（最近 ${logs.size} 条${if (name != null) "，凭证: $name" else ""}）：\n")
                        logs.forEach { l ->
                            append("${fmt.format(java.time.Instant.ofEpochMilli(l.tsMs))} | ${l.caller} | ${l.action} | ${l.credentialName}\n")
                        }
                    },
                ),
            )
        }
    },
)
