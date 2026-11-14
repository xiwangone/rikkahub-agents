package me.rerere.rikkahub.data.vault

import me.rerere.common.http.optBoolean
import me.rerere.common.http.optInt
import me.rerere.common.http.optString
import me.rerere.rikkahub.data.log.AppLog
import org.koin.java.KoinJavaComponent.getKoin
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Vault 凭证导入导出与整理工具（`vault_export_loadcreds` / `vault_import_loadcreds` /
 * `vault_compare_loadcreds` / `vault_credential_merge` / `vault_credential_normalize_names`）。
 *
 * 安全模型：
 * - `vault_export_loadcreds`：需有效 Vault 授权与调用审批，将解密后的凭证明文导出写入工作区脚本
 *   （`/workspace/tmp/load-creds-export.sh`）；明文仅存在于该工作区文件内，工具向 AI 返回的内容
 *   不含明文，仅含导出条数、文件路径与用完删除提醒。
 * - 其余导入、比对、合并与名称规范化工具仅在 App 进程内读写凭证库，工具返回值只含变量名与变更摘要。
 */

/** 将凭证库导出为 load-creds.sh 风格脚本（含分组/描述/公钥注释，与导入格式对称）。 */
fun vaultExportLoadCredsTool(
    context: android.content.Context,
    repository: CredentialVaultRepository,
): Tool = Tool(
    name = "vault_export_loadcreds",
    description =
        "Export the whole vault as a load-creds.sh style shell script and write it to the workspace " +
            "at /workspace/tmp/load-creds-export.sh. The script carries group headers, description comments, " +
            "SSH public key comments and export lines — fully symmetric with the importer, so editing the " +
            "script and importing it back loses nothing (value / description / group / public key). " +
            "Requires active vault authorization. Plaintext secrets appear ONLY inside the generated file " +
            "on the workspace; delete it after use.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    // 目标工作区：不传 = 第一个本地工作区；传了 = 指定区（名字或 id，SSH 区需再传 confirm=true）。
                    put(
                        "workspace",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Target workspace name or id; default = first local workspace")
                        },
                    )
                    put(
                        "confirm",
                        buildJsonObject {
                            put("type", "boolean")
                            put("description", "Set true to confirm a remote (SSH) workspace as the source or destination for plaintext credentials. Required when workspace names an SSH workspace; ignored for local workspaces. Default false (remote targets are refused without it).")
                        },
                    )
                    put(
                        "ttl_hours",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "Lifetime hint written into the file header as expires_at (hours; 0 or omitted = never expire)")
                        },
                    )
                },
            required = emptyList(),
        )
    },
    needsApproval = { true },
    execute = { params ->
        val sessionManager = VaultSessionManager(context)
        if (!sessionManager.hasActiveAuthorization()) {
            listOf(UIMessagePart.Text("❌ Not authorized: complete Vault authorization first (30 minutes or always valid), then call this tool"))
        } else {
            val entries = repository.getAll()
            // 名字级收敛：整库导出只导出白名单内的条目——受限会话不能把整个库倒出去。
            val quads =
                entries.mapNotNull { e ->
                    if (!sessionManager.hasActiveAuthorization(e.name)) {
                        null
                    } else {
                        repository.decryptValue(e)?.let { VaultExporter.Quad(e.name, it, e.description, e.grp, e.publicKey, e.type) }
                    }
                }
            if (quads.isEmpty()) {
                if (entries.isEmpty()) {
                    listOf(UIMessagePart.Text("❌ The vault is empty, nothing to export"))
                } else {
                    listOf(UIMessagePart.Text("❌ No credentials exported: session is not authorized for any entry in the vault"))
                }
            } else {
                val ttlHours = (params.jsonObject.optInt("ttl_hours") ?: 0).coerceIn(0, 24 * 365)
                val expiresAtMs = if (ttlHours > 0) System.currentTimeMillis() + ttlHours * 3_600_000L else null
                val expiresLabel = expiresAtMs?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "never"
                val script = "# expires_at: $expiresLabel\n" + VaultExporter.toLoadCreds(quads)
                val wsRepository =
                    runCatching { getKoin().get<me.rerere.rikkahub.data.repository.WorkspaceRepository>() }.getOrNull()
                        ?: return@Tool listOf(UIMessagePart.Text("❌ Workspace unavailable"))
                val target =
                    resolveVaultWorkspace(wsRepository, params.jsonObject.optString("workspace"))
                        .getOrElse { e -> return@Tool listOf(UIMessagePart.Text("❌ ${e.message ?: "Cannot resolve workspace"}")) }
                // 远程目标二次确认：显式指向 SSH 工作区时必须传 confirm=true。
                remoteTargetNeedsConfirm(target, params.jsonObject.optBoolean("confirm") ?: false)
                    ?.let { reason -> return@Tool listOf(UIMessagePart.Text("❌ $reason")) }
                runCatching { wsRepository.writeText(target.id, "tmp/load-creds-export.sh", script, overwrite = true) }
                    .onFailure { AppLog.w("VaultTools", "Failed to write load-creds-export.sh", it) }
                    .getOrNull()
                    ?: return@Tool listOf(UIMessagePart.Text("❌ Failed to write the workspace file"))
                quads.forEach { q -> repository.logAccess(q.name, "ai-tool", "export_loadcreds") }
                listOf(
                    UIMessagePart.Text(
                        "✅ Exported ${quads.size} credentials to /workspace/tmp/load-creds-export.sh\n" +
                            "Target: ${vaultTargetNote(target, "/workspace/tmp/load-creds-export.sh")}\n" +
                            "expires_at: $expiresLabel\n" +
                            "Includes group/description/SSH公钥 comments. After editing, sync it back with vault_import_loadcreds or the UI import.\n" +
                            "⚠️ This file contains plaintext secrets; delete it when done.",
                    ),
                )
            }
        }
    },
)


/**
 * 合并两条凭证：把 [source] 的引用全部指向 [target]，然后删除 [source]。
 *
 * 典型场景：同一密钥被重复录入两条（如 `X_TOKEN` 与 `X_TOKEN_1`）。
 * 先比对**值指纹**（不见值）报告二者是否一致，再统一引用指向并删除多余条目——
 * 这样既不会留下悬空引用，也不会因为"看起来一样"而误删不同内容。
 */
@Suppress("TooGenericExceptionCaught") // 引用同步横跨DataStore与Room，任何失败时都必须保留源凭证。
fun vaultCredentialMergeTool(
    repository: CredentialVaultRepository,
    settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
    sshHostRepository: me.rerere.rikkahub.data.repository.SshHostRepository,
    telegramBotPreferences: me.rerere.rikkahub.data.telegram.TelegramBotPreferences,
    context: android.content.Context? = null,
): Tool = Tool(
    name = "vault_credential_merge",
    description =
        "Merge one credential into another: repoint every configuration reference from `source` " +
            "to `target`, then delete `source`. Value fingerprints are compared first (values are never " +
            "returned) so you can report whether the two entries actually hold the same secret. " +
            "Use to clean up duplicates without leaving dangling references.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("source", buildJsonObject { put("type", "string"); put("description", "Credential to remove (its references get repointed)") })
                    put("target", buildJsonObject { put("type", "string"); put("description", "Credential to keep") })
                },
            required = listOf("source", "target"),
        )
    },
    needsApproval = { true },
    execute = { params ->
        if (context != null && !VaultSessionManager(context).hasActiveAuthorization()) {
            return@Tool listOf(UIMessagePart.Text("❌ Not authorized: complete Vault authorization first (30 minutes or always valid), then call this tool"))
        }
        val source = params.jsonObject.optString("source")?.trim().orEmpty()
        val target = params.jsonObject.optString("target")?.trim().orEmpty()
        if (source.isEmpty() || target.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text("❌ Both source and target are required"))
        }
        if (source == target) {
            return@Tool listOf(UIMessagePart.Text("❌ source and target are the same, nothing to merge"))
        }
        // 名字级收敛：合并会解密两条凭证的值做指纹比对并删除源条目——受限会话只能合
        // 并白名单内的凭证；无 context 时沿用旧口径（本工具的授权门本身就是 context 非空才生效）。
        if (context != null) {
            val sessionManager = VaultSessionManager(context)
            val denied = listOf(source, target).firstOrNull { !sessionManager.hasActiveAuthorization(it) }
            if (denied != null) {
                return@Tool listOf(UIMessagePart.Text("❌ Not authorized: session is not authorized for credential $denied"))
            }
        }
        val sourceEntry = repository.getByName(source)
            ?: return@Tool listOf(UIMessagePart.Text("❌ Source credential does not exist: $source"))
        val targetEntry = repository.getByName(target)
            ?: return@Tool listOf(UIMessagePart.Text("❌ Target credential does not exist: $target"))

        // 值指纹比对（不见值）：仅用于提示，是否合并由调用方决定
        val sourceValue = repository.decryptValue(sourceEntry)
        val targetValue = repository.decryptValue(targetEntry)
        val sameValue =
            sourceValue != null && targetValue != null &&
                repository.fingerprint(sourceValue) ==
                    repository.fingerprint(targetValue)

        val synced =
            try {
                VaultReferenceSync.renameEverywhere(
                    settingsStore = settingsStore,
                    sshHostRepository = sshHostRepository,
                    telegramBotPreferences = telegramBotPreferences,
                    oldName = source,
                    newName = target,
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        "⚠️ Reference sync failed; source credential \"$source\" is kept and references may be partially updated. Please verify and retry. Reason: ${error.message ?: error::class.simpleName}",
                    ),
                )
            }
        repository.delete(sourceEntry)
        repository.logAccess(source, "ai-tool", "merge_from")
        repository.logAccess(target, "ai-tool", "merge_into")

        listOf(
            UIMessagePart.Text(
                "✅ Merged: $source → $target\n" +
                    "Value comparison: " + if (sameValue) "identical content (same fingerprint)" else "⚠ The contents **differ**; kept $target as you specified" + "\n" +
                    "Reference sync: updated $synced config reference(s)\n" +
                    "(values are not readable/writable; restore from a .vault backup to roll back)",
            ),
        )
    },
)


/**
 * 改名（含**引用同步**）的公共实现——被 update 工具与规范化助手共用。
 *
 * 顺序有意为之：先建新名（含原密文）→ 同步引用 → 再删旧名；
 * 任一步失败都不会让配置指向一个不存在的名字。
 *
 * @return 同步更新的配置引用处数
 */
suspend fun renameCredentialWithRefs(
    repository: CredentialVaultRepository,
    settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
    sshHostRepository: me.rerere.rikkahub.data.repository.SshHostRepository,
    telegramBotPreferences: me.rerere.rikkahub.data.telegram.TelegramBotPreferences,
    oldName: String,
    newName: String,
): Int {
    val existing = repository.getByName(oldName) ?: return 0
    val value = repository.decryptValue(existing) ?: return 0
    repository.save(
        name = newName,
        value = value,
        description = existing.description,
        group = existing.grp,
        publicKey = existing.publicKey,
        type = existing.type,
        // 改名必须连元数据一起搬过去：endpoint/path/header/username 是 provider 解析
        // 引用处要用的，漏传 = 改个名字就把这条凭证的用法信息洗掉了
        metaJson = existing.metaJson,
    )
    val synced = VaultReferenceSync.renameEverywhere(settingsStore, sshHostRepository, telegramBotPreferences, oldName, newName)
    repository.delete(existing)
    repository.logAccess(oldName, "ai-tool", "rename_from")
    repository.logAccess(newName, "ai-tool", "rename_to")
    return synced
}

/** 受限会话下改名：源名与目标名都必须在白名单内，否则返回第一个未授权的名字，无则 null。 */
private suspend fun deniedRenameName(
    sessionManager: VaultSessionManager?,
    oldName: String,
    newName: String,
): String? {
    if (sessionManager == null) return null
    return listOf(oldName, newName).firstOrNull { !sessionManager.hasActiveAuthorization(it) }
}

/**
 * 命名规范化：把不符合命名约定的存量名字改成合规形式（并同步引用）。
 *
 * 背景：历史数据里常有小写、连字符、空格甚至中文的名字；手工逐条改既慢又容易漏掉引用。
 * 默认 **dry_run**：先列出"现名 → 建议名"，确认后再执行。
 */
@Suppress("TooGenericExceptionCaught") // 逐项规范化时跨存储失败必须保留源凭证并继续后续项目。
fun vaultCredentialNormalizeTool(
    repository: CredentialVaultRepository,
    settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
    sshHostRepository: me.rerere.rikkahub.data.repository.SshHostRepository,
    telegramBotPreferences: me.rerere.rikkahub.data.telegram.TelegramBotPreferences,
    context: android.content.Context? = null,
): Tool = Tool(
    name = "vault_credential_normalize_names",
    description =
        "Normalize legacy credential names into the required UPPER_SNAKE form (and rewrite references). " +
            "Defaults to dry_run=true which only LISTS suggested renames; pass dry_run=false to apply. " +
            "Renames are applied only when the suggested name is free — conflicting ones are reported and skipped. " +
            "Values are never read out.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put(
                        "dry_run",
                        buildJsonObject {
                            put("type", "boolean")
                            put("description", "true (default) = only list suggestions; false = apply renames")
                        },
                    )
                },
        )
    },
    execute = { params ->
        val dryRun = params.jsonObject.optString("dry_run")?.toBooleanStrictOrNull() ?: true
        val entries = repository.getAll()
        val taken = entries.map { it.name }.toMutableSet()

        data class Plan(val old: String, val new: String, val conflict: Boolean)
        val plans = mutableListOf<Plan>()
        entries.forEach { entry ->
            val suggested = CredentialVaultRepository.normalizeName(entry.name)
            if (suggested != entry.name) {
                // 目标名是否已被占用（含本轮已分配的建议名）
                val conflict = suggested in taken
                plans += Plan(entry.name, suggested, conflict)
                taken += suggested
            }
        }

        if (plans.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text("✅ All credential names follow the rules, nothing to adjust"))
        }

        if (dryRun) {
            val body = buildString {
                appendLine("Would adjust ${plans.size} entries (dry_run, not executed):")
                plans.forEach { p ->
                    appendLine("- ${p.old} → ${p.new}" + if (p.conflict) "  ⚠ The target name may already be taken; it will be skipped on execution" else "")
                }
                append("Once confirmed, run with dry_run=false (config references are synced automatically).")
            }
            return@Tool listOf(UIMessagePart.Text(body))
        }
        if (context != null && !VaultSessionManager(context).hasActiveAuthorization()) {
            return@Tool listOf(UIMessagePart.Text("❌ Not authorized: complete Vault authorization first (30 minutes or always valid), then run with dry_run=false"))
        }

        var done = 0
        var skipped = 0
        var syncedTotal = 0
        val failures = mutableListOf<String>()
        // 有 context 时才做名字级收敛（本工具的授权门本身就是 context 非空才生效）。
        val sessionManager = context?.let { VaultSessionManager(it) }
        plans.forEach { p ->
            // 源名与目标名都要在白名单内——否则受限会话能把任意条目改名成白名单内的
            // 名字再用它；跳过的条目进 failures，不静默。
            val denied = deniedRenameName(sessionManager, p.old, p.new)
            if (denied != null) {
                skipped++
                failures += "${p.old} → ${p.new}: session is not authorized for credential $denied"
                return@forEach
            }
            if (repository.getByName(p.new) != null) {
                skipped++
                return@forEach
            }
            try {
                val synced = renameCredentialWithRefs(repository, settingsStore, sshHostRepository, telegramBotPreferences, p.old, p.new)
                done++
                syncedTotal += synced
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                skipped++
                failures += "${p.old} → ${p.new}: sync failed (${error.message ?: error::class.simpleName}); source credential kept, references may be partially updated"
            }
        }
        listOf(
            UIMessagePart.Text(
                buildString {
                    append("✅ Normalized $done entries (skipped/failed $skipped)\nReference sync: updated $syncedTotal config reference(s) in total")
                    if (failures.isNotEmpty()) {
                        appendLine("\nFailed items:")
                        failures.forEach { appendLine("- $it") }
                        append("The target entry may already be created; verify the references, then manually merge/clean up the source entry.")
                    }
                },
            ),
        )
    },
)

/** 从工作区 load-creds.sh 导入（解析含公钥/描述/分组），供 AI 全流程维护凭证库。 */
fun vaultImportLoadCredsTool(
    context: android.content.Context,
    repository: CredentialVaultRepository,
): Tool = Tool(
    name = "vault_import_loadcreds",
    description =
        "Import credentials from a load-creds.sh style script already written in the workspace " +
            "(e.g. /workspace/tmp/load-creds-export.sh or a user-provided script). Parses group headers, " +
            "description comments, SSH public key comments and export lines, then upserts into the vault " +
            "(blank value on existing entry keeps the old secret; blank public key keeps the old key). " +
            "Returns how many entries were imported/updated.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string"); put("description", "Workspace path of the script, default /workspace/tmp/load-creds-export.sh") })
                put("workspace", buildJsonObject { put("type", "string"); put("description", "Target workspace name or id; default = first local workspace") })
                put("confirm", buildJsonObject { put("type", "boolean"); put("description", "Set true to confirm a remote (SSH) workspace as the source or destination for plaintext credentials. Required when workspace names an SSH workspace; ignored for local workspaces. Default false (remote targets are refused without it).") })
            },
            required = emptyList(),
        )
    },
    needsApproval = { true },
    execute = { params ->
        val sessionManager = VaultSessionManager(context)
        if (!sessionManager.hasActiveAuthorization()) {
            listOf(UIMessagePart.Text("❌ Not authorized: complete Vault authorization first (30 minutes or always valid), then call this tool"))
        } else {
            val path = params.jsonObject.optString("path")?.ifBlank { null } ?: "/workspace/tmp/load-creds-export.sh"
            val wsRepository =
                runCatching { getKoin().get<me.rerere.rikkahub.data.repository.WorkspaceRepository>() }.getOrNull()
                    ?: return@Tool listOf(UIMessagePart.Text("❌ Workspace unavailable"))
            val target =
                resolveVaultWorkspace(wsRepository, params.jsonObject.optString("workspace"))
                    .getOrElse { e -> return@Tool listOf(UIMessagePart.Text("❌ ${e.message ?: "Cannot resolve workspace"}")) }
            // 远程目标二次确认：显式指向 SSH 工作区时必须传 confirm=true。
            remoteTargetNeedsConfirm(target, params.jsonObject.optBoolean("confirm") ?: false)
                ?.let { reason -> return@Tool listOf(UIMessagePart.Text("❌ $reason")) }
            val content =
                runCatching {
                    val buf = java.io.ByteArrayOutputStream()
                    wsRepository.exportAbsoluteFile(target.id, path, buf)
                    buf.toString(Charsets.UTF_8.name())
                }.getOrNull()
            if (content == null) {
                listOf(UIMessagePart.Text("❌ Failed to read file: $path"))
            } else {
                val parsed = CredentialImporter.parse(content)
                if (parsed.isEmpty()) {
                    listOf(UIMessagePart.Text("❌ No entries parsed (does the format match load-creds.sh?)"))
                } else {
                    val result = repository.importEntries(parsed)
                    parsed.forEach { p -> repository.logAccess(p.name, "ai-tool", "import_loadcreds") }
                    listOf(
                        UIMessagePart.Text(
                            buildString {
                                append("✅ Imported/updated ${result.imported} credentials (parsed ${parsed.size})")
                                if (result.skipped > 0) append("; skipped ${result.skipped} (invalid name or value)")
                                if (result.overwrittenDifferentValue.isNotEmpty()) {
                                    append("\n⚠ The following entries had the same name but different values and were overwritten by the imported content:")
                                    result.overwrittenDifferentValue.forEach { append("\n- " + it) }
                                }
                            },
                        ),
                    )
                }
            }
        }
    },
)

/** 凭证库与 load-creds.sh 文件核对（哈希比对，AI 不见值明文）。 */
fun vaultCompareLoadCredsTool(
    context: android.content.Context,
    repository: CredentialVaultRepository,
): Tool = Tool(
    name = "vault_compare_loadcreds",
    description =
        "Compare the vault against a load-creds.sh style file in the workspace (default " +
            "/workspace/tmp/load-creds.sh). Reports: names only in vault / only in file / in both; " +
            "for shared names, value equality is checked via SHA-256 hash (the AI only sees 'same' or " +
            "'different', never the plaintext value); description/group/public-key differences are shown " +
            "as plaintext (public keys are public). Use before import/export to know what changed; " +
            "the fix direction (file-wins or vault-wins) must be decided by the user since values are " +
            "never revealed.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string"); put("description", "Workspace path of the script, default /workspace/tmp/load-creds.sh") })
                put("workspace", buildJsonObject { put("type", "string"); put("description", "Target workspace name or id; default = first local workspace") })
                put("confirm", buildJsonObject { put("type", "boolean"); put("description", "Set true to confirm a remote (SSH) workspace as the source or destination for plaintext credentials. Required when workspace names an SSH workspace; ignored for local workspaces. Default false (remote targets are refused without it).") })
            },
            required = emptyList(),
        )
    },
    execute = { params ->
        val sessionManager = VaultSessionManager(context)
        if (!sessionManager.hasActiveAuthorization()) {
            listOf(UIMessagePart.Text("❌ Not authorized: complete Vault authorization first (30 minutes or always valid), then call this tool"))
        } else {
            val path = params.jsonObject.optString("path")?.ifBlank { null } ?: "/workspace/tmp/load-creds.sh"
            val wsRepository =
                runCatching { getKoin().get<me.rerere.rikkahub.data.repository.WorkspaceRepository>() }.getOrNull()
                    ?: return@Tool listOf(UIMessagePart.Text("❌ Workspace unavailable"))
            val target =
                resolveVaultWorkspace(wsRepository, params.jsonObject.optString("workspace"))
                    .getOrElse { e -> return@Tool listOf(UIMessagePart.Text("❌ ${e.message ?: "Cannot resolve workspace"}")) }
            // 远程目标二次确认：显式指向 SSH 工作区时必须传 confirm=true。
            remoteTargetNeedsConfirm(target, params.jsonObject.optBoolean("confirm") ?: false)
                ?.let { reason -> return@Tool listOf(UIMessagePart.Text("❌ $reason")) }
            val content =
                runCatching {
                    val buf = java.io.ByteArrayOutputStream()
                    wsRepository.exportAbsoluteFile(target.id, path, buf)
                    buf.toString(Charsets.UTF_8.name())
                }.getOrNull()
            if (content == null) {
                listOf(UIMessagePart.Text("❌ Failed to read file: $path (upload it first, or generate it with vault_export_loadcreds)"))
            } else {
                val fileEntries = CredentialImporter.parse(content).associateBy { it.name }
                if (fileEntries.isEmpty()) {
                    listOf(UIMessagePart.Text("❌ No entries parsed from the file (does the format match load-creds.sh?)"))
                } else {
                    // 名字级收敛：比对只覆盖白名单内的条目——否则用文件里的猜测值比对任意
                    // 条目的哈希，就能当预言机逐个试出低熵值。白名单外的条目在报告里
                    // 表现为"只在文件里"，不泄露它在库里、也不泄露值是否相同。
                    val vaultEntries = repository.getAll()
                        .filter { sessionManager.hasActiveAuthorization(it.name) }
                        .associateBy { it.name }
                    listOf(UIMessagePart.Text(buildVaultCompareReport(path, fileEntries, vaultEntries, repository)))
                }
            }
        }
    },
)

private suspend fun buildVaultCompareReport(
    path: String,
    fileEntries: Map<String, CredentialImporter.ParsedEntry>,
    vaultEntries: Map<String, me.rerere.rikkahub.data.db.entity.VaultCredentialEntity>,
    repository: CredentialVaultRepository,
): String {
    val sha = java.security.MessageDigest.getInstance("SHA-256")
    fun hash(s: String): String =
        java.util.Base64.getEncoder().encodeToString(sha.digest(s.encodeToByteArray()))
    suspend fun vaultValueHash(name: String): String? =
        vaultEntries[name]?.let { repository.decryptValue(it) }?.let { hash(it) }

    val onlyVault = vaultEntries.keys - fileEntries.keys
    val onlyFile = fileEntries.keys - vaultEntries.keys
    val shared = vaultEntries.keys intersect fileEntries.keys

    val sameValues = mutableListOf<String>()
    val diffValues = mutableListOf<String>()
    val diffMeta = mutableListOf<String>()
    for (name in shared.sorted()) {
        val fe = fileEntries.getValue(name)
        val ve = vaultEntries.getValue(name)
        if (hash(fe.value) == vaultValueHash(name)) {
            sameValues += name
        } else {
            diffValues += "$name (different value)"
        }
        val metaDiff = listOfNotNull(
            "Description".takeIf { fe.description != ve.description },
            "Group".takeIf { fe.group != ve.grp },
            "Public key".takeIf { fe.publicKey != ve.publicKey },
        )
        if (metaDiff.isNotEmpty()) diffMeta += "$name（${metaDiff.joinToString("/")}）"
    }

    return buildString {
        append("Reconciliation report (vault ↔ $path)\n")
        append("File entries ${fileEntries.size} | vault entries ${vaultEntries.size}\n")
        if (onlyVault.isNotEmpty()) append("🔹 Only in vault (${onlyVault.size}): ${onlyVault.sorted().joinToString(", ")}\n")
        if (onlyFile.isNotEmpty()) append("🔹 Only in file (${onlyFile.size}): ${onlyFile.sorted().joinToString(", ")}\n")
        append("✅ Values match (${sameValues.size}): ${sameValues.take(30).joinToString(", ")}${if (sameValues.size > 30) "…" else ""}\n")
        if (diffValues.isNotEmpty()) append("⚠️ Values differ (${diffValues.size}): ${diffValues.joinToString("; ")}\n")
        if (diffMeta.isNotEmpty()) append("ℹ️ Metadata differs (${diffMeta.size}): ${diffMeta.joinToString("; ")}\n")
        if (onlyVault.isEmpty() && onlyFile.isEmpty() && diffValues.isEmpty()) {
            append("🎉 All values match.")
            if (diffMeta.isEmpty()) append("Descriptions, groups, and public keys match too.")
        } else {
            append("\nWhich side wins needs user confirmation: file wins → vault_import_loadcreds; vault wins → vault_export_loadcreds to overwrite the file.")
        }
    }
}
