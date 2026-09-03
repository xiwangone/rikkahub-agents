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
            "Supports optional keyword search (matches name / description, case-insensitive) and " +
            "optional group filter. Results are sorted by group then name. " +
            "Use to discover which credential names are available before calling vault_ssh_exec / vault_http_exec.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("group", buildJsonObject { put("type", "string"); put("description", "Filter by group: Git/AI/ECS/MCP/Notification/SSH/Other") })
                    put("keyword", buildJsonObject { put("type", "string"); put("description", "Optional search keyword (matches name or description, case-insensitive)") })
                    put("sort", buildJsonObject { put("type", "string"); put("description", "Sort order: name / group / length (default group-then-name)") })
                },
        )
    },
    execute = { params ->
        val group = params.jsonObject["group"]?.jsonPrimitive?.contentOrNull
        val keyword = params.jsonObject["keyword"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val sort = params.jsonObject["sort"]?.jsonPrimitive?.contentOrNull
        val filtered =
            repository.getAll()
                .filter { e -> group == null || e.grp == group }
                .filter { e -> keyword == null || e.name.lowercase().contains(keyword) || e.description.lowercase().contains(keyword) }
        val entries =
            when (sort) {
                "name" -> filtered.sortedBy { it.name.lowercase() }
                "group" -> filtered.sortedWith(compareBy({ it.grp }, { it.name.lowercase() }))
                "length" -> filtered.sortedBy { it.valueLength }
                else -> filtered.sortedWith(compareBy({ it.grp }, { it.name.lowercase() }))
            }.map { e -> "${e.name}  [${e.grp}] len=${e.valueLength}  ${e.description}" }
        listOf(
            UIMessagePart.Text(
                if (entries.isEmpty()) "（凭证库为空，或无匹配条目）" else "凭证库条目（${entries.size}）：\n" + entries.joinToString("\n"),
            ),
        )
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
            repository.save(
                name = name,
                value = "", // 占位：值留空，用户稍后填写
                description = desc,
                group = group,
            )
            repository.logAccess(name, "ai-tool", "prepare")
            listOf(
                UIMessagePart.Text(
                    "✅ 已创建凭证占位条目：$name [${group}]\n" +
                        "值尚未填写。请用户到 安全凭证库 → $name 编辑，填入实际 key/token。",
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
            "Use before vault_ssh_exec when the server is new and has no key yet.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("name", buildJsonObject { put("type", "string"); put("description", "Credential name (default WEB_SSH_KEY)") })
                    put("group", buildJsonObject { put("type", "string"); put("description", "Vault group (default SSH)") })
                },
        )
    },
    execute = { params ->
        val name = params.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "WEB_SSH_KEY"
        val group = params.jsonObject["group"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "SSH"
        val key = SshKeyGenerator.generate()
        repository.save(
            name = name,
            value = key.privateKeyPem,
            description = "AI 生成 SSH 私钥（${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())}）",
            group = group,
            publicKey = key.publicKeyLine,
        )
        repository.logAccess(name, "ai-tool", "gen_key")
        listOf(
            UIMessagePart.Text(
                "✅ 密钥对已生成并保存到凭证库（$name）\n" +
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
                    if (changed.isEmpty()) {
                        listOf(UIMessagePart.Text("ℹ️ 没有需要更新的字段（当前已是最新）"))
                    } else {
                        val value = repository.decryptValue(existing) ?: ""
                        repository.save(
                            name = targetName,
                            value = value,
                            description = desc ?: existing.description,
                            group = group ?: existing.grp,
                            publicKey = existing.publicKey,
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
