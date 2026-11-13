package me.rerere.rikkahub.data.vault

import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import me.rerere.rikkahub.data.log.AppLog
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
import me.rerere.rikkahub.data.ai.tools.ToolErrors
import me.rerere.rikkahub.data.ai.tools.local.RemotePlatform
import me.rerere.rikkahub.data.ai.tools.local.importLegacyVaultKnownHosts
import me.rerere.rikkahub.data.ai.tools.local.installHostKeyVerification
import me.rerere.rikkahub.data.ai.tools.local.newJSch
import me.rerere.rikkahub.data.ai.tools.local.persistKnownHosts
import me.rerere.rikkahub.data.ai.tools.local.withUtf8ConsoleEncoding
import me.rerere.rikkahub.data.datastore.SshPlatform

/**
 * Vault 凭证执行与环境导出工具（`vault_export_env` / `vault_ssh_exec`）。
 *
 * 安全模型：
 * - `vault_export_env`：需有效 Vault 授权与调用审批，将解密后的凭证明文写入工作区环境文件
 *   （`/workspace/tmp/vault-env.sh`）供沙箱脚本 `source`；工具向 AI 返回的内容不含明文，
 *   仅含已导出变量名列表、文件路径与用完立即删除提醒。
 * - `vault_ssh_exec`：SSH 私钥或密码只在 App 进程内存中通过 JSch 加载使用，不落盘，
 *   工具返回值仅含命令执行输出。
 */

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
                            put("items", buildJsonObject { put("type", "string") })
                        },
                    )
                    // 目标工作区：不传 = 第一个工作区（旧行为）；传了 = 指定区。
                    // 工具执行上下文拿不到调用方工作区，不传时会写到第一个区，
                    // 调用方在别的区既 source 不到也删不掉（明文会滞留）。
                    put(
                        "workspace",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Target workspace name or id; default = first workspace")
                        },
                    )
                    put(
                        "ttl_hours",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "Lifetime hint written into the file header as expires_at (hours; 0 or omitted = never expire). The file is not deleted automatically — the header marks when it should be removed.")
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
        return fail("Not authorized: complete Vault authorization first (next to the session input box, 30 minutes or always valid), then call this tool")
    }
    val requested = o["names"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
    val entries = repository.getAll()
    val selected = if (requested.isNullOrEmpty()) entries else entries.filter { it.name in requested }
    if (selected.isEmpty()) return fail("No credentials to export (use vault_credential_names to list available names)")

    val ttlHours = (o["ttl_hours"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 24 * 365)
    val expiresAtMs = if (ttlHours > 0) System.currentTimeMillis() + ttlHours * 3_600_000L else null
    val expiresLabel = expiresAtMs?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "never"
    val lines =
        mutableListOf(
            "#!/bin/bash",
            "# vault-env — generated by vault_export_env; expires_at: $expiresLabel",
            "# delete after use: rm /workspace/tmp/vault-env.sh",
        )
    val exported = mutableListOf<String>()
    val failures = mutableListOf<String>()
    // 必须传 isAuthorized：EXPORT 的 requiresAuthorization = true，缺省 null 时
    // `isAuthorized?.invoke() != true` 但恒为真 → 每个条目都返回 NotAuthorized。
    val resolver = CredentialResolver(repository) { sessionManager.hasActiveAuthorization() }
    for (entry in selected) {
        when (val r = resolver.resolve(entry.name, CredentialPurpose.EXPORT, caller = "ai-tool")) {
            is CredentialResolution.Granted -> {
                lines += "export ${entry.name}=${vaultShellSingleQuote(r.value)}"
                exported += entry.name
            }
            else -> failures += r.message
        }
    }
    if (exported.isEmpty()) return fail("No credentials exported:\n" + failures.joinToString("\n") { "· $it" })

    val wsRepository =
        runCatching { getKoin().get<me.rerere.rikkahub.data.repository.WorkspaceRepository>() }.getOrNull()
            ?: return fail("Workspace unavailable")
    val target =
        resolveVaultWorkspace(wsRepository, o["workspace"]?.jsonPrimitive?.contentOrNull)
            .getOrElse { return fail(it.message ?: "Cannot resolve target workspace") }
    runCatching { wsRepository.writeText(target.id, "tmp/vault-env.sh", lines.joinToString("\n"), overwrite = true) }
        .onFailure { AppLog.w("VaultTools", "Failed to write vault-env.sh", it) }
        .getOrNull()
        ?: return fail("Failed to write the sandbox env file")

    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("exported", JsonArray(exported.map { JsonPrimitive(it) }))
                put("workspace", target.name)
                put("workspaceId", target.id)
                put("path", "/workspace/tmp/vault-env.sh")
                put("target", vaultTargetNote(target, "/workspace/tmp/vault-env.sh"))
                put("expiresAt", expiresLabel)
                put("hint", "⚠ This file is **fully plaintext** (${exported.size} entries in total) and lives in workspace '${target.name}'. source it there (source /workspace/tmp/vault-env.sh) and **delete it immediately** with rm -f /workspace/tmp/vault-env.sh when done. When reporting, the AI must **explicitly tell the user** which workspace holds the plaintext file and that it must be deleted.")
            }.toString(),
        ),
    )
}

private fun vaultShellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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
                    put("platform", buildJsonObject { put("type", "string"); put("description", "Remote platform: 'auto' (default; falls back to the SSH banner result, then command-shape heuristics), 'linux' or 'windows'. Pass explicitly when the remote platform is known so output encoding picks the right form on the first call.") })
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
    val host = o["host"]?.jsonPrimitive?.contentOrNull ?: return fail("host is required")
    val port = o["port"]?.jsonPrimitive?.intOrNull ?: 22
    val user = o["user"]?.jsonPrimitive?.contentOrNull ?: return fail("user is required")
    val auth = o["auth"]?.jsonPrimitive?.contentOrNull ?: return fail("auth is required (key/password)")
    val credName = o["credential_name"]?.jsonPrimitive?.contentOrNull ?: return fail("credential_name is required")
    val command = o["command"]?.jsonPrimitive?.contentOrNull ?: return fail("command is required")
    val platform = o["platform"]?.jsonPrimitive?.contentOrNull?.let { SshPlatform.fromPreference(it) }
    val timeout = (o["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(5, 300)

    // SSH_AUTH 现需会话授权：不传 isAuthorized 会直接被拒（值不出进程 ≠ 不要紧，
    // AI 能借此在任意已配主机上执行命令）。
    val sessionManager = VaultSessionManager(context)
    if (!sessionManager.hasActiveAuthorization()) {
        return fail("Not authorized: complete Vault authorization first (30 minutes or always valid), then call this tool")
    }
    val resolved =
        CredentialResolver(repository) { sessionManager.hasActiveAuthorization() }
            .resolve(credName, CredentialPurpose.SSH_AUTH, caller = "ai-tool")
    val secret = (resolved as? CredentialResolution.Granted)?.value ?: return fail(resolved.message)
    if (auth != "key" && auth != "password") return fail("auth only supports key/password")

    return runCatching {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            executeVaultSshSession(context, repository, host, port, user, auth, secret, command, timeout, platform = platform)
        }
    }.getOrElse { e ->
        if (e is CancellationException) throw e
        if (isVaultHostKeyChange(e.message)) {
            // Vault 侧无 saved host 实体，不接轮换开关，只把错误翻译成结构化信封
            // （与 SshTool 的 host_key_changed 分支同口径）。
            listOf(
                UIMessagePart.Text(
                    ToolErrors.envelopeFor(
                        error = "host_key_changed",
                        message = "host_key_changed",
                        hint = "Stored key for $host doesn't match what the server presented. If the user trusts this host (e.g. they just reinstalled it), call ssh_forget_host_key with host=\"$host\" then retry. Do NOT forget the key without explicit user confirmation — a changed key can also indicate an attacker.",
                        extra = ToolErrors.extraOf("host" to host, "raw" to e.message.orEmpty()),
                    ).toString(),
                ),
            )
        } else {
            listOf(UIMessagePart.Text("❌ SSH execution failed: ${e.message}"))
        }
    }
}

/** 与 SshTool.isHostKeyChange 同口径的主机密钥变化判定（Vault 侧本地复刻，跨包不可见）。 */
private fun isVaultHostKeyChange(msg: String?): Boolean {
    val m = msg.orEmpty()
    return m.contains("HostKey", ignoreCase = true) ||
        m.contains("host key", ignoreCase = true) ||
        m.contains("identification has changed", ignoreCase = true) ||
        m.contains("REMOTE HOST IDENTIFICATION", ignoreCase = true)
}

private suspend fun executeVaultSshSession(
    context: android.content.Context,
    repository: CredentialVaultRepository,
    host: String,
    port: Int,
    user: String,
    auth: String,
    secret: String,
    command: String,
    timeout: Int,
    /** 显式平台；AUTO/缺省时用建连后读到的 banner 判定。 */
    platform: SshPlatform? = null,
): List<UIMessagePart> {
    // 与 AI 侧共用同一 known_hosts 与同一校验实现（AcceptNewHostKeyRepository）：
    // 首次信任、变更拒绝，一处拒绝、处处拒绝。旧 vault_known_hosts 的条目
    // 一次性并入共用文件（共用侧已有记录时以共用为准），此后不再读写旧文件
    // （旧文件留盘，ssh_forget_host_key 仍会清理它）。
    val jsch = newJSch(context)
    val legacyVaultFile = java.io.File(context.filesDir, "vault_known_hosts")
    if (importLegacyVaultKnownHosts(jsch.hostKeyRepository, legacyVaultFile) > 0) {
        persistKnownHosts(jsch)
    }
    val isFirstConnect = jsch.hostKeyRepository.getHostKey(host, null).isNullOrEmpty()
    // 与 SshNetworkSession / SshTerminalPage 保持一致：先问隧道。
    // tailnet 目标（100.64.0.0/10）在 Android 上无法直接拨号，userspace tsnet 只把出口开在
    // 127.0.0.1:<随机端口>；不解析会表现为 "connection is closed by foreign host"
    // （TCP 看似建立、包没到对端，对端 sshd 日志无记录）。
    val (dialHost, dialPort) = me.rerere.workspace.ssh.TunnelRouter.resolve(host, port)
    val session: com.jcraft.jsch.Session = jsch.getSession(user, dialHost, dialPort)
    // known_hosts 与首连判定仍按用户填的原始 host，代理端口每次不同也不影响信任
    if (dialHost != host) {
        session.setHostKeyAlias(host)
    }
    if (auth == "key") {
        jsch.addIdentity("vault-key", secret.ensureTrailingNewline().encodeToByteArray(), null, null)
    } else {
        session.setPassword(secret)
    }
    installHostKeyVerification(jsch, session)
    session.setConfig("ServerAliveInterval", "30")
    session.setConfig("ServerAliveCountMax", "3")
    session.connect(timeout * 1000)
    val outcome =
        try {
            val channel = session.openChannel("exec") as ChannelExec
            try {
                // 显式平台优先；未显式指定时用本次建连读到的 banner 判定
                val explicitWindows = when (platform) {
                    SshPlatform.WINDOWS -> true
                    SshPlatform.LINUX -> false
                    else -> null
                }
                channel.setCommand(withUtf8ConsoleEncoding(command, explicitWindows ?: RemotePlatform.isWindowsServer(session.serverVersion)))
                channel.connect(timeout * 1000)
                val (oBuf, eBuf, timedOut) = drainChannelStreams(channel, System.currentTimeMillis() + timeout * 1000L)
                if (timedOut) {
                    runCatching { channel.sendSignal("KILL") }
                }
                ChannelExecOutcome(oBuf, eBuf, if (timedOut) -1 else channel.exitStatus, timedOut)
            } finally {
                runCatching { channel.disconnect() }
            }
        } finally {
            runCatching { session.disconnect() }
        }
    val (outBuf, errBuf, exit, timedOut) = outcome
    val allSecrets = allVaultSecrets(repository)
    val stdout = SecretMasker.mask(outBuf.toString("UTF-8").trim(), allSecrets)
    val stderr = SecretMasker.mask(errBuf.toString("UTF-8").trim(), allSecrets)
    if (timedOut) {
        // Same command_timeout envelope as SshTool.runOnSession: a bare exit=-1 is
        // indistinguishable from a real exit code, so the timeout must be explicit.
        return listOf(
            UIMessagePart.Text(
                ToolErrors.envelopeFor(
                    error = "command_timeout",
                    message = "Command timed out",
                    hint = "Command did not complete within ${timeout}s. Increase timeout_seconds and retry. Partial stdout/stderr captured before the timeout is included.",
                    extra = ToolErrors.extraOf("partial_stdout" to stdout, "partial_stderr" to stderr),
                ).toString(),
            ),
        )
    }
    val fingerprintNote = if (isFirstConnect) "\n(First connection; this host fingerprint was recorded and will be verified on later connections)" else ""
    return listOf(
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

/** One exec channel run: captured streams, exit code, and whether the deadline hit first. */
private data class ChannelExecOutcome(
    val stdout: java.io.ByteArrayOutputStream,
    val stderr: java.io.ByteArrayOutputStream,
    val exitCode: Int,
    val timedOut: Boolean,
)

private fun drainChannelStreams(
    channel: ChannelExec,
    deadline: Long,
): Triple<java.io.ByteArrayOutputStream, java.io.ByteArrayOutputStream, Boolean> {
    val outBuf = java.io.ByteArrayOutputStream()
    val errBuf = java.io.ByteArrayOutputStream()
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
    val timedOut = !channel.isClosed
    return Triple(outBuf, errBuf, timedOut)
}

/** OPENSSH 私钥末尾换行标准化：缺末尾换行时补上（否则 JSch 认证失败）。 */
internal fun String.ensureTrailingNewline(): String = if (endsWith("\n")) this else "$this\n"
