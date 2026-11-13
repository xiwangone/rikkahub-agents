package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.data.ai.tools.ToolErrors
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SshPlatform
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.sync.replaceFilePreservingTarget

import me.rerere.rikkahub.data.vault.message
import me.rerere.rikkahub.data.vault.ensureTrailingNewline
import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

private const val TAG_SSH = "SshTool"

/**
 * PowerShell 引号/编码根治：
 * 把命令转成 pwsh -EncodedCommand <base64>——base64 只含 [A-Za-z0-9+/=]，
 * 穿透 SSH exec 通道/远端 shell 零转义需求；脚本内先强制 UTF-8 输出编码，
 * 远端输出字节流确定为 UTF-8（解决 PowerShell 默认 GBK/ANSI 导致的乱码）。
 *
 * 触发条件：命令含非 ASCII（中文等）或 PowerShell 需管道/复杂引号——此时
 * 逐层转义易错，EncodedCommand 是唯一零转义保真路径。纯 ASCII 简单命令
 * （ls/dir/git status）不加壳，保持原样兼容非 pwsh 远端（Linux/华硕等）。
 */
internal fun preparePowerShellCommand(command: String): String {
    val isPowerShell = command.startsWith("powershell") || command.startsWith("pwsh") ||
        command.startsWith("PowerShell") || command.contains("PowerShell -") ||
        command.contains("powershell -")
    if (!isPowerShell) return command // 非 pwsh：保持原样（Linux 远端等）

    // 已经是 EncodedCommand 形式（background 分支的 wrapDetachedCommandSmart 已生成完整
    // "powershell ... -EncodedCommand <b64>"）：再包装会剥掉外层 powershell 前缀、把
    // "-EncodedCommand <b64>" 当脚本本体重新 base64，导致远端收到
    // "[Console]::OutputEncoding=UTF-8; -EncodedCommand <b64>" 而无 powershell 前缀，
    // 报"无法将 -EncodedCommand 识别为 cmdlet"。识别到已编码形式则原样返回，避免二次包装。
    if (command.contains("-EncodedCommand", ignoreCase = true)) return command

    // 剥掉用户已写的前缀 powershell/-NoProfile/-Command，取实际脚本。
    // 关键：剥掉 "powershell" 后，后续参数会落在行首（无前导空白），
    // 因此参数剥除正则必须同时容忍行首与中间（用 \s* 而非 \s+ 前缀）。
    val script = command
        .replace(Regex("^\\s*(powershell|pwsh|PowerShell)\\s+"), " ")
        .replace(Regex("(?i)\\s*-NoProfile\\s*"), " ")
        .replace(Regex("(?i)\\s*-NonInteractive\\s*"), " ")
        .replace(Regex("(?i)\\s*-Command\\s*"), " ")
        .trim()
        // -Command "..." 的引号是语法分隔（剥 -Command 后残留），不是脚本内容；
        // 不剥会导致 EncodedCommand 执行整段引号字符串=不执行只输出。
        .let { if (it.length >= 2 && it.startsWith("\"") && it.endsWith("\"")) it.substring(1, it.length - 1) else it }

    // 前置 UTF-8 输出编码 + 脚本本体
    val fullScript = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; $script"
    val b64 = android.util.Base64.encodeToString(
        fullScript.toByteArray(Charsets.UTF_16LE), android.util.Base64.NO_WRAP
    )
    return "powershell -NoProfile -NonInteractive -EncodedCommand $b64"
}

/** Run a single command on an open session. Returns a JSON object with exit_code/stdout/stderr. */
internal fun runOnSession(session: Session, command: String, timeoutMs: Int, stdin: String? = null): JsonObject {
    // 引号/编码根治：pwsh 命令自动转 EncodedCommand + UTF-8 前缀（见 preparePowerShellCommand）
    val effectiveCommand = preparePowerShellCommand(command)
    // Bounded sinks: cap peak memory at a few KB regardless of how much the remote command
    // emits, so a high-throughput command can't OOM the app before we truncate. See
    // [BoundedOutputStream].
    val stdout = BoundedOutputStream(MAX_RETURNED_STDOUT)
    val stderr = BoundedOutputStream(MAX_RETURNED_STDERR)
    val channel = session.openChannel("exec") as ChannelExec
    var hitDeadline = false
    try {
        channel.setCommand(effectiveCommand)
        channel.outputStream = stdout
        channel.setErrStream(stderr)
        // Feed stdin then EOF. Real `ssh host 'cmd'` closes stdin when there's no piped input,
        // but JSch leaves the channel's stdin open with neither data nor EOF unless we set an
        // input stream — so a command that reads stdin (`cat > file`, `read`, `base64 -d`)
        // blocks until the deadline. An empty stream yields an immediate EOF; a non-null [stdin]
        // is delivered first and then EOF, which is the quote-free way to pipe data or write a
        // file without a heredoc.
        channel.setInputStream(ByteArrayInputStream((stdin ?: "").toByteArray(Charsets.UTF_8)))
        channel.connect(timeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!channel.isClosed) {
            if (System.currentTimeMillis() >= deadline) { hitDeadline = true; break }
            try { Thread.sleep(50) } catch (_: InterruptedException) {
                // Coroutine cancellation propagated through runInterruptible. Bail out.
                Thread.currentThread().interrupt()
                break
            }
        }
        if (hitDeadline) {
            // Command outlasted its timeout. Channel.exitStatus would return -1 here, which
            // earlier versions reported as exit_code=-1 + success=false — indistinguishable
            // from a real exit code -1. Make the timeout explicit so the model can choose
            // to bump timeout_seconds rather than retrying with a different command.
            return ToolErrors.envelopeFor(error = "command_timeout", message = "Command timed out", hint = "Command did not complete within ${timeoutMs / 1000}s. Bump timeout_seconds, or pass background=true to launch it detached so the call returns immediately with the launched PID. Partial stdout/stderr captured before the timeout is included.", extra = ToolErrors.extraOf("partial_stdout" to stdout.snapshot(), "partial_stderr" to stderr.snapshot()))
        }
        val exitCode = channel.exitStatus
        return buildJsonObject {
            put("success", exitCode == 0)
            put("exit_code", exitCode)
            put("stdout", stdout.snapshot())
            put("stderr", stderr.snapshot())
        }
    } finally {
        try { channel.disconnect() } catch (_: Throwable) {}
    }
}

/**
 * One-shot SSH exec — for hosts the user doesn't want to save (anonymous / ad-hoc / one-time).
 * For frequently-used hosts, prefer save_ssh_host + ssh_exec_saved which is shorter for the LLM
 * to call and avoids leaking credentials into chat history on every call.
 *
 * @param settingsStore 全局默认平台来源；为 null 时全局默认一级不参与解析（显式参数与
 * banner 缓存仍生效）。调用方按已有传参方式从构造处传入。
 */
fun sshExecTool(context: Context, settingsStore: SettingsStore? = null): Tool = Tool(
    name = "ssh_exec",
    description = """
        SSH to a host and run one shell command; returns stdout/stderr/exit code. Confirm
        with the user before destructive/system-level commands. For repeat hosts prefer
        save_ssh_host + ssh_exec_saved so credentials never appear in chat history.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", buildJsonObject { put("type", "string"); put("description", "Hostname or IP address") })
                put("port", buildJsonObject { put("type", "integer"); put("description", "SSH port, default 22") })
                put("user", buildJsonObject { put("type", "string"); put("description", "SSH username") })
                put("password", buildJsonObject { put("type", "string"); put("description", "Password (use only if no private_key)") })
                put("private_key", buildJsonObject { put("type", "string"); put("description", "Full PEM/OpenSSH private key contents") })
                put("passphrase", buildJsonObject { put("type", "string"); put("description", "Optional passphrase for the private key") })
                put("command", buildJsonObject { put("type", "string"); put("description", "Shell command to run on the remote host") })
                put("commands", buildJsonObject {
                    put("type", "array")
                    put("description", "A batch of commands to run in ONE connection (joined into a single script: newline-separated for POSIX, '; '-separated when any entry looks like PowerShell). Use instead of `command` to avoid one handshake per command — the app has no connection multiplexing, so batching is the cheapest way to run several steps.")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("stdin", buildJsonObject { put("type", "string"); put("description", "Optional data piped to the command's stdin (then EOF). Quote-free way to write a file (command=\"cat > /path\") or feed input; omit to send an immediate EOF.") })
                put("background", buildJsonObject { put("type", "boolean"); put("description", "If true, launch the command fully detached (nohup, streams redirected) and return immediately with its PID instead of waiting. Use for servers/long jobs that would otherwise block until timeout. Default false.") })
                put("env", buildJsonObject {
                    put("type", "object")
                    put("description", "Optional env vars for this call, e.g. {\"TOKEN\":\"abc\"}. Avoids inline `set X=...` quoting. Not supported when background=true. A value starting with `\$\$` is resolved by name from the credential vault (`\$\$CRED_NAME` → that credential's plaintext, same as workspace_shell's env; `\$\$CRED.field` → one plaintext metadata field of it, e.g. `\$\$MY_S3.access_key_id`, which needs no vault authorization because such fields are stored unencrypted); every other value is used literally as-is. Vault lookup requires an authorized vault session; an unresolvable `\$\$` reference fails the call rather than injecting the name.")
                })
                put("env_style", buildJsonObject {
                    put("type", "string")
                    put("description", "Environment syntax for `env`: 'auto' (default; assumes a Windows/pwsh remote, where `\$env:NAME` is used) or 'posix' (Linux/其他远端，使用 `NAME=... ; export NAME`).")
                })
                put("platform", buildJsonObject {
                    put("type", "string")
                    put("description", "Remote platform: 'auto' (default; falls back to the cached banner result, then the global default, then command-shape heuristics), 'linux' or 'windows'. Pass explicitly when the remote platform is known so command wrapping and env syntax pick the right form on the first call.")
                })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Total timeout including connect+exec, default 30, max 300") })
            },
            required = listOf("host", "user")
        )
    },
    execute = {
        val p = it.jsonObject
        val host = p["host"]?.jsonPrimitive?.contentOrNull ?: error("host is required")
        val user = p["user"]?.jsonPrimitive?.contentOrNull ?: error("user is required")
        // command / commands 二选一（可同给，commands 追加在 command 之后），合并成一次连接里的单条脚本
        val command =
            run {
                val single = p["command"]?.jsonPrimitive?.contentOrNull
                val batch: List<String> =
                    (p["commands"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                        .orEmpty()
                val joined = joinCommandBatch(listOfNotNull(single) + batch)
                if (joined.isBlank()) error("command or commands is required")
                joined
            }
        val port = p["port"]?.jsonPrimitive?.intOrNull ?: 22
        val stdin = p["stdin"]?.jsonPrimitive?.contentOrNull
        val background = p["background"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val auth = SshAuth(
            password = p["password"]?.jsonPrimitive?.contentOrNull,
            privateKey = p["private_key"]?.jsonPrimitive?.contentOrNull,
            passphrase = p["passphrase"]?.jsonPrimitive?.contentOrNull,
        )
        val timeoutSec = (p["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 300)
        if (!auth.isUsable()) {
            return@Tool listOf(UIMessagePart.Text(
                ToolErrors.envelopeFor(error = "invalid_argument", message = "must provide password or private_key", hint = "Check the parameter values and retry with corrected arguments.").toString()
            ))
        }
        if (background && stdin != null) {
            return@Tool listOf(UIMessagePart.Text(
                ToolErrors.envelopeFor(error = "invalid_argument", message = "stdin and background are mutually exclusive (a detached command reads from /dev/null)", hint = "Check the parameter values and retry with corrected arguments.").toString()
            ))
        }
        // 平台判定：显式参数 > 落盘缓存（探针结论优先于 banner 结论）> 全局默认设置；
        // 都未命中再退回命令特征
        val explicitPlatform = p["platform"]?.jsonPrimitive?.contentOrNull?.let { SshPlatform.fromPreference(it) }
        val knownPlatform = probePreferredWindows(context, host, port, user)
        val globalDefault = settingsStore?.settingsFlow?.value?.defaultSshPlatform
        val resolvedWindows = RemotePlatform.resolveWindows(explicitPlatform, knownPlatform, globalDefault)
        val (detachedCmd, bgLogPath) = if (background) wrapDetachedCommandSmart(command, windows = resolvedWindows) else (withUtf8ConsoleEncoding(command, resolvedWindows) to null)
        // env：以「同会话前置赋值语句」注入（background 走 detached 双层包装，不支持 env）。
        // 两种情况此前都是静默的（background 带 env 被忽略、非法键名被 envPrelude 丢弃）→ 现执行前显式拒绝。
        val envParam = p["env"] as? kotlinx.serialization.json.JsonObject
        envParamRejection(background, envParam)?.let { msg ->
            return@Tool listOf(UIMessagePart.Text(msg))
        }
        val env = readEnvParam(p)
        // 远端平台无法从命令内容可靠推断（验证：`cmd /c ...` 曾被判成 POSIX → 远端 pwsh 报错），
        // 因此默认按 Windows($env:) 处理，Linux 远端显式传 env_style=posix。
        // env_style 未显式指定时用已解析的平台（显式参数/缓存/全局默认）决定默认语法，
        // 仍未解析出来时保持旧默认 Windows。
        val envStyle = p["env_style"]?.jsonPrimitive?.contentOrNull
        val envWindows = when {
            envStyle.equals("posix", ignoreCase = true) -> false
            envStyle.equals("windows", ignoreCase = true) -> true
            else -> resolvedWindows ?: true
        }
        val envPrefix =
            if (!background && env.isNotEmpty()) envPrelude(env, envWindows) else emptyList()
        val effectiveCommand =
            if (envPrefix.isEmpty()) detachedCmd else joinCommandBatch(envPrefix + listOf(detachedCmd))
        val payload = runCancellableSshOp(timeoutSec * 1000L) { sessionRef ->
            execOneShot(context, host, port, user, auth, effectiveCommand, timeoutSec * 1000, sessionRef, stdin)
        }
        val finalPayload = if (bgLogPath != null) {
            payload.toString() + "\n[bg_log_path] $bgLogPath"
        } else payload.toString()
        listOf(UIMessagePart.Text(finalPayload))
    }
)

/**
 * Wrap a suspend SSH operation in coroutine-friendly cancellation. JSch's blocking JNI
 * socket reads do NOT honour Thread.interrupt() reliably, so withTimeoutOrNull alone
 * leaves the operation running on the IO thread until the OS TCP timeout (~75s on most
 * kernels) even though we've already returned "timeout" to the model.
 *
 * Strategy: the inner [block] stashes its live Session in [sessionRef] as soon as it's
 * open. If the outer withTimeoutOrNull cancels (or anything else throws), we forcibly
 * disconnect that Session in finally — closing the underlying socket unblocks JSch's JNI
 * read on the IO thread.
 */
internal suspend fun runCancellableSshOp(
    timeoutMs: Long,
    block: suspend (sessionRef: AtomicReference<Session?>) -> JsonObject,
): JsonObject {
    val sessionRef = AtomicReference<Session?>(null)
    return try {
        withTimeoutOrNull(timeoutMs) { block(sessionRef) }
            ?: ToolErrors.envelopeFor(error = "timeout", message = "Operation timed out", hint = "Retry; if it persists, narrow the scope of the operation.")
    } finally {
        // Forcibly disconnect the session if it's still open. Safe to call on an already-
        // disconnected session — JSch's Session.disconnect is idempotent.
        sessionRef.getAndSet(null)?.let { s ->
            try { s.disconnect() } catch (_: Throwable) {}
        }
    }
}

/**
 * Probe → connect → run → disconnect. The probe is suspend (parallel async); the JSch
 * handshake + exec are blocking, so we hand them off to runInterruptible(IO) which gives
 * us best-effort thread interrupt on coroutine cancellation. The Session is stashed in
 * [sessionRef] so the outer [runCancellableSshOp] can also forcibly disconnect from
 * outside if interrupt isn't honoured by JNI.
 */
@Suppress("TooGenericExceptionCaught", "LongParameterList") // 建连参数本就一身（地址/端口/用户/凭证/命令/超时/跳板/选项），拆开只会把同一上下文拆散。
internal suspend fun execOneShot(
    context: Context,
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    command: String,
    timeoutMs: Int,
    sessionRef: AtomicReference<Session?>,
    stdin: String? = null,
    /** 跳板（saved host 名已在 SshHostsTool 解析为 JumpSpec）。非空时目标经跳板隧道连 */
    jump: JumpSpec? = null,
    /** 自定义 ssh 选项（每行 "键 值"），透传 openSshSession */
    extraOptions: String? = null,
    /**
     * 主机密钥轮换开关：建连遇 host key 变更时，先清掉该 host 的旧 key 再单次重试连接。
     * 默认关——关时保持原有行为（结构化 host_key_changed 信封，不自动清 key）。
     */
    allowHostKeyRotation: Boolean = false,
): JsonObject {
    // Stage 1 (suspend): low-level reachability probe in parallel across every transport.
    // JSch's connect timeout fires at the END of the SSH handshake, so when the network is
    // silently broken the LLM sees a 30s "timeout" with no clue why. Probing with a raw
    // java.net.Socket first lets us tell the model exactly which layer is failing, AND
    // pick the working network for JSch to bind to.
    // 跳板场景：探测目标是跳板主机（隧道由跳板代达），而非目标本身——目标可能只在
    // 跳板的内网可达，直连探测必然失败会误报 unreachable。
    val outcome = if (jump != null) {
        probeReachability(context, jump.host, jump.port)
    } else {
        probeReachability(context, host, port)
    }
    if (outcome.winningNetwork == null && outcome.failures.isNotEmpty()) {
        return unreachableEnvelope(if (jump != null) jump.host else host, if (jump != null) jump.port else port, outcome)
    }

    // Stage 2 (suspend): 带重试的 JSch 握手。重试探测与退避是 suspend 的，
    // 不能包在 runInterruptible 的非 suspend 块里，故握手先在 suspend 上下文完成。
    // 主机密钥轮换：开关开且报错是 host key 变更时，先清旧 key 再单次重连；
    // 清 key 失败（写不回）则不再重试，直接返回原错误——重连必然同样失败。
    val handshakeStart = System.currentTimeMillis()
    val connection = try {
        connectSshWithRetry(context, host, port, user, auth, timeoutMs, outcome.winningNetwork, jump, extraOptions)
    } catch (error: Throwable) {
        if (!allowHostKeyRotation || !isHostKeyChange(error.message)) {
            AppLog.w(TAG_SSH, "ssh handshake failed in ${System.currentTimeMillis() - handshakeStart}ms", error)
            return wrapConnectError(host, error)
        }
        val forgotten = runCatching { forgetHostKey(context, host) }.getOrNull()
        if (forgotten == null) {
            AppLog.w(TAG_SSH, "ssh handshake failed in ${System.currentTimeMillis() - handshakeStart}ms (forget host key failed)", error)
            return wrapConnectError(host, error)
        }
        AppLog.i(TAG_SSH, "host key changed for $host, forgot ${forgotten.totalRemoved} stored key(s), retrying once")
        try {
            connectSshWithRetry(context, host, port, user, auth, timeoutMs, outcome.winningNetwork, jump, extraOptions)
        } catch (retryError: Throwable) {
            AppLog.w(TAG_SSH, "ssh handshake retry after host key rotation failed in ${System.currentTimeMillis() - handshakeStart}ms", retryError)
            return wrapConnectError(host, retryError)
        }
    }
    val session = connection.session
    val tunnel = connection.tunnel
    sessionRef.set(session)
    AppLog.i(TAG_SSH, "ssh session up via ${outcome.winningLabel ?: "default"} in ${System.currentTimeMillis() - handshakeStart}ms")

    // Stage 3 (blocking IO, interruptible): 命令执行。
    return try {
        runInterruptible(Dispatchers.IO) {
            // 平台判定回填：banner 结论落盘，供**后续**同主机的命令包装判定使用
            // （当次来不及——命令在建连前就已包装好，见 RemotePlatform 注释）。
            // ⚠ runInterruptible 的块非 suspend 上下文，故用 runBlocking 包一下：
            //   写的是一个小 DataStore（毫秒级），且当前已在 IO 线程上。
            runCatching {
                kotlinx.coroutines.runBlocking {
                    RemotePlatformStore(context).put(host, port, RemotePlatform.isWindowsServer(session.serverVersion))
                }
            }
            try {
                val result = runOnSession(session, command, timeoutMs, stdin)
                // 主机探针不在这里跑：探针最坏 30s，会吃掉调用方超时预算、丢弃已成功的
                // 命令输出（副作用已发生、模型重试会重复执行）。探针只走显式入口
                // （测试连接）或 AUTO 模式的进工作区自动补一次（独立协程 + 自有会话 +
                // 预算，不占调用方超时，见 WorkspaceRepositoryHelpers）。
                result
            } catch (e: Throwable) {
                ToolErrors.envelopeFor(error = "tool_failed", message = "exec failed: ${e.message ?: "unknown"}")
            } finally {
                sessionRef.set(null)
                try { session.disconnect() } catch (_: Throwable) {}
                tunnel?.close()
            }
        }
    } catch (e: Throwable) {
        // runInterruptible 在块执行前被取消时，块内 finally 没跑：这里断开，避免会话泄漏。
        // （块已执行时 finally 已断开，重复断开无害。）
        sessionRef.set(null)
        runCatching { session.disconnect() }
        runCatching { tunnel?.close() }
        throw e
    }
}

private data class SshConnectionResources(
    val session: com.jcraft.jsch.Session,
    val tunnel: JumpTunnelProxy?,
)

@Suppress("TooGenericExceptionCaught") // JSch and network APIs cross several unchecked/checked failure types; preserve retries without catching cancellation.
private suspend fun connectSshWithRetry(
    context: Context,
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    timeoutMs: Int,
    initialNetwork: android.net.Network?,
    jump: JumpSpec?,
    extraOptions: String?,
): SshConnectionResources {
    val jsch = newJSch(context)
    var attempt = 0
    while (attempt < 2) {
        attempt++
        try {
            // 每次重试重新选网，避免继续绑定已失效的Wi-Fi/蜂窝网络。
            // 探测是 suspend 的（包 runBlocking 会占住 IO 线程且取消不传导），故重试循环
            // 本身要是 suspend；每轮阻塞的 JSch 握手单独包进 runInterruptible。
            val network = if (attempt == 1) initialNetwork else probeNetworkForRetry(context, jump, host, port)
            val resources = runInterruptible(Dispatchers.IO) {
                connectSingleSshAttempt(jsch, host, port, user, auth, timeoutMs, network, jump, extraOptions)
            }
            if (jump != null) {
                connectJumpTarget(resources.session, resources.tunnel, timeoutMs)
            }
            return resources
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            throw error
        } catch (error: FakeIpResolutionException) {
            // fake-IP 是确定性失败：重试只会白跑一轮 DNS 探测，直接上抛
            // （由 wrapConnectError 转成带原因的明确信封）
            throw error
        } catch (error: Exception) {
            val retryable =
                !isAuthFailure(error.message) && !isHostKeyChange(error.message)
            if (attempt >= 2 || !retryable) throw error
            AppLog.w(TAG_SSH, "ssh handshake attempt $attempt failed, retrying: ${error.message}")
            delay(800)
        }
    }
    throw IllegalStateException("ssh connect failed after retries")
}

private fun connectSingleSshAttempt(
    jsch: com.jcraft.jsch.JSch,
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    timeoutMs: Int,
    network: android.net.Network?,
    jump: JumpSpec?,
    extraOptions: String?,
): SshConnectionResources {
    var tunnel: JumpTunnelProxy? = null
    var session: com.jcraft.jsch.Session? = null
    var keepResources = false
    try {
        val connected = if (jump != null) {
            val jumpSession = openSshSession(jsch, jump.host, jump.port, jump.user, jump.auth, timeoutMs, network = network)
            val jumpTunnel = JumpTunnelProxy(jumpSession, host, port)
            tunnel = jumpTunnel
            jsch.getSession(user, host, port).also { target ->
                session = target
                if (host != target.host) target.setHostKeyAlias(host)
                if (!auth.password.isNullOrBlank()) target.setPassword(auth.password)
                target.setConfig(Properties().apply {
                    setProperty("StrictHostKeyChecking", "accept-new")
                    setProperty("PreferredAuthentications", "publickey,keyboard-interactive,password")
                })
                if (!auth.privateKey.isNullOrBlank()) {
                    val keyBytes = auth.privateKey.ensureTrailingNewline().toByteArray(Charsets.UTF_8)
                    val passBytes = auth.passphrase?.toByteArray(Charsets.UTF_8)
                    jsch.addIdentity("rikkahub-ssh-key-${System.nanoTime()}", keyBytes, null, passBytes)
                }
                target.serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
                target.serverAliveCountMax = SERVER_ALIVE_COUNT_MAX
                target.setProxy(jumpTunnel)
            }
        } else {
            openSshSession(jsch, host, port, user, auth, timeoutMs, network = network, extraOptions = extraOptions)
        }
        session = connected
        // 跳板目标的握手在重试循环里单独做（suspend 看门狗），这里只建会话、不连接。
        keepResources = true
        return SshConnectionResources(connected, tunnel)
    } finally {
        if (!keepResources) {
            runCatching { session?.disconnect() }
            runCatching { tunnel?.close() }
        }
    }
}

/**
 * 跳板目标握手 + 看门狗：超时未完成则关闭隧道，逼停可能卡住的握手。
 *
 * 看门狗用协程（delay + launch）而不用裸 Thread：与重试循环同一作用域，握手结束
 * 即取消。失败时收拾本轮建好的会话/隧道，与 connectSingleSshAttempt 的 finally
 * 同一口径（调用方不再重复清理）；取消原样上抛。
 */
private suspend fun connectJumpTarget(
    session: com.jcraft.jsch.Session,
    tunnel: JumpTunnelProxy?,
    timeoutMs: Int,
) {
    try {
        coroutineScope {
            val watchdog = launch {
                delay(JUMP_HANDSHAKE_TIMEOUT_MS.toLong())
                AppLog.w(TAG_SSH, "jump handshake watchdog fired after ${JUMP_HANDSHAKE_TIMEOUT_MS}ms, aborting tunnel")
                runCatching { tunnel?.close() }
            }
            try {
                runInterruptible(Dispatchers.IO) { session.connect(timeoutMs) }
            } finally {
                watchdog.cancel()
            }
        }
    } catch (e: CancellationException) {
        runCatching { tunnel?.close() }
        runCatching { session.disconnect() }
        throw e
    } catch (e: Exception) {
        runCatching { tunnel?.close() }
        runCatching { session.disconnect() }
        throw e
    }
}

/**
 * Translate JSch connection failures into structured envelopes the LLM can act on. Three
 * distinct branches:
 *   - host_key_changed: server identity differs from known_hosts. Hint to call
 *     ssh_forget_host_key only after explicit user confirmation.
 *   - auth_failed: credentials rejected. Hint to verify with user before retrying so the
 *     model doesn't burn round-trips brute-forcing the same wrong password.
 *   - generic connect_failed: anything else.
 */
/** 判断是否为主机密钥变化错误（不重试，需用户确认后 forget host key）。 */
internal fun isHostKeyChange(msg: String?): Boolean {
    val m = msg.orEmpty()
    return m.contains("HostKey", ignoreCase = true) ||
        m.contains("host key", ignoreCase = true) ||
        m.contains("identification has changed", ignoreCase = true) ||
        m.contains("REMOTE HOST IDENTIFICATION", ignoreCase = true)
}

/** 判断是否为认证失败（不重试，避免锁死账户）。 */
internal fun isAuthFailure(msg: String?): Boolean {
    val m = msg.orEmpty()
    return m.contains("Auth fail", ignoreCase = true) ||
        m.contains("auth cancel", ignoreCase = true) ||
        m.contains("USERAUTH fail", ignoreCase = true) ||
        m.contains("Authentication failed", ignoreCase = true) ||
        m.contains("Permission denied (publickey", ignoreCase = true) ||
        m.contains("Permission denied (password", ignoreCase = true)
}

internal fun wrapConnectError(host: String, e: Throwable): JsonObject {
    if (e is FakeIpResolutionException) {
        return ToolErrors.envelopeFor(
            error = FAKE_IP_ERROR,
            message = FAKE_IP_ERROR,
            hint = "${e.host} resolved to ${e.fakeIp}, an address from the reserved fake-IP " +
                "range 198.18.0.0/15 — a proxy/VPN on this device is answering DNS for it, and " +
                "SSH from this app does not travel through that VPN, so the virtual address is " +
                "unreachable. Use the server's real IP, or adjust the proxy's split-routing / " +
                "fake-ip rules so this domain resolves normally.",
            extra = ToolErrors.extraOf(
                "host" to e.host,
                "ip" to e.fakeIp,
                "resolution" to "fake_ip_block",
            ),
        )
    }
    val msg = e.message.orEmpty()
    if (isHostKeyChange(msg)) {
        return ToolErrors.envelopeFor(error = "host_key_changed", message = "host_key_changed", hint = "Stored key for $host doesn't match what the server presented. If the user trusts this host (e.g. they just reinstalled it), call ssh_forget_host_key with host=\"$host\" then retry. Do NOT forget the key without explicit user confirmation — a changed key can also indicate an attacker.", extra = ToolErrors.extraOf("host" to host, "raw" to msg))
    }
    if (isAuthFailure(msg)) {
        return ToolErrors.envelopeFor(error = "auth_failed", message = "Authentication failed", hint = "Credentials rejected by $host. Verify the password / private_key / username with the user before retrying — DO NOT keep guessing or you'll lock the account out. If using a private_key, double-check the user passed the FULL PEM contents (including the BEGIN/END markers).", extra = ToolErrors.extraOf("host" to host, "raw" to msg))
    }
    return ToolErrors.envelopeFor(error = "connect_failed", message = "Connection failed", hint = "Check the host/port and retry.", extra = ToolErrors.extraOf("host" to host, "reason" to (msg.ifBlank { e::class.simpleName ?: "unknown" })))
}

private fun writeKnownHostsWithoutHost(
    tempFile: File,
    currentKeys: List<com.jcraft.jsch.HostKey>,
    host: String,
) {
    FileOutputStream(tempFile).bufferedWriter(Charsets.UTF_8).use { writer ->
        for (key in currentKeys) {
            if (key.host == host) continue
            val marker = key.marker?.takeIf { it.isNotEmpty() }
            if (marker != null) writer.append('@').append(marker).append(' ')
            writer.write(key.host)
            writer.write(" ${key.type} ${key.key}")
            writer.newLine()
        }
    }
}

/** [forgetHostKey] 的清理结果：App 与 Vault 两份 known_hosts 各自清掉的条数。 */
internal data class ForgetHostKeyResult(
    val appKnownHostsRemoved: Int,
    val vaultKnownHostsRemoved: Int,
) {
    val totalRemoved: Int get() = appKnownHostsRemoved + vaultKnownHostsRemoved
}

/**
 * Remove all stored host keys for [host] from both known_hosts files: the app's
 * `filesDir/known_hosts` and the Vault's `filesDir/vault_known_hosts`. Use after the
 * user confirms they reinstalled the remote — the next connect will trust the new key per
 * the accept-new policy.
 *
 * IMPORTANT: JSch's [com.jcraft.jsch.HostKeyRepository.remove] only updates the in-memory
 * KnownHosts; it does not write back to the file. JSch's KnownHosts.dump(OutputStream)
 * exists but is package-private, so we manually re-serialise the in-memory list to the
 * known_hosts(5) format. Without this, the next newJSch() reloads from the unchanged file
 * and the "host key changed" error returns immediately — i.e. ssh_forget_host_key silently
 * no-ops.
 *
 * A missing Vault file counts as 0 removed, not an error (the Vault side only writes its
 * file on first connect, so a host touched only via saved hosts has no entry there).
 */
internal fun forgetHostKey(context: Context, host: String): ForgetHostKeyResult {
    val appRemoved = forgetHostKeyFromFile(newJSch(context), knownHostsFile(context), host)
    val vaultFile = File(context.filesDir, "vault_known_hosts")
    val vaultRemoved = if (!vaultFile.exists()) {
        0
    } else {
        val vaultJsch = com.jcraft.jsch.JSch()
        runCatching { vaultJsch.setKnownHosts(vaultFile.absolutePath) }
        forgetHostKeyFromFile(vaultJsch, vaultFile, host)
    }
    return ForgetHostKeyResult(appRemoved, vaultRemoved)
}

/**
 * Remove all stored host keys for [host] from one known_hosts file, using [jsch]'s
 * in-memory repository (already loaded from [file]) as the source of truth and writing
 * the remainder back with the shared serialiser. Returns the removed count; 0 removes
 * nothing and leaves the file untouched.
 */
private fun forgetHostKeyFromFile(
    jsch: com.jcraft.jsch.JSch,
    file: File,
    host: String,
): Int {
    val repo = jsch.hostKeyRepository
    val currentKeys = repo.hostKey?.toList().orEmpty()
    val removed = currentKeys.count { it.host == host }
    if (removed == 0) return 0

    val parent = file.parentFile ?: throw IOException("known_hosts has no parent directory")
    if (!parent.exists() && !parent.mkdirs()) {
        throw IOException("Could not create known_hosts directory ${parent.absolutePath}")
    }
    val temp = File(parent, "${file.name}.tmp-${System.nanoTime()}")
    try {
        writeKnownHostsWithoutHost(temp, currentKeys, host)
        replaceFilePreservingTarget(temp, file)
    } finally {
        if (temp.exists() && !runCatching { temp.delete() }.getOrDefault(false)) {
            AppLog.w(TAG_SSH, "Could not remove temporary known_hosts file ${temp.name}")
        }
    }

    // Keep the in-memory view unchanged unless the durable file update succeeded.
    repo.remove(host, null)
    return removed
}
