package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.vault.CredentialPurpose
import me.rerere.rikkahub.data.vault.CredentialResolution
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.BackgroundStatus
import me.rerere.workspace.WorkspaceTreeResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_apply_edits" to false,
    "workspace_shell" to true,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createListTool(workspaceRepository),
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createApplyEditsTool(workspaceId, ::needsApproval, workspaceRepository),
        createDiffFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
        createCreateFolderTool(workspaceId, ::needsApproval, workspaceRepository),
        createReadFolderTool(workspaceId, ::needsApproval, workspaceRepository),
        createRunBackgroundTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
        createBackgroundStatusTool(workspaceId, ::needsApproval, workspaceRepository),
        createBackgroundKillTool(workspaceId, ::needsApproval, workspaceRepository),
        createSearchCodeTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
    ) + me.rerere.agenttools.createAgentTools(
        AppAgentWorkspaceIO(workspaceId, workspaceRepository),
        approvalOverrides,
    )
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
        Large text files can be read in slices: pass start_line / end_line (1-based, inclusive).
        Set with_line_numbers=true to prefix each returned line with its line number.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                putWorkspaceProperty()
                put("start_line", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional 1-based first line to return (inclusive). Omit to start at line 1.")
                })
                put("end_line", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional 1-based last line to return (inclusive). Omit to read to the end.")
                })
                put("with_line_numbers", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Prefix each returned line with its line number (e.g. '12| ...'). Defaults to false.")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val args = it.jsonObject
        val path = args.absolutePath("path")
        val targetWorkspaceId = resolveTargetWorkspaceId(workspaceRepository, args, workspaceId)
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(targetWorkspaceId, path)
        } else {
            val text = workspaceRepository.readTextInRootfs(targetWorkspaceId, path)
            val startLine = args["start_line"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val endLine = args["end_line"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val withLineNumbers =
                args["with_line_numbers"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            // 不传范围也不要求行号 → 与旧行为完全一致（返回体只有 path/text）。
            if (startLine == null && endLine == null && !withLineNumbers) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("path", path)
                            put("text", text)
                        }.toString()
                    )
                )
            } else {
                val lines = text.split("\n")
                val total = lines.size
                val from = (startLine ?: 1).coerceIn(1, maxOf(1, total))
                val to = (endLine ?: total).coerceIn(from, maxOf(from, total))
                val slice = lines.subList(from - 1, minOf(to, total))
                val body = if (withLineNumbers) {
                    slice.mapIndexed { i, line -> "${from + i}| $line" }.joinToString("\n")
                } else {
                    slice.joinToString("\n")
                }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("path", path)
                            put("text", body)
                            put("start_line", from)
                            put("end_line", minOf(to, total))
                            put("total_lines", total)
                            put("truncated", from > 1 || to < total)
                        }.toString()
                    )
                )
            }
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                    putWorkspaceProperty()
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional octal file mode, e.g. 755 for scripts and binaries or 644 for plain text. Files written without it are not executable inside the rootfs; pass 755 when the file must run.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val wsId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val mode = params["mode"]?.jsonPrimitive?.contentOrNull
        // 执行前读旧内容（文件不存在 → 视为空）：新建文件 diff 全绿、覆盖写显示红绿改动。
        // 与 edit_file 保持一致：diff 存 metadata，不随工具结果发给模型、不占上下文。
        val originalText = runCatching { workspaceRepository.readTextInRootfs(wsId, path) }.getOrNull()
        val entry = workspaceRepository.writeTextInRootfs(wsId, path, text, overwrite, mode)
        val diff = me.rerere.rikkahub.data.vault.SecretMasker.mask(generateUnifiedDiff(originalText.orEmpty(), text, entry.path).orEmpty())
        listOf(
            UIMessagePart.Text(
                text = entry.toJson().toString(),
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 file in the bound workspace (paths absolute, use /workspace).
        Single mode: replace old_text (must occur once; replace_all=true for every occurrence).
        Batch mode: pass `edits` — a list of {old_text, new_text} applied in order to the same file;
        a failing item aborts the whole batch without writing, so you never get a half-applied file.
        Whitespace-tolerant match is attempted when there is no exact hit.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                    putWorkspaceProperty()
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace (single mode)")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text (single mode)")
                })
                put("edits", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "Batch mode: ordered list of {old_text, new_text} for the same file. " +
                            "When present, top-level old_text/new_text are ignored.",
                    )
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("old_text", buildJsonObject { put("type", "string") })
                            put("new_text", buildJsonObject { put("type", "string") })
                        })
                        put("required", buildJsonArray {
                            add(JsonPrimitive("old_text")); add(JsonPrimitive("new_text"))
                        })
                    })
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val wsId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        val path = params.absolutePath("path")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        val original = workspaceRepository.readTextInRootfs(wsId, path)

        // 批量模式：一次提交多处替换（顺序应用）。任一处失败即整体失败且**不写盘** ——
        // 避免留下「改了一半」的文件（半成品比直接失败更难排查）。
        val batch = (params["edits"] as? JsonArray)?.takeIf { it.isNotEmpty() }
        val updatedText: String
        val replacementCount: Int
        // 「匹配策略」只有单条替换才有意义（批量由多条组成，策略可能不同，故不报）。
        var matchStrategy: String? = null
        if (batch != null) {
            var working = original
            var count = 0
            batch.forEachIndexed { index, element ->
                val item = element.jsonObject
                val oldText = item.string("old_text") ?: error("edits[$index].old_text is required")
                val newText = item.string("new_text") ?: error("edits[$index].new_text is required")
                require(oldText.isNotEmpty()) { "edits[$index].old_text must not be empty" }
                // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
                val r =
                    try {
                        replaceText(working, oldText, newText, replaceAll)
                    } catch (e: IllegalArgumentException) {
                        error("edits[$index] failed: ${e.message} (path: $path)")
                    }
                working = r.updated
                count += r.replacements
            }
            updatedText = working
            replacementCount = count
        } else {
            val oldText = params.string("old_text") ?: error("old_text is required (or provide `edits`)")
            val newText = params.string("new_text") ?: error("new_text is required (or provide `edits`)")
            require(oldText.isNotEmpty()) { "old_text must not be empty" }
            // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
            val r =
                try {
                    replaceText(original, oldText, newText, replaceAll)
                } catch (e: IllegalArgumentException) {
                    error("${e.message} (path: $path)")
                }
            updatedText = r.updated
            replacementCount = r.replacements
            matchStrategy = r.strategy
        }
        val entry = workspaceRepository.writeTextInRootfs(wsId, path, updatedText, overwrite = true)
        val diff = me.rerere.rikkahub.data.vault.SecretMasker.mask(generateUnifiedDiff(original, updatedText, entry.path).orEmpty())
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", replacementCount)
                    matchStrategy?.let { if (it != ExactReplacer.name) put("matchStrategy", it) }
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)


/**
 * shell 预设库在工作区内的固定位置（rootfs 绝对路径）。
 * 故意放在 `.agents/` 下：属**本地用户数据**，不应随任何仓库提交（模板可通用，实例只留在本地）。
 */
// —— diff_files：轻量文件对比（复用 generateUnifiedDiff, 超长行/输出截断防撑爆）——
private const val MAX_DIFF_LINE_CHARS = 400
// 仅作 fallback（与设置上限 32K 一致）；正常走 settings.toolOutputMaxChars（默认 8K）。
// 旧值 40_000 > 全局 32K，属互相矛盾的冗余值（2026-09-19 统一）。
private const val MAX_DIFF_TOTAL_CHARS = 32 * 1024

private fun createDiffFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "diff_files",
    description = """
        Compare two files inside the Rootfs (absolute paths). Returns a unified diff of a vs b.
        Reports explicitly when the two files are identical. Long diff lines are truncated and the
        total output is capped so it never overflows the context. Use for cross-repo porting,
        code review, or version comparison.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putWorkspaceProperty()
                put("a", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path of the first file inside Rootfs (/workspace/...).")
                })
                put("b", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path of the second file inside Rootfs (/workspace/...).")
                })
            },
            required = listOf("a", "b"),
        )
    },
    needsApproval = { needsApproval("diff_files") },
    execute = {
        val params = it.jsonObject
        val wsId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        val a = params.absolutePath("a")
        val b = params.absolutePath("b")
        val aText = workspaceRepository.readTextInRootfs(wsId, a)
        val bText = workspaceRepository.readTextInRootfs(wsId, b)
        val diff =
            me.rerere.rikkahub.data.vault.SecretMasker.mask(
                // 两个不同文件对比：头部分别用各自路径（否则 a/b 两侧显示同一个路径，看起来像方向反了）
                generateUnifiedDiff(aText, bText, a, newPath = b).orEmpty(),
            )
        val isSame = diff.isEmpty()
        // 增量摘要：只给数字，不占上下文（正文仍走 diff 字段与落盘机制）
        val added = diff.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") }
        val removed = diff.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
        val hunks = diff.lineSequence().count { it.startsWith("@@") }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("a", a)
                    put("b", b)
                    put("same", isSame)
                    put("added", added)
                    put("removed", removed)
                    put("hunks", hunks)
                    val diffLimit =
                        runCatching {
                            getKoin().get<me.rerere.rikkahub.data.datastore.SettingsStore>()
                                .settingsFlow.value.toolOutputMaxChars
                        }.getOrDefault(MAX_DIFF_TOTAL_CHARS)
                    put("diff", if (isSame) "" else limitDiffOutput(diff, diffLimit))
                }.toString(),
            ),
        )
    },
)

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

internal const val SHELL_PRESETS_PATH = "/workspace/.agents/shell-presets.json"

/** 匹配 `${name}` 与 `${name:-default}` 两种占位符；默认值部分不含花括号、冒号紧随变量名。 */
private val SHELL_PRESET_PLACEHOLDER = Regex("""\$\{([A-Za-z0-9_.\-]+)(:-[^{}]*)?\}""")

/** 读取预设库；文件缺失或不是合法 JSON 时返回空表，由调用方给出友好提示。 */
private suspend fun loadShellPresets(
    workspaceRepository: WorkspaceRepository,
    workspaceId: String,
): JsonObject =
    runCatching {
        Json.parseToJsonElement(
            workspaceRepository.readTextInRootfs(workspaceId, SHELL_PRESETS_PATH),
        ).jsonObject
    }.getOrElse { JsonObject(emptyMap()) }

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
 * 解析 `env` 参数（{环境变量名: vault凭证名}）并进程内解密注入。
 *
 * 独立成函数以压低 createShellTool 的圈复杂度；返回（注入映射, 错误消息），
 * 错误消息非空时调用方应直接返回它。
 */
private suspend fun resolveInjectedEnv(params: JsonObject): Pair<Map<String, String>, String?> {
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
        when (val r = resolver.resolve(credName, CredentialPurpose.ENV_INJECT, caller = "ai-tool")) {
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
private fun shellToolSchema(defaultCwd: String?): JsonObject =
    buildJsonObject {
        put("preset", buildJsonObject {
            put("type", "string")
            put(
                "description",
                "Name of a saved command preset. Presets live in the workspace file " +
                    "$SHELL_PRESETS_PATH ({\"<name>\": \"<command>\"} or {\"<name>\": {\"command\": ..., \"description\": ...}}). " +
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
                "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS.",
            )
        })
        put("workspace", buildJsonObject {
            put("type", "string")
            put("description", "Optional target workspace id (UUID). When set, runs the command in that workspace's rootfs instead of the current one. Use workspace_list to see available workspace ids.")
        })
        put("env", buildJsonObject {
            put("type", "object")
            put(
                "description",
                "Optional vault credentials to inject into the command's environment as variables, " +
                    "mapping envVarName -> vaultCredentialName. Values are decrypted in-process and " +
                    "injected ONLY into this command's process environment — never written to disk, " +
                    "never shown to the AI. Requires an active vault authorization. " +
                    "Example: {\"GITHUB_TOKEN\": \"GITHUB_TOKEN\"} makes the token available as \$GITHUB_TOKEN inside the command.",
            )
        })
    }

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = shellToolSchema(defaultCwd),
            required = emptyList(),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        // 预设模式：`preset` 指向工作区内的预设库条目（模板 + ${占位符}），由 preset_args 填值。
        // 设计意图：把「常用命令」从代码/提示词里挪到**本地工作区文件** —— 模板可通用，
        // 真实主机名/路径只留在本地实例，公开仓库里永远不出现内部内容；增删改直接用现有文件工具。
        val presetName = params.string("preset")?.takeIf { it.isNotBlank() }
        val command =
            if (presetName != null) {
                val presets = loadShellPresets(workspaceRepository, workspaceId)
                val node =
                    presets[presetName]
                        ?: return@Tool listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("error", "preset_not_found")
                                    put("preset", presetName)
                                    put("presetsPath", SHELL_PRESETS_PATH)
                                    put("hint", "Add it to $SHELL_PRESETS_PATH as {\"$presetName\": \"<command>\"}.")
                                    put("available", buildJsonArray { presets.keys.forEach { add(JsonPrimitive(it)) } })
                                }.toString(),
                            ),
                        )
                val template =
                    when (node) {
                        is JsonPrimitive -> node.contentOrNull.orEmpty()
                        is JsonObject -> node["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        else -> ""
                    }
                if (template.isBlank()) {
                    return@Tool listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "preset_empty")
                                put("preset", presetName)
                                put("presetsPath", SHELL_PRESETS_PATH)
                            }.toString(),
                        ),
                    )
                }
                renderShellPreset(presetName, template, params["preset_args"]?.jsonObject)
            } else {
                params.string("command") ?: error("command is required (or provide `preset`)")
            }
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val targetWorkspace = params.string("workspace")

        // env: { 环境变量名: vault凭证名 } —— 进程内解密注入，不落盘、AI 不见明文
        val (injectedEnv, envError) = resolveInjectedEnv(params.jsonObject)
        if (envError != null) return@Tool listOf(UIMessagePart.Text(envError))

        // 执行前后各取一次快照：让脚本改动的文件也能像写文件那样显示红绿 diff。
        // 快照有遍历数与缓存上限，超限即放弃检测（退回原行为，不影响命令执行）。
        // 扫描根取命令的 cwd（默认工作区根）：范围越小越快，也越不容易撞上条目上限
        val diffScanRoot = cwd.orEmpty()
        val beforeSnapshot = takeWorkspaceChangeSnapshot(workspaceRepository, workspaceId, diffScanRoot)

        val result = workspaceRepository.executeCommand(
            workspaceId, command, cwd, timeoutMillis,
            targetId = targetWorkspace, env = injectedEnv,
        )

        val afterSnapshot = takeWorkspaceChangeSnapshot(workspaceRepository, workspaceId, diffScanRoot)
        val changes = WorkspaceChangeDiff.compare(beforeSnapshot, afterSnapshot)
        val changeDiff =
            if (changes.isNotEmpty()) WorkspaceChangeDiff.buildDiff(changes, beforeSnapshot, afterSnapshot) else ""

        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                    if (changes.isNotEmpty()) putTouchedFilesSummary(changes)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染（不随工具结果发给模型，不占上下文）
                metadata = changeDiff.takeIf { it.isNotBlank() }
                    ?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createCreateFolderTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_create_folder",
    description = "Create a directory (and any missing parents) in the workspace. No-op if it already exists.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                    putWorkspaceProperty()
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_create_folder") || it.pathOutsideWritableRoots("path") },
    execute = {
        val wsId = resolveTargetWorkspaceId(workspaceRepository, it.jsonObject, workspaceId)
        val path = it.jsonObject.absolutePath("path")
        val entry = workspaceRepository.createFolderInRootfs(wsId, path)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createReadFolderTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_folder",
    description = "Recursively list a directory as an indented tree (entry count and depth capped).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                    putWorkspaceProperty()
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_folder") },
    execute = {
        val wsId = resolveTargetWorkspaceId(workspaceRepository, it.jsonObject, workspaceId)
        val path = it.jsonObject.absolutePath("path")
        val result = workspaceRepository.readFolderTree(wsId, path)
        listOf(UIMessagePart.Text(formatWorkspaceTree(path, result)))
    },
)

private fun createRunBackgroundTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_run_background",
    description = "Run a command persistently in the background (survives across tool calls). Use for dev servers, long installs, watchers. Do NOT append '&'. Returns a task id; poll with workspace_background_status, stop with workspace_background_kill.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putWorkspaceProperty()
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run in the background")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory relative to the workspace files root" +
                        if (!defaultCwd.isNullOrBlank()) ". Defaults to '$defaultCwd'." else ". Defaults to root.")
                })
                put("env", buildJsonObject {
                    put("type", "object")
                    put(
                        "description",
                        "Optional vault credentials to inject into the background command's environment as variables, " +
                            "mapping envVarName -> vaultCredentialName. Values are decrypted in-process and " +
                            "injected ONLY into this command's process environment — never written to disk, " +
                            "never shown to the AI. Requires an active vault authorization. " +
                            "Example: {\"GITHUB_TOKEN\": \"GITHUB_TOKEN\"} makes the token available as \$GITHUB_TOKEN inside the command.",
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_run_background") },
    execute = {
        val params = it.jsonObject
        val wsId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")

        // env: { 环境变量名: vault凭证名 } —— 同 workspace_shell：进程内解密注入，不落盘、AI 不见明文
        val (injectedEnv, envError) = resolveInjectedEnv(params)
        if (envError != null) return@Tool listOf(UIMessagePart.Text(envError))

        val status = workspaceRepository.startBackground(wsId, command, cwd, injectedEnv)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("id", status.id)
                    put("status", "running")
                }.toString()
            )
        )
    },
)

private fun createBackgroundStatusTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_background_status",
    description = "Check status and recent output of background tasks started with workspace_run_background. Omit id to list all.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putWorkspaceProperty()
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id from workspace_run_background. Omit to list all.")
                })
            },
            required = emptyList(),
        )
    },
    needsApproval = { needsApproval("workspace_background_status") },
    execute = {
        val wsId = resolveTargetWorkspaceId(workspaceRepository, it.jsonObject, workspaceId)
        val taskId = it.jsonObject.string("id")
        val statuses = if (taskId != null) {
            listOfNotNull(workspaceRepository.backgroundStatus(wsId, taskId))
        } else {
            workspaceRepository.listBackground(wsId)
        }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("processes", buildJsonArray {
                        statuses.forEach { status -> add(status.toJson()) }
                    })
                }.toString()
            )
        )
    },
)

private fun createBackgroundKillTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_background_kill",
    description = "Stop a background task by id.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putWorkspaceProperty()
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id from workspace_run_background")
                })
            },
            required = listOf("id"),
        )
    },
    needsApproval = { needsApproval("workspace_background_kill") },
    execute = {
        val wsId = resolveTargetWorkspaceId(workspaceRepository, it.jsonObject, workspaceId)
        val taskId = it.jsonObject.string("id") ?: error("id is required")
        val killed = workspaceRepository.killBackground(wsId, taskId)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("id", taskId)
                    put("killed", killed)
                }.toString()
            )
        )
    },
)

private fun BackgroundStatus.toJson() = buildJsonObject {
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

private fun createListTool(
    workspaceRepository: WorkspaceRepository,
): Tool = Tool(
    name = "workspace_list",
    description = buildString {
        append("List all available workspaces with their id, name and shell status. ")
        append("Use a workspace id as the 'workspace' parameter of workspace_shell / workspace_read_file / workspace_write_file / workspace_edit_file ")
        append("to run commands or access files in that workspace instead of the current one.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {},
            required = emptyList(),
        )
    },
    needsApproval = { false },
    execute = {
        val workspaces = workspaceRepository.getAll()
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("workspaces", buildJsonArray {
                        workspaces.forEach { w ->
                            add(
                                buildJsonObject {
                                    put("id", w.id)
                                    put("name", w.name)
                                    put("shellStatus", w.shellStatus)
                                    w.lastAccessAt?.let { put("lastAccessAt", it) }
                                }
                            )
                        }
                    })
                }.toString()
            )
        )
    },
)

internal fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

internal suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
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

internal suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
    mode: String? = null,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val modeArg = sanitizeFileMode(mode)
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${modeArg?.let { "chmod $it -- $pathArg || exit 1" }.orEmpty()}
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

internal fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区: 工作区文件目录, 临时目录 /tmp, 跨工作区共享目录 /mnt/shared
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp", "/skills", "/mnt/shared")

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWritableRoots()
    }.getOrDefault(true)

private fun String.isOutsideWritableRoots(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return WRITABLE_ROOT_PREFIXES.none { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}

private suspend fun WorkspaceRepository.createFolderInRootfs(
    workspaceId: String,
    path: String,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Create folder",
        command = """
            if [ -e $pathArg ] && [ ! -d $pathArg ]; then
              printf '%s\n' ${"Path already exists and is not a directory: $path".shellQuote()} >&2
              exit 1
            fi
            mkdir -p -- $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
    )
    return result.stdout.parseRootfsEntry()
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
