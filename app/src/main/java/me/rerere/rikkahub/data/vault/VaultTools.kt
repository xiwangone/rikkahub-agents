package me.rerere.rikkahub.data.vault

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.koin.java.KoinJavaComponent.getKoin
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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

/** 列出凭证库条目（不含值）。 */
fun vaultCredentialNamesTool(repository: CredentialVaultRepository): Tool = Tool(
    name = "vault_credential_names",
    description =
        "List credentials stored in the vault (names only, never values). " +
            "Supports optional keyword search (matches name / description, case-insensitive), " +
            "optional group filter, and sorting. SSH key entries show their public-key SHA256 fingerprint. " +
            "Pass duplicates=true to also report suspicious same-group-same-length duplicates (read-only hint). " +
            "Results are sorted by group then name by default. " +
            "Use to discover which credential names are available before calling vault_ssh_exec / vault_http_exec.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("group", buildJsonObject { put("type", "string"); put("description", "Filter by group: Git/AI/ECS/MCP/Notification/SSH/Other") })
                    put("keyword", buildJsonObject { put("type", "string"); put("description", "Optional search keyword (matches name or description, case-insensitive)") })
                    put("sort", buildJsonObject { put("type", "string"); put("description", "Sort order: name / group / length (default group-then-name)") })
                    put("duplicates", buildJsonObject { put("type", "boolean"); put("description", "When true, also report suspicious duplicates (same group + same value length) as a read-only hint") })
                },
        )
    },
    execute = { params ->
        val group = params.jsonObject["group"]?.jsonPrimitive?.contentOrNull
        val keyword = params.jsonObject["keyword"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val sort = params.jsonObject["sort"]?.jsonPrimitive?.contentOrNull
        val showDupes = params.jsonObject["duplicates"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val all = repository.getAll()
        val filtered =
            all
                .filter { e -> group == null || e.grp == group }
                .filter { e -> keyword == null || e.name.lowercase().contains(keyword) || e.description.lowercase().contains(keyword) }
        val entries =
            when (sort) {
                "name" -> filtered.sortedBy { it.name.lowercase() }
                "group" -> filtered.sortedWith(compareBy({ it.grp }, { it.name.lowercase() }))
                "length" -> filtered.sortedBy { it.valueLength }
                else -> filtered.sortedWith(compareBy({ it.grp }, { it.name.lowercase() }))
            }
        val sb = StringBuilder()
        if (showDupes) {
            // 疑似重复检测：同分组 + 值长度相同（无法比对明文，故只提示可疑项，不自动合并）
            val dupGroups =
                all.groupBy { it.grp to it.valueLength }
                    .filter { (_, items) -> items.size > 1 }
                    .filter { (_, items) -> items.any { it.valueLength > 0 } }
            if (dupGroups.isEmpty()) {
                sb.append("（未发现疑似重复条目）\n")
            } else {
                sb.append("⚠️ 疑似重复（同分组同长度，AI 无法比对明文，请人工确认）：\n")
                dupGroups.forEach { (key, items) ->
                    sb.append("  [${key.first}] len=${key.second}: ${items.joinToString(" / ") { it.name }}\n")
                }
                sb.append("\n")
            }
        }
        if (entries.isEmpty()) {
            sb.append("（凭证库为空，或无匹配条目）")
        } else {
            sb.append("凭证库条目（${entries.size}）：\n")
            entries.forEach { e ->
                val fp = if (e.publicKey.isNotBlank()) " fp=${SshKeyGenerator.fingerprint(e.publicKey) ?: "?"}" else ""
                sb.append("${e.name}  [${e.grp}] len=${e.valueLength}$fp  ${e.description}\n")
            }
        }
        listOf(UIMessagePart.Text(sb.toString().trimEnd()))
    },
)

/** AI 创建凭证条目占位（不含值），由用户在密钥库最后填写 key/token。 */
fun vaultCredentialPrepareTool(repository: CredentialVaultRepository): Tool = Tool(
    name = "vault_credential_prepare",
    description =
        "Create a credential entry placeholder in the vault (name / description / group, NO value). " +
            "The user fills the actual secret later in the vault UI. Use to prepare a named slot " +
            "before the user provides the key/token — the AI never handles the secret value.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("name", buildJsonObject { put("type", "string"); put("description", "Credential name, e.g. NEW_SERVER_API_KEY") })
                    put("description", buildJsonObject { put("type", "string"); put("description", "What this credential is for") })
                    put("group", buildJsonObject { put("type", "string"); put("description", "Vault group: Git/AI/ECS/MCP/Notification/SSH/Other") })
                    put("public_key", buildJsonObject { put("type", "string"); put("description", "Optional SSH public key line (plaintext, for SSH key entries; helps AI identify the key later)") })
                },
            required = listOf("name"),
        )
    },
    execute = { params ->
        val o = params.jsonObject
        val name = o["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        if (name == null) {
            listOf(UIMessagePart.Text("❌ name 必填"))
        } else {
            val desc = o["description"]?.jsonPrimitive?.contentOrNull ?: ""
            val group = o["group"]?.jsonPrimitive?.contentOrNull ?: "Other"
            val pub = o["public_key"]?.jsonPrimitive?.contentOrNull ?: ""
            repository.save(
                name = name,
                value = "", // 占位：值留空，用户稍后填写
                description = desc,
                group = group,
                publicKey = pub,
            )
            repository.logAccess(name, "ai-tool", "prepare")
            listOf(
                UIMessagePart.Text(
                    "✅ 已创建凭证占位条目：$name [${group}]\n" +
                        "值尚未填写。请用户到 安全凭证库 → $name 编辑，填入实际 key/token。" +
                        if (pub.isNotBlank()) "\n公钥已记录（AI 可据此识别该密钥）。" else "",
                ),
            )
        }
    },
)

/** 生成 SSH 密钥对并保存到凭证库，返回公钥（可配置到服务器 authorized_keys）。 */
fun vaultGenKeyTool(
    context: android.content.Context,
    repository: CredentialVaultRepository,
): Tool = Tool(
    name = "vault_gen_key",
    description =
        "Generate an SSH key pair, store the private key in the vault, and return the public key " +
            "for the user to configure on a server (e.g. ~/.ssh/authorized_keys). " +
            "Use before vault_ssh_exec when the server is new and has no key yet. " +
            "The public key line carries a comment identifying the purpose and RikkaHub Agents as generator.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("name", buildJsonObject { put("type", "string"); put("description", "Credential name (default WEB_SSH_KEY)") })
                    put("group", buildJsonObject { put("type", "string"); put("description", "Vault group (default SSH)") })
                    put("comment", buildJsonObject { put("type", "string"); put("description", "Public key comment suffix, e.g. 'pc-main@rikkahub-agents'. Default 'generated@rikkahub-agents'. Always include purpose + @rikkahub-agents for traceability.") })
                    put("type", buildJsonObject { put("type", "string"); put("description", "Key type: ED25519 (recommended) / RSA / ECDSA (default RSA for backward compat)") })
                },
        )
    },
    execute = { params ->
        val name = params.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "WEB_SSH_KEY"
        val group = params.jsonObject["group"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "SSH"
        val rawComment = params.jsonObject["comment"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val typeStr = params.jsonObject["type"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val keyType =
            when (typeStr?.uppercase()) {
                "ED25519" -> SshKeyGenerator.KeyType.ED25519
                "ECDSA" -> SshKeyGenerator.KeyType.ECDSA
                "RSA", null -> SshKeyGenerator.KeyType.RSA
                else -> return@Tool listOf(UIMessagePart.Text("❌ 不支持的 type: $typeStr（可选 ED25519 / RSA / ECDSA）"))
            }
        // 强制注释带 RikkaHub Agents 标识便于溯源；未指定时默认用途注释
        val comment =
            when {
                rawComment == null -> SshKeyGenerator.DEFAULT_COMMENT
                "@rikkahub-agents" in rawComment || "@rikkahub" in rawComment -> rawComment
                else -> "$rawComment@rikkahub-agents"
            }
        val key = SshKeyGenerator.generate(keyType, comment)
        val fp = SshKeyGenerator.fingerprint(key.publicKeyLine) ?: "?"
        repository.save(
            name = name,
            value = key.privateKeyPem,
            description = "AI 生成 SSH ${keyType.label} 密钥（${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())}）",
            group = group,
            publicKey = key.publicKeyLine,
        )
        repository.logAccess(name, "ai-tool", "gen_key")
        listOf(
            UIMessagePart.Text(
                "✅ 密钥对已生成并保存到凭证库（$name）\n" +
                    "类型：${keyType.label} | 指纹：$fp\n" +
                    "私钥已在 App 内存中存入 Vault（未落盘明文），公钥存于同条目 publicKey 字段（明文）\n" +
                    "请将以下公钥配置到服务器 ~/.ssh/authorized_keys：\n${key.publicKeyLine}",
            ),
        )
    },
)

/** vault_export_env — 工具桥：把密钥库凭证解密值导出为沙箱环境变量文件（AI 不见明文）。 */
fun vaultExportEnvTool(
    context: android.content.Context,
    repository: CredentialVaultRepository,
): Tool = Tool(
    name = "vault_export_env",
    description =
        "Export decrypted vault credentials into a sandbox env file (/workspace/tmp/vault-env.sh) " +
            "so sandbox CLI scripts can source them. Requires an active vault authorization (30min or forever). " +
            "The AI never sees plaintext values — only exported credential names are returned. " +
            "Usage: vault_export_env(names=[...]) or no args to export all. After use, delete the file.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put(
                        "names",
                        buildJsonObject {
                            put("type", "array")
                            put("description", "Credential names to export (default: all)")
                        },
                    )
                },
            required = emptyList(),
        )
    },
    needsApproval = { true },
    execute = { params -> runVaultExportEnv(context, repository, params.jsonObject) },
)

private suspend fun runVaultExportEnv(
    context: android.content.Context,
    repository: CredentialVaultRepository,
    o: kotlinx.serialization.json.JsonObject,
): List<UIMessagePart> {
    val fail: (String) -> List<UIMessagePart> = { msg -> listOf(UIMessagePart.Text("❌ $msg")) }
    val sessionManager = VaultSessionManager(context)
    if (!sessionManager.hasActiveAuthorization()) {
        return fail("未授权：请先在会话输入框旁完成 Vault 授权（30 分钟或一直有效），再调用本工具")
    }
    val requested = o["names"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
    val entries = repository.getAll()
    val selected = if (requested.isNullOrEmpty()) entries else entries.filter { it.name in requested }
    if (selected.isEmpty()) return fail("没有可导出的凭证（vault_credential_names 查看可用名称）")

    val lines = mutableListOf("#!/bin/bash", "# vault-env — vault_export_env 生成，用完请删除: rm /workspace/tmp/vault-env.sh")
    val exported = mutableListOf<String>()
    for (entry in selected) {
        val value = runCatching { repository.decryptValue(entry) }.getOrNull()
        if (value != null) {
            lines += "export ${entry.name}=${shellSingleQuote(value)}"
            exported += entry.name
        }
        repository.logAccess(entry.name, "ai-tool", "export_env")
    }
    if (exported.isEmpty()) return fail("解密失败，未导出任何凭证")

    val wsRepository =
        runCatching { getKoin().get<me.rerere.rikkahub.data.repository.WorkspaceRepository>() }.getOrNull()
            ?: return fail("工作区不可用")
    val ws = wsRepository.getAll().firstOrNull() ?: return fail("无工作区")
    runCatching { wsRepository.writeText(ws.id, "tmp/vault-env.sh", lines.joinToString("\n"), overwrite = true) }.getOrNull()
        ?: return fail("写沙箱环境文件失败")

    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("exported", JsonArray(exported.map { JsonPrimitive(it) }))
                put("path", "/workspace/tmp/vault-env.sh")
                put("hint", "source /workspace/tmp/vault-env.sh 使用环境变量；用完删除 rm /workspace/tmp/vault-env.sh")
            }.toString(),
        ),
    )
}

private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * 使用凭证库中的 SSH 私钥或密码连接服务器执行命令。
 * 凭证值只走内存（JSch 字节加载），不落盘、不进上下文。
 * Host key：首次连接记录指纹（known_hosts），后续校验防中间人。
 */
fun vaultSshExecTool(
    context: android.content.Context,
    repository: CredentialVaultRepository,
): Tool = Tool(
    name = "vault_ssh_exec",
    description =
        "Connect to a server via SSH using a credential stored in the vault " +
            "(private key or password), run a single command, and return its output. " +
            "auth: 'key' uses a vault-stored SSH private key; 'password' uses a vault-stored password. " +
            "Approval required before connecting.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("host", buildJsonObject { put("type", "string"); put("description", "Server host or IP") })
                    put("port", buildJsonObject { put("type", "integer"); put("description", "SSH port (default 22)") })
                    put("user", buildJsonObject { put("type", "string"); put("description", "SSH username") })
                    put("auth", buildJsonObject { put("type", "string"); put("description", "'key' or 'password'") })
                    put("credential_name", buildJsonObject { put("type", "string"); put("description", "Vault credential name holding the private key or password") })
                    put("command", buildJsonObject { put("type", "string"); put("description", "Command to run on the server") })
                    put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Timeout (default 30)") })
                },
            required = listOf("host", "user", "auth", "credential_name", "command"),
        )
    },
    needsApproval = { true },
    execute = { params -> runVaultSshExec(context, repository, params.jsonObject) },
)

/** vault_ssh_exec 执行体（独立函数避免 lambda label 问题）。 */
private suspend fun runVaultSshExec(
    context: android.content.Context,
    repository: CredentialVaultRepository,
    o: kotlinx.serialization.json.JsonObject,
): List<UIMessagePart> {
    val fail: (String) -> List<UIMessagePart> = { msg -> listOf(UIMessagePart.Text("❌ $msg")) }
    val host = o["host"]?.jsonPrimitive?.contentOrNull ?: return fail("host 必填")
    val port = o["port"]?.jsonPrimitive?.intOrNull ?: 22
    val user = o["user"]?.jsonPrimitive?.contentOrNull ?: return fail("user 必填")
    val auth = o["auth"]?.jsonPrimitive?.contentOrNull ?: return fail("auth 必填（key/password）")
    val credName = o["credential_name"]?.jsonPrimitive?.contentOrNull ?: return fail("credential_name 必填")
    val command = o["command"]?.jsonPrimitive?.contentOrNull ?: return fail("command 必填")
    val timeout = (o["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(5, 300)

    val entry = repository.getByName(credName) ?: return fail("凭证不存在: $credName（用 vault_credential_names 查看可用名称）")
    val secret = repository.decryptValue(entry) ?: return fail("凭证解密失败: $credName")
    repository.logAccess(credName, "ai-tool", "ssh_exec")

    return try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val jsch = JSch()
        // Host key 校验：known_hosts 文件（App 私有目录），首次记录指纹、后续校验防中间人。
        // 用文件文本判断「是否首次」——不依赖 JSch getHostKey(null) 的兼容行为。
        val knownHostsFile = java.io.File(context.filesDir, "vault_known_hosts")
        jsch.setKnownHosts(knownHostsFile.absolutePath)
        val knownHostsText = if (knownHostsFile.exists()) knownHostsFile.readText() else ""
        val isFirstConnect = knownHostsText.lines().none { line ->
            line.isNotBlank() && !line.startsWith("#") &&
                (line.startsWith(host) || line.contains(" $host ") || line.contains(" $host,"))
        }
        val session: com.jcraft.jsch.Session = jsch.getSession(user, host, port)
        when (auth) {
            "key" ->
                // OPENSSH 私钥严格要求末尾换行（缺换行会导致 Auth fail），导入缺行时自动补
                jsch.addIdentity("vault-key", secret.ensureTrailingNewline().encodeToByteArray(), null, null)
            "password" -> session.setPassword(secret)
            else -> return@withContext fail("auth 只支持 key/password")
        }
        session.setConfig("StrictHostKeyChecking", if (isFirstConnect) "no" else "yes")
        session.setConfig("ServerAliveInterval", "30")
        session.setConfig("ServerAliveCountMax", "3")
        session.connect(timeout * 1000)

        // 首次连接：把 host key 追加到 known_hosts 文件（OpenSSH 行格式）
        if (isFirstConnect) {
            session.hostKey?.let { hk ->
                val typeName = hk.type // "ssh-rsa" / "ssh-ed25519" / "ecdsa-sha2-nistp256"...
                // mwiede/jsch 的 HostKey.key 是 String（base64 编码的密钥数据）
                val keyData = hk.key
                knownHostsFile.appendText("$host $typeName $keyData\n")
            }
        }

        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.connect()
        val deadline = System.currentTimeMillis() + timeout * 1000L
        val outBuf = java.io.ByteArrayOutputStream()
        val errBuf = java.io.ByteArrayOutputStream()
        // 读取 stdout/stderr（InputStream），直到命令结束或超时
        val input = channel.inputStream
        val errInput = channel.errStream
        val buf = ByteArray(4096)
        while (System.currentTimeMillis() < deadline) {
            while (input.available() > 0) {
                val n = input.read(buf)
                if (n > 0) outBuf.write(buf, 0, n)
            }
            while (errInput.available() > 0) {
                val n = errInput.read(buf)
                if (n > 0) errBuf.write(buf, 0, n)
            }
            if (channel.isClosed && input.available() <= 0 && errInput.available() <= 0) break
            Thread.sleep(100)
        }
        val exit = channel.exitStatus
        session.disconnect()
        val allSecrets = allVaultValues(repository)
        val stdout = SecretMasker.mask(outBuf.toString("UTF-8").trim(), allSecrets)
        val stderr = SecretMasker.mask(errBuf.toString("UTF-8").trim(), allSecrets)
        val fingerprintNote =
            if (isFirstConnect) "\n（首次连接，已记录该主机指纹，后续连接将校验）" else ""
        listOf(
            UIMessagePart.Text(
                buildString {
                    append("exit=$exit")
                    if (stdout.isNotEmpty()) append("\n--- stdout ---\n$stdout")
                    if (stderr.isNotEmpty()) append("\n--- stderr ---\n$stderr")
                    if (fingerprintNote.isNotEmpty()) append(fingerprintNote)
                },
            ),
        )
        }
    } catch (e: Exception) {
        listOf(UIMessagePart.Text("❌ SSH 执行失败: ${e.message}"))
    }
}

/** OPENSSH 私钥末尾换行标准化：缺末尾换行时补上（否则 JSch 认证失败）。 */
internal fun String.ensureTrailingNewline(): String = if (endsWith("\n")) this else "$this\n"

/** 更新凭证条目的元数据（名称/描述/分组），值不可被 AI 修改。改名=复制 value 密文到新名后删旧条目。 */
fun vaultCredentialUpdateTool(
    context: android.content.Context,
    repository: CredentialVaultRepository,
): Tool = Tool(
    name = "vault_credential_update",
    description =
        "Update a credential entry's metadata (rename / change description / change group). " +
            "The secret VALUE is never readable or writable by the AI — this tool only touches " +
            "name/description/group fields. Rename copies the existing encrypted value to the new name. " +
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
                    if (pubParam != null && newPub != existing.publicKey) changed += if (newPub.isNullOrEmpty()) "公钥(清空)" else "公钥"
                    if (changed.isEmpty()) {
                        listOf(UIMessagePart.Text("ℹ️ 没有需要更新的字段（当前已是最新）"))
                    } else {
                        val value = repository.decryptValue(existing) ?: ""
                        repository.save(
                            name = targetName,
                            value = value,
                            description = desc ?: existing.description,
                            group = group ?: existing.grp,
                            publicKey = newPub ?: existing.publicKey,
                        )
                        if (targetName != name) {
                            repository.delete(existing)
                            repository.logAccess(name, "ai-tool", "rename_from")
                            repository.logAccess(targetName, "ai-tool", "rename_to")
                        } else {
                            repository.logAccess(name, "ai-tool", "update")
                        }
                        listOf(
                            UIMessagePart.Text(
                                "✅ 已更新凭证元数据：${targetName}\n变更：${changed.joinToString("；")}\n（值未改动，AI 不可读写密钥值）",
                            ),
                        )
                    }
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
                val fp = if (entry.publicKey.isNotBlank()) (SshKeyGenerator.fingerprint(entry.publicKey) ?: "(解析失败)") else "(无公钥)"
                val created = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.createdAt))
                val updated = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.updatedAt))
                listOf(
                    UIMessagePart.Text(
                        buildString {
                            append("凭证: ${entry.name}  [${entry.grp}]\n")
                            append("描述: ${entry.description.ifEmpty { "(空)" }}\n")
                            append("值长度: ${entry.valueLength}（已加密存储，AI 不可见）\n")
                            append("创建: $created | 更新: $updated\n")
                            append("公钥指纹: $fp\n")
                            if (entry.publicKey.isNotBlank()) append("公钥全文:\n${entry.publicKey}\n")
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
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            listOf(
                UIMessagePart.Text(
                    buildString {
                        append("密钥调用记录（最近 ${logs.size} 条${if (name != null) "，凭证: $name" else ""}）：\n")
                        logs.forEach { l ->
                            append("${fmt.format(java.util.Date(l.tsMs))} | ${l.caller} | ${l.action} | ${l.credentialName}\n")
                        }
                    },
                ),
            )
        }
    },
)

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
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
    needsApproval = { true },
    execute = { params ->
        val sessionManager = VaultSessionManager(context)
        if (!sessionManager.hasActiveAuthorization()) {
            listOf(UIMessagePart.Text("❌ 未授权：请先完成 Vault 授权（30 分钟或一直有效）再调用本工具"))
        } else {
            val entries = repository.getAll()
            val quads =
                entries.mapNotNull { e ->
                    repository.decryptValue(e)?.let { VaultExporter.Quad(e.name, it, e.description, e.grp, e.publicKey) }
                }
            if (quads.isEmpty()) {
                listOf(UIMessagePart.Text("❌ 凭证库为空，无可导出条目"))
            } else {
                val script = VaultExporter.toLoadCreds(quads)
                val wsRepository =
                    runCatching { getKoin().get<me.rerere.rikkahub.data.repository.WorkspaceRepository>() }.getOrNull()
                        ?: return@Tool listOf(UIMessagePart.Text("❌ 工作区不可用"))
                val ws = wsRepository.getAll().firstOrNull() ?: return@Tool listOf(UIMessagePart.Text("❌ 无工作区"))
                runCatching { wsRepository.writeText(ws.id, "tmp/load-creds-export.sh", script, overwrite = true) }.getOrNull()
                    ?: return@Tool listOf(UIMessagePart.Text("❌ 写工作区文件失败"))
                quads.forEach { q -> repository.logAccess(q.name, "ai-tool", "export_loadcreds") }
                listOf(
                    UIMessagePart.Text(
                        "✅ 已导出 ${quads.size} 条凭证到 /workspace/tmp/load-creds-export.sh\n" +
                            "含分组/描述/SSH公钥注释。修改后可用 vault_import_loadcreds 或 UI 导入同步。\n" +
                            "⚠️ 该文件含明文密钥，用完请删除。",
                    ),
                )
            }
        }
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
            },
            required = emptyList(),
        )
    },
    needsApproval = { true },
    execute = { params ->
        val sessionManager = VaultSessionManager(context)
        if (!sessionManager.hasActiveAuthorization()) {
            listOf(UIMessagePart.Text("❌ 未授权：请先完成 Vault 授权（30 分钟或一直有效）再调用本工具"))
        } else {
            val path = params.jsonObject["path"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "/workspace/tmp/load-creds-export.sh"
            val wsRepository =
                runCatching { getKoin().get<me.rerere.rikkahub.data.repository.WorkspaceRepository>() }.getOrNull()
                    ?: return@Tool listOf(UIMessagePart.Text("❌ 工作区不可用"))
            val ws = wsRepository.getAll().firstOrNull() ?: return@Tool listOf(UIMessagePart.Text("❌ 无工作区"))
            val content =
                runCatching {
                    val buf = java.io.ByteArrayOutputStream()
                    wsRepository.exportRootfsFile(ws.id, path.removePrefix("/workspace/"), buf)
                    buf.toString(Charsets.UTF_8.name())
                }.getOrNull()
            if (content == null) {
                listOf(UIMessagePart.Text("❌ 读取文件失败: $path"))
            } else {
                val parsed = CredentialImporter.parse(content)
                if (parsed.isEmpty()) {
                    listOf(UIMessagePart.Text("❌ 未解析到任何条目（格式不符 load-creds.sh？）"))
                } else {
                    val imported = repository.importEntries(parsed)
                    parsed.forEach { p -> repository.logAccess(p.name, "ai-tool", "import_loadcreds") }
                    listOf(UIMessagePart.Text("✅ 已导入/更新 $imported 条凭证（解析 ${parsed.size} 条）"))
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
            },
            required = emptyList(),
        )
    },
    execute = { params ->
        val sessionManager = VaultSessionManager(context)
        if (!sessionManager.hasActiveAuthorization()) {
            listOf(UIMessagePart.Text("❌ 未授权：请先完成 Vault 授权（30 分钟或一直有效）再调用本工具"))
        } else {
            val path = params.jsonObject["path"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "/workspace/tmp/load-creds.sh"
            val wsRepository =
                runCatching { getKoin().get<me.rerere.rikkahub.data.repository.WorkspaceRepository>() }.getOrNull()
                    ?: return@Tool listOf(UIMessagePart.Text("❌ 工作区不可用"))
            val ws = wsRepository.getAll().firstOrNull() ?: return@Tool listOf(UIMessagePart.Text("❌ 无工作区"))
            val content =
                runCatching {
                    val buf = java.io.ByteArrayOutputStream()
                    wsRepository.exportRootfsFile(ws.id, path.removePrefix("/workspace/"), buf)
                    buf.toString(Charsets.UTF_8.name())
                }.getOrNull()
            if (content == null) {
                listOf(UIMessagePart.Text("❌ 读取文件失败: $path（先上传或 vault_export_loadcreds 生成）"))
            } else {
                val fileEntries = CredentialImporter.parse(content).associateBy { it.name }
                if (fileEntries.isEmpty()) {
                    listOf(UIMessagePart.Text("❌ 文件未解析到任何条目（格式不符 load-creds.sh？）"))
                } else {
                    val vaultEntries = repository.getAll().associateBy { it.name }
                    val sha = java.security.MessageDigest.getInstance("SHA-256")
                    fun hash(s: String): String =
                        java.util.Base64.getEncoder().encodeToString(sha.digest(s.encodeToByteArray()))
                    fun vaultValueHash(name: String): String? =
                        vaultEntries[name]?.let { repository.decryptValue(it) }?.let { hash(it) }
                    val fileValueHash = { e: CredentialImporter.ParsedEntry -> hash(e.value) }

                    val onlyVault = vaultEntries.keys - fileEntries.keys
                    val onlyFile = fileEntries.keys - vaultEntries.keys
                    val shared = vaultEntries.keys intersect fileEntries.keys

                    val sameValues = mutableListOf<String>()
                    val diffValues = mutableListOf<String>()
                    val diffMeta = mutableListOf<String>()
                    shared.sorted().forEach { name ->
                        val fe = fileEntries.getValue(name)
                        val ve = vaultEntries.getValue(name)
                        val fh = fileValueHash(fe)
                        val vh = vaultValueHash(name)
                        if (fh != null && fh == vh) {
                            sameValues += name
                        } else {
                            diffValues += "$name（值不同）"
                        }
                        val metaDiff = mutableListOf<String>()
                        if (fe.description != ve.description) metaDiff += "描述"
                        if (fe.group != ve.grp) metaDiff += "分组"
                        if (fe.publicKey != ve.publicKey) metaDiff += "公钥"
                        if (metaDiff.isNotEmpty()) diffMeta += "$name（${metaDiff.joinToString("/")}）"
                    }

                    val sb = StringBuilder()
                    sb.append("核对报告（vault ↔ $path）\n")
                    sb.append("文件条目 ${fileEntries.size} | vault 条目 ${vaultEntries.size}\n")
                    if (onlyVault.isNotEmpty()) sb.append("🔹 仅 vault 有（${onlyVault.size}）：${onlyVault.sorted().joinToString(", ")}\n")
                    if (onlyFile.isNotEmpty()) sb.append("🔹 仅文件有（${onlyFile.size}）：${onlyFile.sorted().joinToString(", ")}\n")
                    sb.append("✅ 值一致（${sameValues.size}）：${sameValues.take(30).joinToString(", ")}${if (sameValues.size > 30) "…" else ""}\n")
                    if (diffValues.isNotEmpty()) sb.append("⚠️ 值不同（${diffValues.size}）：${diffValues.joinToString("; ")}\n")
                    if (diffMeta.isNotEmpty()) sb.append("ℹ️ 元数据不同（${diffMeta.size}）：${diffMeta.joinToString("; ")}\n")
                    if (onlyVault.isEmpty() && onlyFile.isEmpty() && diffValues.isEmpty()) {
                        sb.append("🎉 值完全一致。")
                        if (diffMeta.isEmpty()) sb.append("描述/分组/公钥也一致。")
                    } else {
                        sb.append("\n修正方向需用户确认：文件为准→vault_import_loadcreds；vault 为准→vault_export_loadcreds 覆盖文件。")
                    }
                    listOf(UIMessagePart.Text(sb.toString()))
                }
            }
        }
    },
)
