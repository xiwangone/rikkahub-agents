package me.rerere.rikkahub.data.vault

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
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
import me.rerere.rikkahub.data.ai.tools.local.withUtf8ConsoleEncoding
import me.rerere.rikkahub.data.datastore.SshPlatform

/**
 * Vault 凭证工具（App 内 AI 凭证中枢）。
 *
 * 安全模型：凭证值只在 App 进程内存使用（JSch 字节加载 / 密码对象），
 * 工具返回值不含明文——只返回变量名、公钥、命令输出。
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
    if (exported.isEmpty()) return fail("没有导出任何凭证：\n" + failures.joinToString("\n") { "· $it" })

    val wsRepository =
        runCatching { getKoin().get<me.rerere.rikkahub.data.repository.WorkspaceRepository>() }.getOrNull()
            ?: return fail("工作区不可用")
    val ws = wsRepository.getAll().firstOrNull() ?: return fail("无工作区")
    runCatching { wsRepository.writeText(ws.id, "tmp/vault-env.sh", lines.joinToString("\n"), overwrite = true) }
        .onFailure { AppLog.w("VaultTools", "写入 vault-env.sh 失败", it) }
        .getOrNull()
        ?: return fail("写沙箱环境文件失败")

    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("exported", JsonArray(exported.map { JsonPrimitive(it) }))
                put("path", "/workspace/tmp/vault-env.sh")
                put("hint", "⚠ 该文件是**全量明文**（共 ${exported.size} 条）：source /workspace/tmp/vault-env.sh 使用；**用完立即** rm -f /workspace/tmp/vault-env.sh。AI 在汇报时必须把这条删除提醒**显式告诉用户**。")
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
    val host = o["host"]?.jsonPrimitive?.contentOrNull ?: return fail("host 必填")
    val port = o["port"]?.jsonPrimitive?.intOrNull ?: 22
    val user = o["user"]?.jsonPrimitive?.contentOrNull ?: return fail("user 必填")
    val auth = o["auth"]?.jsonPrimitive?.contentOrNull ?: return fail("auth 必填（key/password）")
    val credName = o["credential_name"]?.jsonPrimitive?.contentOrNull ?: return fail("credential_name 必填")
    val command = o["command"]?.jsonPrimitive?.contentOrNull ?: return fail("command 必填")
    val platform = o["platform"]?.jsonPrimitive?.contentOrNull?.let { SshPlatform.fromPreference(it) }
    val timeout = (o["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(5, 300)

    val resolved = CredentialResolver(repository).resolve(credName, CredentialPurpose.SSH_AUTH, caller = "ai-tool")
    val secret = (resolved as? CredentialResolution.Granted)?.value ?: return fail(resolved.message)
    if (auth != "key" && auth != "password") return fail("auth 只支持 key/password")

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
            listOf(UIMessagePart.Text("❌ SSH 执行失败: ${e.message}"))
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
    val jsch = JSch()
    val knownHostsFile = java.io.File(context.filesDir, "vault_known_hosts")
    jsch.setKnownHosts(knownHostsFile.absolutePath)
    val isFirstConnect = isFirstKnownHostConnect(knownHostsFile, host)
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
    session.setConfig("StrictHostKeyChecking", if (isFirstConnect) "no" else "yes")
    session.setConfig("ServerAliveInterval", "30")
    session.setConfig("ServerAliveCountMax", "3")
    session.connect(timeout * 1000)
    val outcome =
        try {
            if (isFirstConnect) {
                session.hostKey?.let { hk ->
                    knownHostsFile.appendText("$host ${hk.type} ${hk.key}\n")
                }
            }
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
    val fingerprintNote = if (isFirstConnect) "\n（首次连接，已记录该主机指纹，后续连接将校验）" else ""
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

private fun isFirstKnownHostConnect(knownHostsFile: java.io.File, host: String): Boolean {
    if (!knownHostsFile.exists()) return true
    return knownHostsFile.readText().lines().none { line ->
        line.isNotBlank() && !line.startsWith("#") &&
            (line.startsWith(host) || line.contains(" $host ") || line.contains(" $host,"))
    }
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
