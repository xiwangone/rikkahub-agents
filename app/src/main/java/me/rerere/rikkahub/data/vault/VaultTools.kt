package me.rerere.rikkahub.data.vault

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import kotlinx.serialization.json.JsonElement
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
            "Use to discover which credential names are available before calling vault_ssh_exec.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("group", buildJsonObject { put("type", "string"); put("description", "Filter by group: Git/AI/ECS/MCP/Notification/SSH/Other") })
                },
        )
    },
    execute = { params ->
        val group = params.jsonObject["group"]?.jsonPrimitive?.contentOrNull
        val entries =
            repository.getAll()
                .filter { group == null || it.grp == group }
                .map { e -> "${e.name}  [${e.grp}] len=${e.valueLength}  ${e.description}" }
        listOf(
            UIMessagePart.Text(
                if (entries.isEmpty()) "（凭证库为空，或该分组无条目）" else "凭证库条目（${entries.size}）：\n" + entries.joinToString("\n"),
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
        )
        // 公钥条目：可公开，便于密钥库内查看/复制（配置服务器 authorized_keys）
        repository.save(
            name = "${name}_PUB",
            value = key.publicKeyLine,
            description = "SSH 公钥（对应 $name，可公开，配置服务器 authorized_keys 用）",
            group = group,
        )
        repository.logAccess(name, "ai-tool", "gen_key")
        listOf(
            UIMessagePart.Text(
                "✅ 密钥对已生成并保存到凭证库（$name）\n" +
                    "私钥已在 App 内存中存入 Vault（未落盘明文）\n" +
                    "请将以下公钥配置到服务器 ~/.ssh/authorized_keys：\n${key.publicKeyLine}",
            ),
        )
    },
)

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
            "key" -> jsch.addIdentity("vault-key", secret.encodeToByteArray(), null, null)
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
