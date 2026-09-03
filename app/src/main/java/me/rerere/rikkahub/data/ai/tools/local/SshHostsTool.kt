package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.data.vault.ensureTrailingNewline
import android.content.Context
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.vault.CredentialVaultRepository

/**
 * 主机命令预设表（2026-09-03 静态内置版，后续可迁 Room 用户自定义）。
 *
 * 设计：按 saved host 名（或其 user/host 特征）匹配预设集；每条预设是
 * 常用操作模板，AI 用 ssh_exec_saved(preset="键") 直接展开执行——
 * 消除每次现场拼命令的乱码/引号/记错路径问题。
 *
 * 预留结构：后续加用户自定义时，把这里替换为 DB 字段 + 同构覆盖即可，
 * 工具层接口（preset 键）不变。
 */
internal object SshPresets {
    /**
     * 命令预设表（2026-09-03）。
     *
     * 设计：按 saved host 名匹配预设集；ssh_exec_saved(preset="键") 直接展开执行，
     * 消除每次手写命令的转义/路径错误。预设与 command 参数互斥。
     *
     * ⚠️ 通用性约定：本表只放**通用平台操作**（跨用户可用，如系统信息/进程/磁盘），
     * 不放任何个人环境命令（用户目录/项目路径/专属服务）——个人常用操作由
     * 各用户在自身环境文档/配置中维护（AI 按需查询展开），避免把私人环境
     * 编译进开源代码。示例命令中的 <占位> 由调用方替换后作为普通 command 执行。
     *
     * 扩展：后续支持用户自定义时，可在 Room 存同名覆盖表，工具层接口不变。
     */
    val byHost: Map<String, Map<String, String>> = mapOf(
        // 通用 POSIX 主机（Linux / BSD / OpenWrt / Termux 等）常用只读操作
        "linux" to mapOf(
            "系统信息" to "uname -a; uptime; cat /etc/os-release 2>/dev/null | head -2",
            "磁盘" to "df -h | head -10",
            "内存" to "free -h",
            "进程" to "ps aux | head -20",
            "最近日志" to "dmesg | tail -30 2>/dev/null || journalctl -n 30 --no-pager 2>/dev/null || echo 'no log access'",
        ),
        // 通用 Windows 主机（pwsh 语义；经 EncodedCommand 自动保真）
        "windows" to mapOf(
            "系统信息" to "powershell -NoProfile -Command \"Get-ComputerInfo | Select-Object CsName,WindowsProductName,WindowsVersion,OsArchitecture | Format-List\"",
            "磁盘" to "powershell -NoProfile -Command \"Get-PSDrive -PSProvider FileSystem | Select-Object Name,Used,Free | Format-Table -AutoSize\"",
            "进程" to "powershell -NoProfile -Command \"Get-Process | Sort-Object CPU -Descending | Select-Object -First 15 Name,Id,CPU | Format-Table -AutoSize\"",
            "服务列表" to "powershell -NoProfile -Command \"Get-Service | Where-Object {$_.Status -eq 'Running'} | Select-Object -First 20 Name,Status | Format-Table -AutoSize\"",
        ),
    )

    /** 运行时按主机名取预设集（小写匹配；无则按特征归类到平台模板）。 */
    fun forHost(hostName: String): Map<String, String> {
        val lower = hostName.lowercase()
        // 先精确匹配用户自定义模板（未来 Room 扩展；当前仅内置平台模板）
        byHost[lower]?.let { return it }
        // 特征归类：主机名含 Windows 平台信号 → windows 模板；其余按 linux 模板
        return if (PLATFORM_HINTS_WINDOWS.any { lower.contains(it) }) {
            byHost.getValue("windows")
        } else {
            byHost.getValue("linux")
        }
    }

    /** 判定主机属 Windows 平台的特征词（仅通用词，不含任何具体用户环境） */
    private val PLATFORM_HINTS_WINDOWS = listOf("pc", "win", "windows", "microsoft", "ms-")
}

/**
 * 从主机条目解析可用认证：
 * - vaultCredentialRef 非空 → 从 Vault 解密私钥（不明文存 Room）
 * - 否则回退明文 password/privateKey（旧数据兼容）
 * 返回 null 表示引用失效或无可用凭证。
 */
internal suspend fun resolveHostAuth(
    h: SshHostEntity,
    vaultRepository: CredentialVaultRepository,
): SshAuth? {
    if (h.vaultCredentialRef != null) {
        val entry = vaultRepository.getByName(h.vaultCredentialRef)
        val secret = entry?.let { vaultRepository.decryptValue(it) }
        if (secret != null) {
            // OPENSSH 私钥末尾换行标准化（缺换行 Auth fail）——统一在此容错，覆盖所有走 resolveHostAuth 的连接
            return SshAuth(password = null, privateKey = secret.ensureTrailingNewline(), passphrase = h.passphrase)
        }
        return null
    }
    return SshAuth(password = h.password, privateKey = h.privateKey, passphrase = h.passphrase)
        .takeIf { it.isUsable() }
}

/**
 * Save an SSH host so it can be referenced by name in subsequent calls instead of passing
 * credentials every time. Replaces any existing host with the same name.
 */
fun saveSshHostTool(repo: SshHostRepository): Tool = Tool(
    name = "save_ssh_host",
    description = """
        Persist an SSH host (host, port, user, credentials) under a short name so the LLM can
        reference it later via ssh_exec_saved / ssh_upload / ssh_download without re-typing
        credentials. Replaces any existing host with the same name. Pass either password OR
        private_key for authentication.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Short name (used as lookup key)") })
                put("host", buildJsonObject { put("type", "string"); put("description", "Hostname or IP") })
                put("port", buildJsonObject { put("type", "integer"); put("description", "SSH port, default 22") })
                put("user", buildJsonObject { put("type", "string"); put("description", "SSH username") })
                put("password", buildJsonObject { put("type", "string"); put("description", "Password (use only if no private_key)") })
                put("private_key", buildJsonObject { put("type", "string"); put("description", "Full PEM/OpenSSH private key contents") })
                put("passphrase", buildJsonObject { put("type", "string"); put("description", "Optional passphrase for the private key") })
                put("vault_credential", buildJsonObject { put("type", "string"); put("description", "Optional Vault credential name holding the SSH private key (preferred over private_key — the key is never stored in plaintext). See vault_credential_names.") })
            },
            required = listOf("name", "host", "user")
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
        val host = p["host"]?.jsonPrimitive?.contentOrNull ?: error("host is required")
        val user = p["user"]?.jsonPrimitive?.contentOrNull ?: error("user is required")
        val port = p["port"]?.jsonPrimitive?.intOrNull ?: 22
        val password = p["password"]?.jsonPrimitive?.contentOrNull
        val privateKey = p["private_key"]?.jsonPrimitive?.contentOrNull
        val passphrase = p["passphrase"]?.jsonPrimitive?.contentOrNull
        val vaultCredential = p["vault_credential"]?.jsonPrimitive?.contentOrNull
        if (password.isNullOrBlank() && privateKey.isNullOrBlank() && vaultCredential.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "must provide password, private_key or vault_credential") }.toString()
            ))
        }
        repo.upsert(SshHostEntity(
            name = name, host = host, port = port, user = user,
            password = if (vaultCredential.isNullOrBlank()) password else null,
            privateKey = if (vaultCredential.isNullOrBlank()) privateKey else null,
            passphrase = passphrase,
            vaultCredentialRef = vaultCredential,
            createdAtMs = System.currentTimeMillis(),
        ))
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("name", name)
        }.toString()))
    }
)

/** List saved hosts. Secrets omitted — only metadata + which auth method is configured. */
fun listSshHostsTool(repo: SshHostRepository): Tool = Tool(
    name = "list_ssh_hosts",
    description = "List all saved SSH hosts (name, host, port, user, has_password, has_private_key). Secrets are not returned.".trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val all = repo.getAll()
        listOf(UIMessagePart.Text(buildJsonObject {
            put("hosts", buildJsonArray {
                all.forEach { h ->
                    addJsonObject {
                        put("name", h.name)
                        put("host", h.host)
                        put("port", h.port)
                        put("user", h.user)
                        put("has_password", !h.password.isNullOrBlank())
                        put("has_private_key", !h.privateKey.isNullOrBlank())
                    }
                }
            })
        }.toString()))
    }
)

/** Delete a saved host by name. */
fun deleteSshHostTool(repo: SshHostRepository): Tool = Tool(
    name = "delete_ssh_host",
    description = "Delete a saved SSH host by name.".trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
            },
            required = listOf("name")
        )
    },
    execute = { input ->
        val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            ?: error("name is required")
        repo.deleteByName(name)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("name", name)
        }.toString()))
    }
)

/**
 * Forget the stored SSH host key(s) for a host. Use after the user explicitly confirms they
 * reinstalled the remote — the next connect will trust the new key per accept-new policy.
 *
 * The host argument is the actual hostname/IP (not a saved-host name). If the saved host's
 * underlying address changed too, look it up via list_ssh_hosts first.
 */
fun forgetSshHostKeyTool(context: Context): Tool = Tool(
    name = "ssh_forget_host_key",
    description = """
        Remove the stored host key for an SSH host from known_hosts. Call this AFTER the user
        explicitly confirms they reinstalled the remote machine — the next connect will trust
        the new key. NEVER call this without user confirmation: a changed host key can also
        indicate a man-in-the-middle attack.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", buildJsonObject { put("type", "string"); put("description", "Hostname or IP whose stored key should be forgotten") })
            },
            required = listOf("host")
        )
    },
    execute = { input ->
        val host = input.jsonObject["host"]?.jsonPrimitive?.contentOrNull
            ?: error("host is required")
        val removed = forgetHostKey(context, host)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("host", host)
            put("keys_removed", removed)
        }.toString()))
    }
)

/** Run a command on a saved host without re-passing credentials. */
fun sshExecSavedTool(
    context: Context,
    repo: SshHostRepository,
    vaultRepository: CredentialVaultRepository,
): Tool = Tool(
    name = "ssh_exec_saved",
    description = """
        Run a shell command on a previously-saved SSH host (looked up by name). Returns
        stdout, stderr, exit_code. For destructive or system-level commands you should
        explicitly confirm with the user before invoking.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
                put("command", buildJsonObject { put("type", "string"); put("description", "Shell command to run. Mutually exclusive with preset.") })
                put("preset", buildJsonObject { put("type", "string"); put("description", "Preset command key for this host (see ssh_presets). Mutually exclusive with command. Generic platform presets include: on a Windows-class host: 系统信息/磁盘/进程/服务列表; on a POSIX host: 系统信息/磁盘/内存/进程/最近日志. When preset is used, command must be omitted.") })
                put("stdin", buildJsonObject { put("type", "string"); put("description", "Optional data piped to the command's stdin (then EOF). Quote-free way to write a file (command=\"cat > /path\") or feed input; omit to send an immediate EOF.") })
                put("background", buildJsonObject { put("type", "boolean"); put("description", "If true, launch the command fully detached (nohup, streams redirected) and return immediately with its PID instead of waiting. Default false.") })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Total timeout, default 30, max 300") })
            },
            required = listOf("name", "command")
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
        val command = p["command"]?.jsonPrimitive?.contentOrNull
        val presetKey = p["preset"]?.jsonPrimitive?.contentOrNull
        if (command == null && presetKey == null) error("either command or preset is required")
        if (command != null && presetKey != null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "command and preset are mutually exclusive — pass exactly one") }.toString()
            ))
        }
        // preset 展开：按 saved host 名匹配预设集（精确名 → 特征归类 linux/windows 通用模板）
        val finalCommand = if (presetKey != null) {
            val presets = SshPresets.forHost(name)
            val expanded = presets[presetKey]
            if (expanded == null) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "no preset '$presetKey' resolved for host: $name")
                        put("available_presets", presets.keys.sorted().toString())
                        put("hint", "presets are generic per-platform templates (linux/windows). " +
                            "For personal/project-specific operations pass an explicit command instead.")
                    }.toString()
                ))
            }
            expanded
        } else {
            command!!
        }
        val stdin = p["stdin"]?.jsonPrimitive?.contentOrNull
        val background = p["background"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val timeoutSec = (p["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 300)
        val h = repo.getByName(name)
            ?: return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "no saved host: $name") }.toString()
            ))
        val auth = resolveHostAuth(h, vaultRepository)
        if (auth == null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "saved host has no usable credentials (vault ref: ${h.vaultCredentialRef ?: "none"})") }.toString()
            ))
        }
        if (background && stdin != null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "stdin and background are mutually exclusive (a detached command reads from /dev/null)") }.toString()
            ))
        }
        val effectiveCommand = if (background) wrapDetachedCommand(finalCommand) else finalCommand
        val payload = runCancellableSshOp(timeoutSec * 1000L) { sessionRef ->
            execOneShot(context, h.host, h.port, h.user, auth, effectiveCommand, timeoutSec * 1000, sessionRef, stdin)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/** List available command presets for saved hosts. Read-only helper for the LLM. */
fun sshPresetsTool(): Tool = Tool(
    name = "ssh_presets",
    description = "List available command presets for saved SSH hosts. Each preset is a verified " +
        "common operation (build/pull/log/...) that ssh_exec_saved can run by key instead of " +
        "hand-writing a fragile command string. Returns hosts that have presets and their keys. " +
        "Call this before ssh_exec_saved(preset=...) when unsure which keys exist.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", buildJsonObject { put("type", "string"); put("description", "Optional saved host name to filter. Omit to list all hosts with presets.") })
            },
            required = emptyList(),
        )
    },
    execute = { input ->
        val hostFilter = input.jsonObject["host"]?.jsonPrimitive?.contentOrNull
        val out = buildJsonObject {
            put("platform_templates", SshPresets.byHost.keys.sorted().toString())
            if (hostFilter != null) {
                val presets = SshPresets.forHost(hostFilter)
                put("resolved_for_host", hostFilter)
                put("presets", presets.keys.sorted().toString())
            } else {
                SshPresets.byHost.forEach { (platform, presets) ->
                    put(platform, presets.keys.sorted().joinToString(", "))
                }
            }
            put("usage", "ssh_exec_saved(name=<host>, preset=<key>) — preset and command are mutually exclusive. " +
                "Host name is matched to a platform template by generic keyword (pc/win/windows → windows template; else linux template). " +
                "Generic per-platform operations only; project-specific commands pass explicit command.")
        }
        listOf(UIMessagePart.Text(out.toString()))
    }
)
