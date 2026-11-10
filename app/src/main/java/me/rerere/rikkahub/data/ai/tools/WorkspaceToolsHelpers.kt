
package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.vault.CredentialPurpose
import me.rerere.rikkahub.data.vault.CredentialResolution
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.vault.message
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.BackgroundStatus
import me.rerere.workspace.WorkspaceTreeResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.normalizeWorkspacePath
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

/**
 * Workspace tool limits shared by [WorkspaceTools.kt] and this file. Single source of
 * truth: shell timeout cap and the largest file read into memory.
 */
internal const val SHELL_TIMEOUT_MAX_SECONDS = 600L
internal const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

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
            val value = args?.get(key)?.jsonPrimitive?.contentOrNull
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
        "preset '$presetName' 缺少参数: ${missing.joinToString()}（请通过 preset_args 提供）"
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
    val envObj = params["env"]?.jsonObject ?: return emptyMap<String, String>() to null
    if (envObj.isEmpty()) return emptyMap<String, String>() to null
    val vaultRepository =
        runCatching { getKoin().get<me.rerere.rikkahub.data.vault.CredentialVaultRepository>() }.getOrNull()
            ?: return emptyMap<String, String>() to "❌ env 注入失败：凭证库不可用"
    val context = runCatching { getKoin().get<android.content.Context>() }.getOrNull()
    // 单点解析器统一处理：会话授权 / 存在性 / 解密 / 审计（成功与拒绝都记）
    val resolver =
        CredentialResolver(vaultRepository) {
            context?.let { me.rerere.rikkahub.data.vault.VaultSessionManager(it) }
                ?.hasActiveAuthorization() == true
        }
    val resolved = mutableMapOf<String, String>()
    for ((envName, credNameJson) in envObj) {
        val credName = credNameJson.jsonPrimitive.contentOrNull
        if (credName.isNullOrBlank()) continue
        when (val r = resolver.resolveRef(credName, CredentialPurpose.ENV_INJECT, caller = "ai-tool")) {
            is CredentialResolution.Granted -> resolved[envName] = r.value
            else -> return emptyMap<String, String>() to "❌ env 注入失败：${r.message}"
        }
    }
    return resolved to null
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
            put("description", "Optional target workspace name (or id). When set, runs the command in that workspace's rootfs instead of the current one. Prefer the name; use workspace_list to see available workspaces.")
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
    this[name]?.jsonPrimitive?.contentOrNull

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
    val size = absoluteFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportAbsoluteFile(workspaceId, path, it) }
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
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
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

/** 把目录树渲染成缩进文本（带截断提示）。 */
internal fun formatWorkspaceTree(rootPath: String, result: WorkspaceTreeResult): String = buildString {
    appendLine(rootPath)
    result.entries.forEach { entry ->
        repeat(entry.depth) { append("  ") }
        append(if (entry.isDirectory) "[D] " else "[F] ")
        append(entry.name)
        if (!entry.isDirectory) append(" (${entry.sizeBytes}B)")
        appendLine()
    }
    if (result.truncated) appendLine("(truncated: more entries omitted)")
}
