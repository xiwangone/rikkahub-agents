package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.inject.INJECT_CALLER_WORKSPACE
import me.rerere.rikkahub.data.ai.tools.inject.InjectedEnv
import me.rerere.rikkahub.data.ai.tools.inject.InjectedSecret
import me.rerere.rikkahub.data.ai.tools.inject.InjectionResult
import me.rerere.rikkahub.data.ai.tools.inject.SecretInjector
import me.rerere.rikkahub.data.ai.tools.inject.countUnmaskableInjected
import me.rerere.rikkahub.data.ai.tools.inject.maskInjectedValues
import me.rerere.rikkahub.data.ai.tools.inject.unmaskableNote
import me.rerere.rikkahub.data.ai.tools.inject.vaultSecretInjector
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.BackgroundStatus
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceTreeResult
import me.rerere.workspace.normalizeWorkspacePath
import me.rerere.workspace.ssh.REMOTE_BG_DIR_NAME
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Workspace tool limits shared by [WorkspaceTools.kt] and this file. Single source of
 * truth: shell timeout cap and the largest file read into memory.
 */
internal const val SHELL_TIMEOUT_MAX_SECONDS = 600L
internal const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
internal const val MAX_BINARY_BASE64_BYTES = 256 * 1024

/**
 * 凭据类文件内容被扣下时的统一提示：告诉模型别换路重读（shell base64 是同一个洞），
 * 内容确实需要时问用户。三处读类工具（workspace_read_file / read_file 的 file 分支与
 * content:// 分支）共用，避免文案漂移。
 */
internal const val CREDENTIAL_WITHHELD_NOTE =
    "credential file: content withheld; do not re-read via shell, ask the user if needed"

/**
 * Base64 内联上限判定：二进制文件超过 256KB 不直接内联进消息，
 * 改由调用方用 shell（head/xxd/python）查看，避免撑爆上下文。
 */
internal fun requireInlineBase64Size(sizeBytes: Int, path: String) {
    require(sizeBytes <= MAX_BINARY_BASE64_BYTES) {
        "Binary file is too large to inline as base64: $path ($sizeBytes bytes, limit $MAX_BINARY_BASE64_BYTES bytes). Use workspace_shell (e.g. head/xxd/python) to inspect it."
    }
}

/**
 * English text of the workspace tool messages, kept in sync with values/strings.xml.
 * Used only when no [Context] is available (unit tests / headless runs); in the app
 * the string resource is always the source of truth.
 */
private val WORKSPACE_TOOL_MESSAGE_FALLBACKS: Map<Int, String> = mapOf(
    R.string.workspace_tool_preset_not_found to "Shell preset \"%1\$s\" was not found in %2\$s.",
    R.string.workspace_tool_preset_empty to "Shell preset \"%1\$s\" is empty. Add a non-empty command for it in %2\$s.",
    R.string.workspace_tool_preset_file_corrupted to "The shell presets file %1\$s is damaged and could not be read. Fix its JSON content instead of adding a new preset.",
    R.string.workspace_tool_timeout_invalid to "Invalid timeout \"%1\$s\": timeout must be an integer number of seconds between 1 and %2\$s.",
)

/** Resolve a workspace tool message from string resources, with an English fallback. */
internal fun workspaceToolMessage(@StringRes resId: Int, vararg args: Any?): String {
    val context = runCatching { getKoin().get<Context>() }.getOrNull()
    if (context != null) return context.getString(resId, *args)
    val fallback = WORKSPACE_TOOL_MESSAGE_FALLBACKS[resId] ?: return "Tool error"
    return String.format(fallback, *args)
}

/** 超长行截断 + 总输出上限，避免 diff 撑爆消息或上下文。 */
internal fun limitDiffOutput(diff: String, maxChars: Int = MAX_DIFF_TOTAL_CHARS): String {
    val sb = StringBuilder()
    var total = 0
    for (line in diff.lineSequence()) {
        val limited =
            if (line.length > MAX_DIFF_LINE_CHARS) line.take(MAX_DIFF_LINE_CHARS) + "…<line truncated>"
            else line
        if (sb.isNotEmpty() && total + limited.length + 1 > maxChars) {
            sb.append('\n').append("…<diff truncated: exceeds $maxChars chars>")
            break
        }
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(limited)
        total += limited.length + 1
    }
    return sb.toString()
}

internal const val SHELL_PRESETS_RELATIVE_PATH = ".agents/shell-presets.json"

/** 预设库在环境内的绝对路径: 本地是 /workspace/…, 远程是工作区目录下的同一路径 */
internal fun shellPresetsPath(layout: WorkspaceLayout): String =
    layout.absolutePath(WorkspaceStorageArea.FILES, SHELL_PRESETS_RELATIVE_PATH)

/** 匹配 `${name}` 与 `${name:-default}` 两种占位符；默认值部分不含花括号、冒号紧随变量名。 */
private val SHELL_PRESET_PLACEHOLDER = Regex("""\$\{([A-Za-z0-9_.\-]+)(:-[^{}]*)?\}""")

/** Presets file exists but its content is not a valid JSON object. */
internal class ShellPresetsCorruptedException(cause: Throwable?) :
    IllegalStateException("Shell presets file is damaged", cause)

/**
 * 读取预设库；文件缺失时返回空表，由调用方给出友好提示。
 * 文件存在但内容损坏（不是合法 JSON 对象）时抛 [ShellPresetsCorruptedException]，
 * 调用方据此报“预设文件损坏”，不与“不存在”混在一起。
 */
internal suspend fun loadShellPresets(
    workspaceRepository: WorkspaceRepository,
    workspaceId: String,
    presetsPath: String,
): JsonObject {
    val text = runCatching {
        workspaceRepository.readAbsoluteText(workspaceId, presetsPath)
    }.getOrElse {
        if (it is CancellationException) throw it
        return JsonObject(emptyMap())
    }
    return runCatching { Json.parseToJsonElement(text).jsonObject }
        .getOrElse { throw ShellPresetsCorruptedException(it) }
}

/**
 * 用 `preset_args` 渲染预设命令占位符；支持两种形态：
 * - `${name}`：必须由 `preset_args` 提供，缺则直接失败并列出缺哪些
 * - `${name:-default}`：`preset_args` 有值则用值覆盖；无值则保留原样交给 shell 用默认值
 */
internal fun renderShellPreset(
    presetName: String,
    template: String,
    args: JsonObject?,
): String {
    val missing = linkedSetOf<String>()
    val rendered =
        SHELL_PRESET_PLACEHOLDER.replace(template) { m ->
            val key = m.groupValues[1]
            val hasDefault = !m.groupValues[2].isNullOrEmpty()
            val value = (args?.get(key) as? JsonPrimitive)?.contentOrNull
            if (value != null) {
                value
            } else if (hasDefault) {
                m.value // 保留 `${name:-default}` 交给 shell 用默认值
            } else {
                missing.add(key)
                m.value
            }
        }
    require(missing.isEmpty()) {
        "preset '$presetName' is missing parameters: ${missing.joinToString()} (please provide them via preset_args)"
    }
    return rendered
}

/**
 * 解析 `env` 参数并进程内解密注入 —— 统一走 [SecretInjector]（与 ssh 工具同一条路径）。
 *
 * - 以 `$$` 开头 → 按名从凭证库解析（`$$NAME` 取主值，`$$NAME.field` 取明文元数据字段）；
 * - 其余 → 按字面值注入；但字面值恰好是已存在凭证名 / 名字.字段时拒绝（旧写法护栏）。
 *
 * 纯字面量时不碰凭证库（也不需要授权）；解析失败一律返回错误、不静默回退字面值。
 * 返回（注入映射, 错误消息），错误消息非空时调用方应直接返回它。
 */
internal suspend fun resolveInjectedEnv(params: JsonObject): Pair<Map<String, String>, String?> {
    val r = resolveInjectedEnvDetailed(params)
    return r.env to r.error
}

/** [resolveInjectedEnv] 的完整结果：带上注入值，供输出脱敏。 */
internal class ResolvedInjectedEnv(val injected: InjectedEnv, val error: String?) {
    val env: Map<String, String> get() = injected.env
    val secrets: List<InjectedSecret> get() = injected.secrets
    override fun toString(): String = "ResolvedInjectedEnv($injected, error=$error)"
}

internal suspend fun resolveInjectedEnvDetailed(
    params: JsonObject,
    injector: SecretInjector = vaultSecretInjector(INJECT_CALLER_WORKSPACE),
): ResolvedInjectedEnv =
    when (val r = injector.prepare(params["env"])) {
        is InjectionResult.Ok -> ResolvedInjectedEnv(r.injected, null)
        is InjectionResult.Failed -> ResolvedInjectedEnv(InjectedEnv.EMPTY, "❌ " + r.message)
    }

/** 远端后台任务的脱敏名单变量名（`rk bg` 读它）。 */
internal const val REMOTE_SECRET_KEYS_VAR = "RIKKA_SECRET_KEYS"

/**
 * 把本次注入用的**环境变量名**拼进 env，供远端 `rk bg` 在写日志前脱敏（只有名字，不含值）。
 *
 * 背景：远程区的后台任务是 `setsid` 起的纯 shell 进程，App 根本不知道它跑过；
 * 日志一旦落盘，下一次「不带注入」的调用读出来就是明文。把名单交给 `rk bg`
 * 的过滤器后，值只在那个进程内存里替换，日志文件本身就是干净的。
 */
internal fun envWithSecretKeyNames(
    env: Map<String, String>,
    secrets: List<InjectedSecret>,
): Map<String, String> {
    if (secrets.isEmpty()) return env
    val names = secrets.map { it.envName }.filter { it.isNotBlank() }.distinct()
    if (names.isEmpty()) return env
    return env + (REMOTE_SECRET_KEYS_VAR to names.joinToString(","))
}

/**
 * 构造 `workspace_shell` 的工具描述：远程工作区不支持 `workspace_run_background` 等后台工具，
 * 在此显式提供 POSIX 标准命令（`setsid` / `nohup` / `pgrep` / `tail` / `pkill`）的后台脱离范式。
 */
internal fun buildShellToolDescription(
    layout: WorkspaceLayout,
    defaultCwd: String?,
    supportsBackground: Boolean = true,
): String = buildString {
    append("Run a shell command in the assistant's bound workspace. The workspace files area is at ${layout.workspaceDir}. ")
    append("Use cwd for a path relative to the workspace files root. ")
    if (!defaultCwd.isNullOrBlank()) {
        append("Defaults to '$defaultCwd'. ")
    }
    append("Requires the workspace environment to be installed and ready.")
    if (!supportsBackground) {
        append(
            " This workspace runs commands on a remote host and has NO background-task tools. " +
                "A long or never-ending command blocks until it finishes or the call times out, " +
                "and its output is lost on timeout. To run one detached, do the whole sequence " +
                "in a SINGLE command: cd <dir> && setsid nohup <cmd> >/tmp/task.log 2>&1 </dev/null & " +
                "sleep 2; echo \"pid=\$!\"; tail -n 5 /tmp/task.log — setsid detaches it from the " +
                "session so it survives the SSH channel closing, and </dev/null stops it waiting on " +
                "stdin. Later check with pgrep -f + tail, stop with pkill -f. Do NOT append a bare & " +
                "without redirecting output: the log is lost and failures become invisible.",
        )
    }
}

/** `workspace_shell` 命令超时后的恢复指引（区分本地后台工具与远程 POSIX 脱离运行）。 */
internal fun shellTimeoutHint(timeoutSeconds: Long, supportsBackground: Boolean): String =
    if (supportsBackground) {
        "Command timed out after ${timeoutSeconds}s. Increase 'timeout' (up to ${SHELL_TIMEOUT_MAX_SECONDS}s), or use workspace_run_background for long-running tasks and servers."
    } else {
        "Command timed out after ${timeoutSeconds}s. This remote workspace has no background-task tools; increase 'timeout' (up to ${SHELL_TIMEOUT_MAX_SECONDS}s), or run detached in a single command: cd <dir> && setsid nohup <cmd> >/tmp/task.log 2>&1 </dev/null & sleep 2; echo \"pid=\$!\"; tail -n 5 /tmp/task.log"
    }

/** 跨工作区调用时解析目标工作区的真实布局；同工作区直接复用已绑定布局。 */
internal suspend fun resolveEffectiveLayout(
    workspaceRepository: WorkspaceRepository,
    boundWorkspaceId: String,
    targetWorkspaceId: String,
    boundLayout: WorkspaceLayout,
): WorkspaceLayout =
    if (targetWorkspaceId == boundWorkspaceId) {
        boundLayout
    } else {
        workspaceRepository.layout(targetWorkspaceId) ?: boundLayout
    }

private val LOCAL_ONLY_ROOTS = listOf("/workspace", "/upload", "/skills")
private val ANDROID_STORAGE_ROOTS = listOf("/sdcard", "/storage/emulated")

/**
 * 校验目标工作区与传入路径的环境边界，防止静默跨边界误用：
 * - 远程 SSH 工作区：拒绝误传设备专属挂载点（`/workspace`、`/upload`、`/skills`）或手机存储（`/sdcard`），
 *   除非远程工作区目录本身就在该前缀下；
 * - 本地 Rootfs 工作区：当手机存储挂载（`/sdcard`）未开启时，拒绝把 `/sdcard` 当普通容器目录读写，
 *   避免文件静默落入容器内部不可见的假 `/sdcard` 目录，并指引改用设备文件工具或开启挂载开关。
 */
internal suspend fun validateWorkspaceTargetPath(
    workspaceRepository: WorkspaceRepository,
    workspaceId: String,
    path: String,
) {
    val ws = workspaceRepository.getById(workspaceId) ?: return
    if (ws.isSsh()) {
        val remoteDir = ws.workspaceDir() ?: "/workspace"
        val hitsLocalRoot = LOCAL_ONLY_ROOTS.firstOrNull { root ->
            (path == root || path.startsWith("$root/")) &&
                remoteDir != root && !remoteDir.startsWith("$root/")
        }
        require(hitsLocalRoot == null) {
            "Path '$path' uses local Rootfs mount '$hitsLocalRoot', but workspace '${ws.name}' is a remote SSH host whose workspace directory is '$remoteDir'. Use '$remoteDir/...' for files in this remote workspace, or use device tools (read_file / write_text_file) for local phone files. " +
                "If '$hitsLocalRoot' really exists on that remote host (e.g. a build machine keeps its repo there), set this workspace's remote directory to it instead — the check only guards against mixing up environments, not against genuine remote paths."
        }
        val hitsAndroidStorage = ANDROID_STORAGE_ROOTS.firstOrNull { root ->
            (path == root || path.startsWith("$root/")) &&
                remoteDir != root && !remoteDir.startsWith("$root/")
        }
        require(hitsAndroidStorage == null) {
            "Path '$path' is an Android phone storage path, but workspace '${ws.name}' is a remote SSH host ('$remoteDir'). Use device tools (read_file / write_text_file / list_files) to access Android phone storage."
        }
    } else if (!workspaceRepository.sdcardEnabledNow()) {
        // 工作区显式配置的 storage_mounts 是比全局开关更强的意图表达，且自带 approvalRoots
        // 审批闸门（proot bind 已挂上，shell 本来就进得去）——豁免全局开关，
        // 否则文件工具拒、shell 放行，两条入口口径分裂。
        val explicitlyMounted = ws.storageMountList().map { it.path.trimEnd('/') }
            .firstOrNull { m -> m.isNotEmpty() && (path == m || path.startsWith("$m/")) }
        if (explicitlyMounted == null) {
            val hitsUnmountedSdcard = ANDROID_STORAGE_ROOTS.firstOrNull { root ->
                path == root || path.startsWith("$root/")
            }
            require(hitsUnmountedSdcard == null) {
                "Path '$path' refers to Android shared storage, which is not reachable by file tools in local workspace '${ws.name}' (no storage mount covers it and the /sdcard mount switch is off in Workspace settings). Use device tools (read_file / write_text_file / list_files) to access phone storage directly, add a storage mount on this workspace, or enable the phone storage mount in Extensions > Workspace."
            }
        }
    }
}

/**
 * workspace_shell 的参数 schema。
 *
 * 独立成函数而非内联：detekt 的 CyclomaticComplexMethod 会把嵌套 lambda 内的分支
 * 一并计入 createShellTool，导致其圈复杂度超阈值（曾达 26）。
 */
internal fun shellToolSchema(defaultCwd: String?, presetsPath: String): JsonObject =
    buildJsonObject {
        put("preset", buildJsonObject {
            put("type", "string")
            put(
                "description",
                "Name of a saved command preset. Presets live in the workspace file " +
                    "$presetsPath ({\"<name>\": \"<command>\"} or {\"<name>\": {\"command\": ..., \"description\": ...}}). " +
                    "Keep real hostnames/paths only in that local file — never in repository-tracked files. " +
                    "When set, `command` is ignored.",
            )
        })
        put("preset_args", buildJsonObject {
            put("type", "object")
            put(
                "description",
                "Values for \${name} placeholders inside the preset command, e.g. {\"jdk\": \"...\", \"abi\": \"arm64-v8a\"}.",
            )
        })
        put("command", buildJsonObject {
            put("type", "string")
            put("description", "Shell command to run (omit when using preset)")
        })
        put("cwd", buildJsonObject {
            put("type", "string")
            put(
                "description",
                if (!defaultCwd.isNullOrBlank()) {
                    "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                } else {
                    "Working directory relative to the workspace files root. Defaults to root."
                },
            )
        })
        put("timeout", buildJsonObject {
            put("type", "integer")
            put(
                "description",
                "Command timeout in seconds. Defaults to 30. Must be an integer between 1 and $SHELL_TIMEOUT_MAX_SECONDS; larger values are clamped to $SHELL_TIMEOUT_MAX_SECONDS and the result notes the effective value, other values are rejected.",
            )
        })
        put("workspace", buildJsonObject {
            put("type", "string")
            put("description", "Optional target workspace name (or id). When set, runs the command in that workspace's environment instead of the current one. Prefer the name; use workspace_list to see available workspaces.")
        })
        put("env", buildJsonObject {
            put("type", "object")
            put(
                "description",
                "Optional environment variables for this command, mapping envVarName -> value. " +
                    "A value starting with `\$\$` is resolved from the credential vault (`\$\$NAME` = that credential's value, " +
                    "`\$\$NAME.field` = one plaintext metadata field, e.g. `\$\$MY_S3.access_key_id`); " +
                    "every other value is injected literally as-is. " +
                    "Vault values are decrypted in-process and injected ONLY into this command's process environment — " +
                    "never written to disk, never shown to the AI; `\$\$` references require an active vault authorization. " +
                    "Variable names must match [A-Za-z_][A-Za-z0-9_]*. " +
                    "Example: {\"GITHUB_TOKEN\": \"\$\$GITHUB_TOKEN\", \"NODE_ENV\": \"production\"}.",
            )
        })
    }


internal fun BackgroundStatus.toJson(
    injectedSecrets: List<InjectedSecret> = emptyList(),
    legacyMaskRules: Map<String, String> = emptyMap(),
) = buildJsonObject {
    put("id", id)
    put("command", command)
    put("status", if (running) "running" else "exited")
    if (!running) put("exitCode", exitCode)
    put("startedAt", startedAtMillis)
    // 启动时经 `$$` 注入的值：状态/日志尾部返回前替换为占位符
    // legacyMaskRules = 被登记表淘汰的任务留下的永久掩码，保证淘汰不致明文回传
    put("stdout", maskInjectedValues(stdout, injectedSecrets, legacyMaskRules))
    put("stderr", maskInjectedValues(stderr, injectedSecrets, legacyMaskRules))
    if (droppedStdout > 0) {
        put("droppedStdout", droppedStdout)
        put("stdout_truncated", true)
    }
    if (droppedStderr > 0) put("droppedStderr", droppedStderr)
    // 日志全文路径：status 只给尾部，要全文就读这个文件（workspace_read_file / search_code）。
    logPath?.let { put("log_path", it) }
}


internal fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

internal fun kotlinx.serialization.json.JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

internal fun kotlinx.serialization.json.JsonObject.boolean(name: String): Boolean? =
    when ((this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

internal suspend fun WorkspaceRepository.readAbsoluteText(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按环境内绝对路径读入内存。路径映射交给工作区后端: 本地由它处理 /workspace、bind mount
 * 与 Rootfs 内部路径, 远程走 SFTP。
 */
internal suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    validateWorkspaceTargetPath(this, workspaceId, path)
    val size = absoluteFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportAbsoluteFile(workspaceId, path, it) }
}

/** 内容嗅探前 8KB 的窗口：NUL 字节只在这段里找，UTF-8 严格解码则覆盖全文。 */
private const val BINARY_SNIFF_BYTES = 8 * 1024

/**
 * 内容嗅探判二进制（不靠扩展名）：前 8KB 出现 NUL 字节，或全文严格 UTF-8 解码失败。
 * UTF-8 文本里不会出现 NUL；UTF-16 等编码因含 NUL 同样判二进制，由调用方走 base64 通道。
 */
internal fun ByteArray.isBinaryContent(): Boolean {
    val sniffLen = minOf(size, BINARY_SNIFF_BYTES)
    for (i in 0 until sniffLen) {
        if (this[i] == 0.toByte()) return true
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
        false
    } catch (_: CharacterCodingException) {
        true
    }
}

internal suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

internal fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(!path.startsWith("content://") && !path.startsWith("file://")) {
        "$name must be an absolute path inside Rootfs (got URI '$path'); use device tool read_file / write_text_file for content:// or file:// URIs"
    }
    require(path.startsWith("/")) {
        "$name must be an absolute path inside Rootfs (e.g. /workspace/${path.removePrefix("./").removePrefix("~/")})"
    }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    // 折叠 .. 与重复斜杠, 防止 "/rootfs/../etc" 绕过 isWritable 的前缀检查
    return normalizeWorkspacePath(path)
}

// 免强制审批的可写安全区由工作区布局给出: 本地是文件区 + /tmp + /skills + /mnt/shared, 远程是工作区目录 + /tmp
internal fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(
    layout: WorkspaceLayout,
    name: String,
): Boolean =
    runCatching {
        !layout.isWritable(jsonObject.absolutePath(name))
    }.getOrDefault(true)

internal fun JsonObjectBuilder.putPathProperty(layout: WorkspaceLayout, required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside the workspace environment. Use ${layout.workspaceDir} for the workspace files area."
            } else {
                "Optional absolute path inside the workspace environment. Use ${layout.workspaceDir} for the workspace files area."
            }
        )
    })
}


internal fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}

/** 把目录树渲染成缩进文本（带截断提示）。软链标 [L] 并带目标与目标大小，不再和空文件混淆。 */
internal fun formatWorkspaceTree(rootPath: String, result: WorkspaceTreeResult): String = buildString {
    appendLine(rootPath)
    result.entries.forEach { entry ->
        repeat(entry.depth) { append("  ") }
        when {
            entry.isSymlink -> {
                append("[L] ")
                append(entry.name)
                append(" -> ")
                append(entry.linkTarget ?: "?")
                // 目录链接不印大小（目录大小无意义）；文件链接印目标大小，断链目标大小为 0
                if (entry.isDirectory) append("/") else append(" (${entry.sizeBytes}B)")
            }
            entry.isDirectory -> {
                append("[D] ")
                append(entry.name)
            }
            else -> {
                append("[F] ")
                append(entry.name)
                append(" (${entry.sizeBytes}B)")
            }
        }
        appendLine()
    }
    if (result.truncated) appendLine("(truncated: more entries omitted)")
}

private fun renderSlicedBody(slice: List<String>, from: Int, withLineNumbers: Boolean): String =
    if (withLineNumbers) {
        slice.mapIndexed { i, line -> "${from + i}| $line" }.joinToString("\n")
    } else {
        slice.joinToString("\n")
    }

/** 格式化 `workspace_read_file` 的文本切片结果（抽离以保持 createReadFileTool 复杂度低于门限）。 */
/**
 * 敏感凭证文件的判定（**只按路径**）。
 *
 * 为什么按路径而不是「读类输出统一过掩码」：后者会改变工具返回语义 —— AI 拿到掩码后无法
 * 引用原文，「读配置 → 改配置」的常规工作流会断（改完写回把掩码写成真值）。所以只覆盖
 * 「几乎必然是机密」的一小撮路径，边界清晰、代价可控；其余文件原样返回。
 *
 * 背景：编辑/差异路径都过了 SecretMasker，只有 read 没过 —— 远程工作区还能用绝对路径读
 * 整台机器的文件，`.env`、`~/.ssh/id_ed25519` 原文会直接进模型上下文。
 */
private val SENSITIVE_FILE_NAMES = listOf(
    ".env", ".git-credentials", ".netrc", ".pgpass", ".npmrc", ".pypirc",
    "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa",
)
private val SENSITIVE_FILE_SUFFIXES = listOf(".pem", ".key", ".p12", ".pfx", ".jks", ".keystore")

/**
 * 后台任务日志路径判定：远程后台任务的日志固定写在
 * `$workspaceDir/.rikkahub-bg/<taskId>/log`（目录名见 workspace 模块的
 * [REMOTE_BG_DIR_NAME]）。这类日志是命令 stdout 的回显，可能含 `$$` 注入值明文，
 * read_file / search_code 命中时须按本 workspace 的注入值掩一遍——
 * 否则绕过 workspace_background_status 的掩码就能把注入值带进模型上下文。
 * 宽松包含匹配即可：误命中只会多做一次掩码替换，方向安全。
 */
internal fun isBackgroundTaskPath(path: String): Boolean = path.contains(REMOTE_BG_DIR_NAME)

internal fun isSensitiveCredentialPath(path: String): Boolean {
    val normalized = path.replace('\\', '/')
    val name = normalized.substringAfterLast('/')
    // 公钥先判：`id_ed25519.pub` 会被下面的 `id_ed25519` 前缀规则误命中。
    if (name.endsWith(".pub")) return false
    // 远程 env 临时文件（`.rikkahub-env-<nanoTime>`，0600 存 `$$` 解析出的凭证明文）：
    // 文件名前缀可预测、目录树列表不过滤 dotfile，不拦的话并发会话/后续轮次可把它当
    // 普通文件读出注入值明文，绕过「注入值只进进程环境、不给模型」的设计。
    if (name.startsWith(".rikkahub-env-")) return true
    if (SENSITIVE_FILE_NAMES.any { name == it || name.startsWith("$it.") }) return true
    if (SENSITIVE_FILE_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }) return true
    // `.ssh/` 下区分对待：**公钥与主机指纹本就是公开贴的**（authorized_keys / known_hosts / *.pub），
    // 掩了反而不便（用户想看一眼自己贴的公钥）；其余（私钥、ssh config）一律掩。
    if (normalized.contains("/.ssh/")) {
        val publicByDesign = name == "authorized_keys" || name == "known_hosts" || name.endsWith(".pub")
        return !publicByDesign
    }
    if (normalized.endsWith("/.kube/config") || normalized.endsWith("/.docker/config.json")) return true
    return false
}

/**
 * 把凭证类文件里的**值**掩掉，保留键名/注释/行结构，让 AI 仍能理解文件在配什么。
 * 私钥块（BEGIN..END）整段丢弃正文。
 */
internal fun redactCredentialFileText(text: String): String {
    val out = StringBuilder(text.length)
    var inPemBlock = false
    text.lineSequence().forEach { line ->
        val trimmed = line.trim()
        when {
            trimmed.startsWith("-----BEGIN") -> {
                inPemBlock = true
                out.append(trimmed.replace(Regex("""-----BEGIN (.+?)-----"""), "-----BEGIN $1-----"))
                out.append(" [body redacted]")
            }
            inPemBlock && trimmed.startsWith("-----END") -> {
                inPemBlock = false
                out.append('\n').append(trimmed)
            }
            inPemBlock -> Unit
            else -> out.append(redactValueInLine(line))
        }
        out.append('\n')
    }
    return out.toString().trimEnd('\n')
}

/** 长 base64/hex 形态的不透明串（无 `=`/`:` 分隔的行也要掩，如裸密钥串）。 */
private val LONG_OPAQUE_TOKEN = Regex("""[A-Za-z0-9+/=_\-]{20,}""")

/**
 * 键名里的敏感词提示：命中即视为敏感键（值无条件掩）。
 * 覆盖 JSON 引号键（`"password"`）、`.npmrc` 路径形键（`//host/:_authToken`）等
 * 基本键名正则放不下的形态；`auth` 只与 token 连用，避免误伤 AUTHOR 之类的普通键。
 */
private val SENSITIVE_KEY_HINT = Regex(
    """(?i)(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential|auth[_-]?token|authorization)""",
)

/** 值形如 `scheme://…` 的纯 URL（无内嵌凭证时不掩，避免文档里的链接全变 ***）。 */
private val PLAIN_URL = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://\S*""")

/** URL 内嵌凭证形态：`scheme://user:pass@…`（这类该掩）。 */
private val URL_WITH_CREDENTIALS = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://[^\s/@]+:[^\s/@]+@""")

/** `machine x login u password p` 这类同行多键值行（.netrc / .pgpass 风格）：掩敏感关键词后的值。 */
private val KEYWORD_VALUE = Regex("""(?i)\b(password|passwd|pwd|token|secret|api[_-]?key)\b([\s=:]+)(\S+)""")

/** `KEY = value` / `KEY: value`（含 `export KEY=value`）→ 保留键、掩值；没分隔符则掩长 token。 */
private fun redactValueInLine(line: String): String {
    val byKeyValue = redactKeyValue(line)
    if (byKeyValue != null) return byKeyValue
    // .netrc 风格的同行多键值行：没有单一分隔符可依，按敏感关键词掩其后的值。
    // 只在敏感文件里跑，短密码（如 `password hunter2`）也该掩，不设长度门槛。
    val byKeyword = KEYWORD_VALUE.replace(line) { m ->
        "${m.groupValues[1]}${m.groupValues[2]}***"
    }
    if (byKeyword != line) return byKeyword
    // 兜底：没有分隔符的行（ssh 公钥的 base64 段、裸密钥串）按长 token 掩。
    // 注意：只处理 KEY=VALUE 时，`.ssh/` 下的行会被标 redacted 却一字未掩。
    return LONG_OPAQUE_TOKEN.replace(line) { if (it.value.length >= 20) "***" else it.value }
}

/**
 * 命中 `KEY=VALUE` / `KEY: VALUE` 形态则返回掩码结果；否则返回 null 交给关键词/token 兜底。
 *
 * 掩码口径：
 * - 键名放宽：JSON 引号键（`"password": …`）与 `.npmrc` 路径形键（`//host/:_authToken=…`）也认；
 * - 键名带敏感词（PASSWORD/TOKEN/…）时值无条件掩；
 * - 其余键的值是**无内嵌凭证的纯 URL** 时不掩（`REPO_URL=https://…` 保持原样），
 *   URL 里带 `user:pass@` 的照掩（`DATABASE_URL=postgres://u:p@h`）——
 *   旧实现只放过以 `//` 开头的值，注释里说的「URL 别掩」实际从未生效。
 */
private fun redactKeyValue(line: String): String? {
    // 剥掉 shell 前缀（export / set 之类），键名仍按裸键判
    val m = Regex("""^(\s*(?:export\s+)?)(.*)$""").find(line) ?: return null
    val prefix = m.groupValues[1]
    val body = m.groupValues[2]
    // 先试 `=`（KEY=VALUE），再试 `:`（KEY: VALUE / JSON）：`.npmrc` 的 `//host/:_authToken=x`
    // 若按最近的 `:` 切会把键切坏，按 `=` 切键名完整。
    for (sep in charArrayOf('=', ':')) {
        val idx = body.indexOf(sep)
        if (idx <= 0) continue
        val rawKey = body.substring(0, idx).trim()
        val key = rawKey.trim('"', '\'')
        val keyIsSensitive = key.matches(Regex("""[A-Za-z_][A-Za-z0-9_.\-]*""")) ||
            SENSITIVE_KEY_HINT.containsMatchIn(rawKey)
        if (!keyIsSensitive) continue
        val rawValue = body.substring(idx + 1)
        if (rawValue.isBlank()) continue
        val value = rawValue.trim()
        val keyHinted = SENSITIVE_KEY_HINT.containsMatchIn(rawKey)
        if (!keyHinted && PLAIN_URL.matches(value) && !URL_WITH_CREDENTIALS.containsMatchIn(value)) continue
        // 值以 `//` 开头：多半是 `https://…` 被按 `:` 切坏了键（如裸 URL 行、`//host/:_authToken` 之外的
        // 协议相对写法），恢复旧豁免语义，不掩。
        if (!keyHinted && value.startsWith("//")) continue
        // 分隔符归一化为 `=`（旧实现口径，钉在 `values are masked but keys survive`）
        return "$prefix$rawKey=***"
    }
    return null
}

internal fun formatReadFileTextResult(
    path: String,
    text: String,
    startLine: Int?,
    endLine: Int?,
    withLineNumbers: Boolean,
): JsonObject {
    val defaultMaxLines = 500
    val lines = text.split("\n")
    val total = lines.size
    val hasWindow = startLine != null || endLine != null || withLineNumbers
    if (!hasWindow && total <= defaultMaxLines) {
        return buildJsonObject {
            put("path", path)
            put("text", text)
        }
    }
    val maxLine = maxOf(1, total)
    val from = (startLine ?: 1).coerceIn(1, maxLine)
    val defaultTo = if (startLine == null && total > defaultMaxLines) defaultMaxLines else total
    val actualEnd = (endLine ?: defaultTo).coerceIn(from, maxLine)
    val body = renderSlicedBody(lines.subList(from - 1, actualEnd), from, withLineNumbers)
    return buildReadSliceJson(path, body, from, actualEnd, total, startLine, endLine)
}

private fun buildReadSliceJson(
    path: String,
    body: String,
    from: Int,
    actualEnd: Int,
    total: Int,
    startLine: Int?,
    endLine: Int?,
): JsonObject = buildJsonObject {
    put("path", path)
    put("text", body)
    put("start_line", from)
    put("end_line", actualEnd)
    put("total_lines", total)
    put("truncated", from > 1 || actualEnd < total)
    if ((startLine ?: from) != from || (endLine ?: actualEnd) != actualEnd) {
        put("range_clamped", true)
        startLine?.let { put("requested_start_line", it) }
        endLine?.let { put("requested_end_line", it) }
    }
    if (startLine == null && endLine == null && actualEnd < total) {
        put("hint", "File has $total lines. Showing first $actualEnd lines to prevent context blowout. Pass start_line and end_line to inspect specific sections.")
    }
}

internal fun buildShellOutputJson(
    result: me.rerere.workspace.WorkspaceCommandResult,
    effectiveTimeoutSeconds: Long,
    requestedTimeoutSeconds: Long?,
    supportsBackground: Boolean,
    ignoredCommand: Boolean,
    diffSkipped: Boolean,
    changes: List<FileChange>,
    injectedSecrets: List<InjectedSecret> = emptyList(),
): JsonObject = buildJsonObject {
    put("exitCode", result.exitCode)
    // 由 `$$` 解析出的注入值在返回前替换为占位符（字面值不动）；全局 SecretMasker 之后还会再过一遍
    put("stdout", maskInjectedValues(result.stdout, injectedSecrets))
    put("stderr", maskInjectedValues(result.stderr, injectedSecrets))
    put("timedOut", result.timedOut)
    if (result.timedOut) {
        put("hint", shellTimeoutHint(effectiveTimeoutSeconds, supportsBackground))
    }
    if (ignoredCommand) put("ignoredCommand", true)
    if (requestedTimeoutSeconds != null && requestedTimeoutSeconds != effectiveTimeoutSeconds) {
        put("timeoutClamped", true)
        put("requestedTimeoutSeconds", requestedTimeoutSeconds)
        put("timeoutSeconds", effectiveTimeoutSeconds)
    }
    if (result.truncated) put("truncated", true)
    if (diffSkipped) put("diffSkipped", true)
    if (changes.isNotEmpty()) putTouchedFilesSummary(changes)
    val unmaskable = countUnmaskableInjected(injectedSecrets)
    if (unmaskable > 0) {
        put("maskNote", unmaskableNote(unmaskable))
    }
}

/** 解析 `workspace_shell` 的命令或预设模板（返回 `命令 to 错误JSON`）。 */
internal suspend fun resolveShellCommandOrError(
    workspaceRepository: WorkspaceRepository,
    targetWorkspaceId: String,
    presetsPath: String,
    params: JsonObject,
): Pair<String?, String?> {
    val presetName = params.string("preset")?.takeIf { it.isNotBlank() }
        ?: return params.string("command") to null
    val presets = try {
        loadShellPresets(workspaceRepository, targetWorkspaceId, presetsPath)
    } catch (_: ShellPresetsCorruptedException) {
        return null to ToolErrors.envelopeFor(
            error = "preset_file_corrupted",
            message = workspaceToolMessage(R.string.workspace_tool_preset_file_corrupted, presetsPath),
            extra = ToolErrors.extraOf("preset" to presetName, "presetsPath" to presetsPath),
        ).toString()
    }
    val node = presets[presetName]
        ?: return null to ToolErrors.envelopeFor(
            error = "preset_not_found",
            message = workspaceToolMessage(R.string.workspace_tool_preset_not_found, presetName, presetsPath),
            hint = "Add it to $presetsPath as {\"$presetName\": \"<command>\"}.",
            extra = ToolErrors.extraOf(
                "preset" to presetName,
                "presetsPath" to presetsPath,
                "available" to kotlinx.serialization.json.buildJsonArray {
                    presets.keys.forEach { add(JsonPrimitive(it)) }
                },
            ),
        ).toString()
    val template = when (node) {
        is JsonPrimitive -> node.contentOrNull.orEmpty()
        is JsonObject -> node.string("command").orEmpty()
        else -> ""
    }
    if (template.isBlank()) {
        return null to ToolErrors.envelopeFor(
            error = "preset_empty",
            message = workspaceToolMessage(R.string.workspace_tool_preset_empty, presetName, presetsPath),
            extra = ToolErrors.extraOf("preset" to presetName, "presetsPath" to presetsPath),
        ).toString()
    }
    return renderShellPreset(presetName, template, params["preset_args"] as? JsonObject) to null
}
