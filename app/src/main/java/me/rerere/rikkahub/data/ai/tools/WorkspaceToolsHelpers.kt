package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import androidx.annotation.StringRes
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
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.vault.CredentialPurpose
import me.rerere.rikkahub.data.vault.CredentialResolution
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.vault.message
import me.rerere.workspace.BackgroundStatus
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceTreeResult
import me.rerere.workspace.normalizeWorkspacePath
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
    }.getOrElse { return JsonObject(emptyMap()) }
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
 * 解析 `env` 参数（{环境变量名: vault凭证名 或 凭证名.字段}）并进程内解密注入。
 *
 * 独立成函数以压低 createShellTool 的圈复杂度；返回（注入映射, 错误消息），
 * 错误消息非空时调用方应直接返回它。
 */
internal suspend fun resolveInjectedEnv(params: JsonObject): Pair<Map<String, String>, String?> {
    val envObj = params["env"] as? JsonObject ?: return emptyMap<String, String>() to null
    if (envObj.isEmpty()) return emptyMap<String, String>() to null
    val vaultRepository =
        runCatching { getKoin().get<me.rerere.rikkahub.data.vault.CredentialVaultRepository>() }.getOrNull()
            ?: return emptyMap<String, String>() to "❌ env injection failed: credential vault unavailable"
    val context = runCatching { getKoin().get<android.content.Context>() }.getOrNull()
    // 单点解析器统一处理：会话授权 / 存在性 / 解密 / 审计（成功与拒绝都记）
    val resolver =
        CredentialResolver(vaultRepository) { name ->
            context?.let { me.rerere.rikkahub.data.vault.VaultSessionManager(it) }
                ?.hasActiveAuthorization(name) == true
        }
    val resolved = mutableMapOf<String, String>()
    for ((envName, credNameJson) in envObj) {
        val credName = (credNameJson as? JsonPrimitive)?.contentOrNull?.trim()
        if (credName.isNullOrEmpty()) {
            return emptyMap<String, String>() to
                "❌ env injection failed: credential name for '$envName' must be a non-empty string"
        }
        when (val r = resolver.resolveRef(credName, CredentialPurpose.ENV_INJECT, caller = "ai-tool")) {
            is CredentialResolution.Granted -> resolved[envName] = r.value
            else -> return emptyMap<String, String>() to "❌ env injection failed: ${r.message}"
        }
    }
    return resolved to null
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
            "Path '$path' uses local Rootfs mount '$hitsLocalRoot', but workspace '${ws.name}' is a remote SSH host whose workspace directory is '$remoteDir'. Use '$remoteDir/...' for files in this remote workspace, or use device tools (read_file / write_text_file) for local phone files."
        }
        val hitsAndroidStorage = ANDROID_STORAGE_ROOTS.firstOrNull { root ->
            (path == root || path.startsWith("$root/")) &&
                remoteDir != root && !remoteDir.startsWith("$root/")
        }
        require(hitsAndroidStorage == null) {
            "Path '$path' is an Android phone storage path, but workspace '${ws.name}' is a remote SSH host ('$remoteDir'). Use device tools (read_file / write_text_file / list_files) to access Android phone storage."
        }
    } else if (!workspaceRepository.sdcardEnabledNow()) {
        val hitsUnmountedSdcard = ANDROID_STORAGE_ROOTS.firstOrNull { root ->
            path == root || path.startsWith("$root/")
        }
        require(hitsUnmountedSdcard == null) {
            "Path '$path' refers to Android shared storage, which is not mounted in local workspace '${ws.name}' (the /sdcard mount switch is off in Workspace settings). Use device tools (read_file / write_text_file / list_files) to access phone storage directly, or enable the phone storage mount in Extensions > Workspace."
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
                "Optional vault credentials to inject into the command's environment as variables, " +
                    "mapping envVarName -> vaultCredentialName. Values are decrypted in-process and " +
                    "injected ONLY into this command's process environment — never written to disk, " +
                    "never shown to the AI. Requires an active vault authorization. " +
                    "A plaintext metadata field can be referenced as `credentialName.<field>` " +
                    "(e.g. {\"AWS_ACCESS_KEY_ID\": \"MY_S3.access_key_id\"}); plaintext fields are not " +
                    "gated by authorization because they are stored unencrypted. " +
                    "Example: {\"GITHUB_TOKEN\": \"GITHUB_TOKEN\"} makes the token available as \$GITHUB_TOKEN inside the command.",
            )
        })
    }


internal fun BackgroundStatus.toJson() = buildJsonObject {
    put("id", id)
    put("command", command)
    put("status", if (running) "running" else "exited")
    if (!running) put("exitCode", exitCode)
    put("startedAt", startedAtMillis)
    put("stdout", stdout)
    put("stderr", stderr)
    if (droppedStdout > 0) put("droppedStdout", droppedStdout)
    if (droppedStderr > 0) put("droppedStderr", droppedStderr)
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
): JsonObject = buildJsonObject {
    put("exitCode", result.exitCode)
    put("stdout", result.stdout)
    put("stderr", result.stderr)
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
