package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.data.ai.tools.ToolErrors

import me.rerere.rikkahub.data.vault.CredentialPurpose
import me.rerere.rikkahub.data.vault.CredentialResolution
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.vault.message
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.VAULT_REF_PREFIX
import me.rerere.rikkahub.data.vault.VaultSessionManager
import me.rerere.rikkahub.data.vault.isVaultReference
import android.content.Context
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import org.koin.java.KoinJavaComponent.getKoin

private const val TAG_SSH = "SshTool"


/** Per-network probe timeout. Tight because we now race networks in parallel. */
internal const val PROBE_PER_NETWORK_TIMEOUT_MS = 2_500
/** Default per-network connect timeout used when JSch creates its own socket. */
internal const val SOCKET_CONNECT_TIMEOUT_MS = 10_000

/**
 * Keep-alive parameters for every JSch session. Without these, JSch sends nothing on an idle
 * channel, so a long-running command (e.g. a build, a `tail -f`) sits silent long enough for
 * an intermediate NAT/firewall to drop the connection's state table entry — the next byte
 * then hits a black hole and the command "dies" with no error. 30s × 3 missed = ~90s before
 * JSch itself declares the session dead, which keeps NATs warm while still failing fast on a
 * genuinely-gone server.
 */
internal const val SERVER_ALIVE_INTERVAL_MS = 30_000
internal const val SERVER_ALIVE_COUNT_MAX = 3

/**
 * 经跳板（Proxy）握手的总时长上限。JSch 在代理路径下**不会**设 socket 读超时：
 * Session.connect 里 `socket = proxy.getSocket()` 之后仅当 `socket != null` 才
 * `setSoTimeout(connectTimeout)`，而合法的 Proxy.getSocket() 返回 null（标准 ProxyCommand
 * 同款）→ 网络切换/抖动时经隧道的握手会一直挂到对端关闭（可长达数分钟）。
 * 看门狗到点关闭隧道，让挂住的读立即抛错、按既有逻辑（重新选网）重试。
 */
internal const val JUMP_HANDSHAKE_TIMEOUT_MS = 20_000

/**
 * Bounds for the independent (system-resolver-bypassing) DNS path. Only used once the system
 * resolver has already returned a reserved fake-IP block address, i.e. in a situation that
 * could not connect anyway — so a couple of seconds of extra probing costs nothing new.
 */
internal const val INDEPENDENT_DNS_TIMEOUT_MS = 1_500
internal const val INDEPENDENT_DNS_SERVER_LIMIT = 4

/** Stable error code for "your proxy/VPN hijacked DNS into a fake-IP block". */
internal const val FAKE_IP_ERROR = "dns_fake_ip"

/**
 * Process-lifetime DNS cache shared by every SSH session-build path. 60s TTL — long enough
 * to spare a 15-minute-interval workflow a fresh lookup on every run, short enough that a
 * DHCP/DNS change is picked up quickly. Self-registers with NetworkChangeMonitor so a
 * default-network handoff drops the cache (a cached IP from the old network's DNS could be
 * wrong on the new one).
 */
internal val sshDnsCache: DnsCache = DnsCache().also { cache ->
    runCatching {
        me.rerere.rikkahub.utils.NetworkChangeMonitor.addNetworkChangeListener {
            cache.invalidateAll()
        }
    }
}

/**
 * Caps on stdout / stderr returned to the LLM. Without these, `cat /var/log/syslog` /
 * `journalctl` / `ls -R /` push megabytes into the next prompt, blowing the context
 * budget on a single tool call. Mirrors the Termux tool's caps so behaviour is
 * consistent across local and remote shell.
 */
internal const val MAX_RETURNED_STDOUT = 8_000
internal const val MAX_RETURNED_STDERR = 2_000

/**
 * Bounded stdout/stderr sink for a remote command. JSch streams the remote process's output
 * into the OutputStream we hand the channel; a previous version pointed that at an unbounded
 * ByteArrayOutputStream and only truncated AFTER, so `cat /dev/zero | base64` (or any chatty
 * remote command) could pull megabytes into the heap and OOM the app before we ever truncated.
 * This keeps at most [cap] + [SLACK] bytes in memory and discards the rest, counting what it
 * threw away so the user still sees a faithful "[truncated; N bytes more]" suffix.
 *
 * The slack lets us hold a few bytes past [cap] so [snapshot] can render the truncation marker
 * without splitting a trailing multi-byte UTF-8 sequence. [cap] and the discarded-byte count
 * are both measured in BYTES so multibyte (CJK / emoji) output is sized honestly: a char-based
 * kept slice would over- or under-shoot the byte budget and, worse, falsely report truncation
 * when the byte count (total) exceeds the char-count cap even though nothing was discarded.
 */
internal class BoundedOutputStream(private val cap: Int) : java.io.OutputStream() {
    private val buf = ByteArrayOutputStream(minOf(cap + SLACK, 16_384))
    /** Total bytes the remote process wrote, including the ones we discarded. */
    private var total: Long = 0L

    override fun write(b: Int) {
        total += 1
        if (buf.size() < cap + SLACK) buf.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        total += len
        val remaining = (cap + SLACK) - buf.size()
        if (remaining > 0) buf.write(b, off, minOf(len, remaining))
    }

    /**
     * Render the captured bytes with a "[truncated; N bytes more]" tail derived from the true
     * byte count we observed — the discarded bytes never hit the heap, so a post-hoc length
     * check on the buffer alone couldn't know about them.
     *
     * Everything here is byte-accurate: we keep at most [cap] bytes (snapped down to a UTF-8
     * code-point boundary so a trailing multi-byte sequence is never split) and report the
     * remainder as (total bytes written minus bytes kept). When nothing exceeded [cap] there is no
     * marker, even for multibyte text whose byte count outruns its char count.
     */
    fun snapshot(): String {
        val bytes = buf.toByteArray()
        if (total <= cap.toLong()) return String(bytes, Charsets.UTF_8)
        // Keep up to [cap] bytes, backing off to a code-point boundary so we don't slice a
        // multi-byte UTF-8 sequence in half. The buffer holds cap + SLACK bytes, so there is
        // always at least one whole code point's worth of headroom past the cut.
        var keep = minOf(cap, bytes.size)
        while (keep in 1 until bytes.size && (bytes[keep].toInt() and 0xC0) == 0x80) keep--
        val kept = String(bytes, 0, keep, Charsets.UTF_8)
        return kept + "\n…[truncated; ${total - keep.toLong()} bytes more]"
    }

    private companion object {
        /** Headroom past [cap] so a final multi-byte UTF-8 sequence isn't split. */
        const val SLACK = 4_096
    }
}

/**
 * Single-quote [s] for safe embedding in a POSIX shell command. Wraps the whole string in
 * single quotes and rewrites each embedded single quote as the standard `'\''` sequence
 * (close-quote, escaped literal quote, reopen-quote). Lets us splice an arbitrary user command
 * into a wrapper without a quoting injection.
 */
internal fun shellSingleQuote(s: String): String =
    "'" + s.replace("'", "'\\''") + "'"

/**
 * Wrap [command] so it runs fully detached from the SSH exec channel and returns immediately.
 *
 * A bare `cmd &` does NOT free the exec channel: the backgrounded child inherits the channel's
 * stdout/stderr pipes, so JSch never sees channel EOF and ssh_exec blocks until its timeout even
 * though the foreground shell already exited. We instead launch under `nohup` (immune to the
 * SIGHUP fired when the session closes), redirect all three std streams away from the channel
 * pipes, background it, and echo the launched PID. The channel then reaches EOF at once and the
 * call returns the PID instead of hanging.
 */
internal fun wrapDetachedCommand(command: String): String =
    "nohup sh -c ${shellSingleQuote(command)} >/dev/null 2>&1 </dev/null & echo \"rikkahub_bg_pid=\$!\""

/**
 * 判断 [command] 是不是「Windows/PowerShell 风格」的命令行。
 *
 * 用于 detached 包装与输出编码修复的选路。历史上只看 `powershell`/`pwsh` 前缀与 `C:\`
 * 等字面量，导致 `Start-Sleep -Seconds 5; Write-Output done` 这类**纯 cmdlet 命令**被误判为
 * POSIX → 用 `nohup sh -c …` 包装 → Windows 主机上必然 ParserError（已验证）。
 * 现在补上 cmdlet 特征（动词-名词、`-Command`、`$env:`、`$_` 等）。
 *
 * 先排除 POSIX 外壳前缀（cmd/wsl/bash/sh/zsh）——它们即使出现在 Windows 主机上，
 * 也应按原样直接执行，不能加 PowerShell 包装。
 */
internal fun looksLikeWindowsCommand(command: String): Boolean {
    val c = command.trim()
    if (c.isEmpty()) return false
    val posixShellPrefix = Regex("""^\s*(cmd(\.exe)?|wsl|bash|sh|zsh|dash|ash)\b""", RegexOption.IGNORE_CASE)
    if (posixShellPrefix.containsMatchIn(c)) return false
    if (Regex("""^\s*(powershell|pwsh)(\.exe)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(c)) return true
    if (c.contains(":\\") || c.contains("cmd /c") || c.contains("cmd.exe /c")) return true
    return WINDOWS_CMDLET_REGEX.containsMatchIn(c)
}

/** PowerShell 命令特征：`$env:` / `$_` / `$?` 变量，或常见 cmdlet 动词-名词。 */
private val WINDOWS_CMDLET_REGEX = Regex(
    """(?i)([$]env:|[$]_|[$][?]|\b(Select|Where|ForEach|Measure|Sort|Group|Tee|Out|Write|Read|Get|Set|New|Remove|Copy|Move|Rename|Test|Start|Stop|Restart|Invoke|Add|Clear|Enable|Disable|Export|Import|Join|Split|ConvertTo|ConvertFrom|Wait|Resolve|Format|Show|Update)-[A-Za-z]+)""",
)

/** UTF-8 输出前缀（Windows/PowerShell 专用）；幂等判据以此常量为唯一依据。 */
internal const val UTF8_CONSOLE_PREFIX =
    "[Console]::OutputEncoding=[Text.Encoding]::UTF8; \$OutputEncoding=[Text.Encoding]::UTF8; "

/**
 * 给 Windows/PowerShell 命令补上「输出转 UTF-8」前缀，修中文乱码。
 *
 * 验证（pwsh 7.7 会话）：远端默认 `[Console]::OutputEncoding` = gb2312，
 * 中文字节流回到 App 按 UTF-8 解码 → 全是 `????`/乱码；命令前加这一句即恢复。
 * `cmd.exe /c …` 之类显式 POSIX/exe 前缀不动（那是另一套代码页，乱码需 chcp，另行处理）。
 *
 * @param windows 已知道的远端平台（如 SSH banner 判定结果）；`null` = 未知，退回命令特征判据。
 */
internal fun withUtf8ConsoleEncoding(command: String, windows: Boolean? = null): String {
    val isWindows = windows ?: looksLikeWindowsCommand(command)
    if (!isWindows) return command
    // 幂等只看**开头**是否已有前缀。旧实现用 `contains("[Console]::OutputEncoding")`：
    // 命令里只要“提到”该字符串（例如查当前编码）就被整体跳过 → 反而漏包装、中文照旧乱码
    // （验证时踩到）。
    if (command.startsWith(UTF8_CONSOLE_PREFIX)) return command
    return UTF8_CONSOLE_PREFIX + command
}

/**
 * env 前置语句：Windows 用 `$env:X='v'`，POSIX 用 `X='v'; export X`。
 *
 * 由 [joinCommandBatch] 拼在命令之前，与命令在**同一进程**内执行，故后续语句可见；
 * 键名走白名单、值按各自语法转义，免去调用方内联 `set X=...` 的引号困扰。
 *
 * 非法键名（不匹配 [ENV_KEY_REGEX]）在此被丢弃——调用方须先用 [invalidEnvKeys] 把它们
 * 作为错误显式返回给模型，不能依赖本函数静默过滤。
 */
internal fun envPrelude(env: Map<String, String>, windows: Boolean): List<String> =
    env.entries
        .filter { (k, _) -> ENV_KEY_REGEX.matches(k) }
        .map { (k, v) ->
            if (windows) {
                "\$env:$k='" + v.replace("'", "''") + "'"
            } else {
                "$k='" + v.replace("'", "'\\''") + "'; export $k"
            }
        }

/**
 * 读取工具参数里的 `env` 对象（只接受字符串值，其它忽略）。
 *
 * 值的语义与 `workspace_shell` 对齐（见 WorkspaceTools 的 `resolveInjectedEnv`）：
 * - 以 `$$` 开头 → 按名从凭证库解析（purpose = `ENV_INJECT`，走会话授权门 + 审计）；
 *   其中 `$$名字` 取主值（密文），`$$名字.字段` 取该条目的明文元数据（如 `$$MY_S3.access_key_id`，
 *   明文字段不过授权门 —— 它本来就是不加密存的，且 `vault_credential_meta` 早已无门可读）；
 * - 其余 → 按字面值，行为与本函数支持引用之前逐字一致。
 *
 * 解析失败**直接报错**，不回退字面值：静默回退等于把凭证名当值注入远端环境
 * （`env={"ZT_AI_CODE":"ZT_AI_CODE"}` 在远端变成 10 个字符的字符串），正是这次要修的坑。
 *
 * 依赖经 koin 获取（同 `resolveInjectedEnv` 的做法），因此签名不扩参数，
 * `ssh_exec` 与 `ssh_exec_saved` 两个调用点自动获得同一语义。
 */
internal suspend fun readEnvParam(p: kotlinx.serialization.json.JsonObject): Map<String, String> {
    val envObj = (p["env"] as? kotlinx.serialization.json.JsonObject) ?: return emptyMap()
    val literals = envObj.mapNotNull { (k, v) ->
        (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let { k to it }
    }.toMap()
    val referenced = literals.filter { (_, v) -> isVaultReference(v) }
    if (referenced.isEmpty()) return literals

    val vaultRepository = runCatching { getKoin().get<CredentialVaultRepository>() }.getOrNull()
        ?: error("env 注入失败：凭证库不可用")
    val appContext = runCatching { getKoin().get<Context>() }.getOrNull()
    // 单点解析器统一处理：会话授权 / 存在性 / 解密 / 审计（成功与拒绝都记）
    val resolver = CredentialResolver(vaultRepository) {
        appContext?.let { VaultSessionManager(it) }?.hasActiveAuthorization() == true
    }
    return literals.map { (k, v) ->
        if (!isVaultReference(v)) return@map k to v
        // 引用串本体（去前缀后）交给单点解析器：`名字` 取主值、`名字.字段` 取明文元数据字段
        // （env 注入正是"AK ID 要明文进环境、SK 仍走密文"的主用法）
        val credRef = v.trim().removePrefix(VAULT_REF_PREFIX).trim()
        val resolution = resolver.resolveRef(credRef, CredentialPurpose.ENV_INJECT, caller = "ssh-env")
        when (resolution) {
            // 只把真值交给 envPrelude；真值不进日志、不进返回给模型的文本
            is CredentialResolution.Granted -> k to resolution.value
            else -> error("env 注入失败：$k -> ${resolution.message}")
        }
    }.toMap()
}

private val ENV_KEY_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * 挑出不匹配 [ENV_KEY_REGEX] 的 env 键名。
 *
 * 背景：[envPrelude] 会静默丢弃非法键（不报错也不告知，模型以为注入成功）。现在两个 ssh 工具
 * 在执行前用本函数校验，把非法键作为 invalid_argument 显式退回。入参取工具参数 `env` 的**原始
 * JSON 对象**（而非解析后的 map），使注定被拒的调用不必先走一遍凭证解析。
 */
internal fun invalidEnvKeys(envParam: kotlinx.serialization.json.JsonObject?): List<String> =
    envParam?.keys?.filterNot { ENV_KEY_REGEX.matches(it) }.orEmpty()

/**
 * env 参数的前置拒绝判定：返回可直接回给模型的错误文本，null = 通过。
 *
 * 两种情况此前都是静默的：background=true 时 env 被忽略（与工具描述不符）、键名非法时被
 * [envPrelude] 丢弃（模型以为注入成功）。入参取原始 JSON 对象，使注定被拒的调用不必先走凭证解析。
 */
internal fun envParamRejection(
    background: Boolean,
    envParam: kotlinx.serialization.json.JsonObject?,
): String? {
    if (background && envParam != null && envParam.isNotEmpty()) {
        return ToolErrors.envelopeFor(
            error = "invalid_argument",
            message = "env is not supported when background=true (a detached command gets no same-session prelude assignments)",
            hint = "Drop env, or run with background=false; for long jobs wrap only the heavy part (nohup it inside the command) and keep env in the foreground call.",
        ).toString()
    }
    val badKeys = invalidEnvKeys(envParam)
    if (badKeys.isNotEmpty()) {
        return ToolErrors.envelopeFor(
            error = "invalid_argument",
            message = "env key(s) not matching [A-Za-z_][A-Za-z0-9_]*: ${badKeys.joinToString(", ")}",
            hint = "Rename or drop the offending keys and retry; nothing was executed on the remote host.",
        ).toString()
    }
    return null
}

/**
 * 平台自适应 detached 包装（POSIX 与 Windows 统一），返回 (detachedCommand, logPath)：
 * - POSIX：nohup（缺失则退 setsid，再退裸后台）落 /tmp/rikkahub_bg_<ts>.log，返回 logPath 供轮询
 * - Windows/pwsh：Start-Process 全脱钩 + 输出重定向日志，返回 logPath
 * 两者都让 background=true 的任务可被 ssh_job_poll 尾读进度——任何主机类型通用。
 */
internal fun wrapDetachedCommandSmart(command: String): Pair<String, String?> {
    if (!looksLikeWindowsCommand(command)) {
        // POSIX：日志落 /tmp（通用可写），输出与错误都进同一日志。
        // nohup 并非到处都有（验证：OpenWrt/ash 无 nohup，命令直接失败），
        // 因此按 nohup → setsid → 裸后台 逐级降级；整组后台化，$! 即该组的 pid。
        val logPath = "/tmp/rikkahub_bg_${System.currentTimeMillis()}.log"
        val body = shellSingleQuote(command)
        val wrapped = "{ command -v nohup >/dev/null 2>&1 && nohup sh -c $body >$logPath 2>&1 </dev/null" +
            " || command -v setsid >/dev/null 2>&1 && setsid sh -c $body >$logPath 2>&1 </dev/null" +
            " || sh -c $body >$logPath 2>&1 </dev/null; } & echo \"rikkahub_bg_pid=\$!\""
        return wrapped to logPath
    }

    // Windows：外层 EncodedCommand 脚本内 Start-Process 全脱钩（-WindowStyle Hidden），
    // 输出重定向日志文件。命令本身若已是 powershell 前缀，去掉再包（防嵌套）。
    // 剥除需容忍参数落行首（剥 powershell 后 -NoProfile 变行首），且 -Command "..." 
    // 的外层引号是语法分隔需一并剥掉，否则 Start-Process 会收到字面引号串。
    val inner = command
        .replace(Regex("^\\s*(powershell|pwsh|PowerShell)\\s+"), " ")
        .replace(Regex("(?i)\\s*-NoProfile\\s*"), " ")
        .replace(Regex("(?i)\\s*-NonInteractive\\s*"), " ")
        .replace(Regex("(?i)\\s*-Command\\s*"), " ")
        .trim()
        .let { if (it.length >= 2 && it.startsWith("\"") && it.endsWith("\"")) it.substring(1, it.length - 1) else it }
    val logPath = "C:\\Users\\Public\\rikkahub_bg_${System.currentTimeMillis()}.log"
    val errPath = logPath.substringBeforeLast(".log") + "-err.log"
    val script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; " +
        "Start-Process -FilePath powershell -ArgumentList @('-NoProfile','-NonInteractive','-Command',$(-f ${shellQuoteWin(inner)})) " +
        "-RedirectStandardOutput '$logPath' -RedirectStandardError '$errPath' -WindowStyle Hidden"
    val b64 = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    return "powershell -NoProfile -NonInteractive -EncodedCommand $b64" to logPath
}

/** PowerShell 单引号字符串转义（内嵌单引号翻倍）。 */
internal fun shellQuoteWin(s: String): String = "'" + s.replace("'", "''") + "'"

/**
 * 读取 `ssh_options` 文本里某个整型选项（每行「键 值」，# 为注释）。
 *
 * 用途：让默认值可被按主机覆盖。例如弱网/长任务把保活调密：
 * `ServerAliveInterval 10` + `ServerAliveCountMax 6`。未给出时返回 null，调用方落回默认。
 */
internal fun sshOptionInt(
    extraOptions: String?,
    key: String,
): Int? =
    extraOptions
        ?.lineSequence()
        ?.map { it.trim().removePrefix("\uFEFF") }
        ?.filter { it.isNotEmpty() && !it.startsWith("#") }
        ?.firstOrNull { it.length > key.length && it.startsWith(key, ignoreCase = true) && it[key.length].isWhitespace() }
        ?.substring(key.length)
        ?.trim()
        ?.toIntOrNull()

/**
 * 把「一批命令」合成一条脚本，供 `commands: [...]` 参数使用——一次 SSH 连接跑完，
 * 省掉逐条握手（App 侧无连接复用，合并是等价且更省的做法）。
 *
 * 分隔符按命令风格选：任一命令像 PowerShell/cmdlet 就用 `; `，否则用换行（POSIX）。
 * 空串条目被丢弃；全部为空时返回空串，调用方据此报参数错误。
 */
internal fun joinCommandBatch(commands: List<String>): String {
    val cleaned = commands.map { it.trim() }.filter { it.isNotEmpty() }
    if (cleaned.isEmpty()) return ""
    val separator = if (cleaned.any { looksLikeWindowsCommand(it) }) "; " else "\n"
    return cleaned.joinToString(separator)
}

