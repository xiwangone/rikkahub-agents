package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.util.Base64

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
    val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
    val layout = workspaceRepository.layout(workspaceId) ?: return emptyList()
    val approvalOverrides = workspace.toolApprovalOverrides()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.let(layout::relativeToWorkspace)
    val supportsBackground = workspace.capabilities().backgroundShell
    val isRemote = !workspace.capabilities().rootfs

    return buildList {
        add(createListTool(workspaceRepository))
        add(createReadFileTool(workspaceId, layout, ::needsApproval, workspaceRepository, isRemote))
        add(createWriteFileTool(workspaceId, layout, ::needsApproval, workspaceRepository, isRemote))
        add(createEditFileTool(workspaceId, layout, ::needsApproval, workspaceRepository))
        add(createApplyEditsTool(workspaceId, layout, ::needsApproval, workspaceRepository))
        add(createDiffFileTool(workspaceId, layout, ::needsApproval, workspaceRepository))
        add(createShellTool(workspaceId, layout, ::needsApproval, workspaceRepository, shellCwd, supportsBackground))
        add(createCreateFolderTool(workspaceId, layout, ::needsApproval, workspaceRepository))
        add(createReadFolderTool(workspaceId, layout, ::needsApproval, workspaceRepository))
        // 后台任务跑在本机进程里, 远程工作区没有这个落点, 不提供
        // （backgroundShell 位与原实现同口径：本地 true、远程 false）
        if (supportsBackground) {
            add(createRunBackgroundTool(workspaceId, layout, ::needsApproval, workspaceRepository, shellCwd))
            add(createBackgroundStatusTool(workspaceId, ::needsApproval, workspaceRepository))
            add(createBackgroundKillTool(workspaceId, ::needsApproval, workspaceRepository))
        }
        add(createSearchCodeTool(workspaceId, layout, ::needsApproval, workspaceRepository, shellCwd))
    } + me.rerere.agenttools.createAgentTools(
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
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    isRemote: Boolean = false,
) = Tool(
    name = "workspace_read_file",
    description = buildString {
        if (isRemote) {
            append("Read a file on the assistant's bound remote SSH workspace host. ")
            append("Paths must be absolute on the remote machine; use ${layout.workspaceDir} for the workspace files area ")
            append("(local Rootfs mounts like /workspace, /upload, /skills, and Android /sdcard do not exist on the remote host — use read_file for local device files). ")
        } else {
            append("Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs. ")
            append("Use ${layout.workspaceDir} for the workspace files area. NOTE: use read_file instead for files ")
            append("on the device outside the workspace Rootfs. ")
        }
        append("Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico). ")
        append("Non-text (binary) files are returned as base64 with \"binary\": true, \"size\" and \"base64\" fields. ")
        append("Large text files can be read in slices: pass start_line / end_line (1-based, inclusive). ")
        append("Set with_line_numbers=true to prefix each returned line with its line number.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
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
            val bytes = workspaceRepository.readRootfsBuffer(targetWorkspaceId, path).toByteArray()
            // 二进制内容不按 UTF-8 文本返回：乱码会被 AI 当成文本误判，改走 base64；
            // 本地 Rootfs 与远程 SSH 共用 readRootfsBuffer，两条路径一次覆盖
            if (bytes.isBinaryContent()) {
                requireInlineBase64Size(bytes.size, path)
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("path", path)
                            put("binary", true)
                            put("size", bytes.size)
                            put("base64", Base64.getEncoder().encodeToString(bytes))
                        }.toString()
                    )
                )
            }
            val rawText = bytes.toString(Charsets.UTF_8)
            // 凭证类文件（.env / 私钥 / .ssh 下等）只掩「值」，见 isSensitiveCredentialPath。
            val sensitive = isSensitiveCredentialPath(path)
            val text = if (sensitive) redactCredentialFileText(rawText) else rawText
            val result = formatReadFileTextResult(
                path = path,
                text = text,
                startLine = args.int("start_line"),
                endLine = args.int("end_line"),
                withLineNumbers = args.boolean("with_line_numbers") ?: false,
            )
            listOf(
                UIMessagePart.Text(
                    if (sensitive) {
                        JsonObject(result + mapOf("redacted" to JsonPrimitive(true))).toString()
                    } else {
                        result.toString()
                    },
                ),
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    isRemote: Boolean = false,
) = Tool(
    name = "workspace_write_file",
    description = buildString {
        append("Write a UTF-8 text file in the assistant's bound workspace. Paths must be absolute inside the workspace environment. ")
        append("Use ${layout.workspaceDir} for the workspace files area. ")
        if (isRemote) {
            append("This is a remote SSH workspace; use write_text_file for local Android device files.")
        } else {
            append("NOTE: use write_text_file instead for files on the Android device outside the workspace Rootfs.")
        }
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
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
                    put("description", "Optional octal file mode, e.g. 755 for scripts and binaries or 644 for plain text. Files written without it keep the environment's default permissions (usually not executable); pass 755 when the file must run.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots(layout, "path") },
    execute = {
        val params = it.jsonObject
        val wsId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        val path = params.absolutePath("path")
        validateWorkspaceTargetPath(workspaceRepository, wsId, path)
        val text = params.string("text") ?: error("text is required")
        val overwrite = params.boolean("overwrite") ?: true
        val mode = sanitizeFileMode(params.string("mode"))
        // 执行前读旧内容（文件不存在 → 视为空）：新建文件 diff 全绿、覆盖写显示红绿改动。
        // 与 edit_file 保持一致：diff 存 metadata，不随工具结果发给模型、不占上下文。
        val originalText = runCatching { workspaceRepository.readAbsoluteText(wsId, path) }.getOrNull()
        val entry = workspaceRepository.writeAbsoluteText(wsId, path, text, overwrite, mode)
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
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 file in the bound workspace (paths absolute, use ${layout.workspaceDir}).
        Single mode: replace old_text (must occur once; replace_all=true for every occurrence).
        Batch mode: pass `edits` — a list of {old_text, new_text} applied in order to the same file;
        a failing item aborts the whole batch without writing, so you never get a half-applied file.
        Whitespace-tolerant match is attempted when there is no exact hit.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
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
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots(layout, "path") },
    execute = {
        val params = it.jsonObject
        val wsId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        val path = params.absolutePath("path")
        validateWorkspaceTargetPath(workspaceRepository, wsId, path)
        val replaceAll = params.boolean("replace_all") ?: false

        val original = workspaceRepository.readAbsoluteText(wsId, path)

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
        val entry = workspaceRepository.writeAbsoluteText(wsId, path, updatedText, overwrite = true)
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
internal const val MAX_DIFF_LINE_CHARS = 400
// 仅作 fallback（与设置上限 32K 一致）；正常走 settings.toolOutputMaxChars（默认 8K）。
// 旧值 40_000 > 全局 32K，属互相矛盾的冗余值（已统一）。
internal const val MAX_DIFF_TOTAL_CHARS = 32 * 1024

private fun createDiffFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "diff_files",
    description = """
        Compare two files inside the workspace (absolute paths). Returns a unified diff of a vs b.
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
                    put("description", "Absolute path of the first file inside the workspace (${layout.workspaceDir}/...).")
                })
                put("b", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path of the second file inside the workspace (${layout.workspaceDir}/...).")
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
        val aText = workspaceRepository.readAbsoluteText(wsId, a)
        val bText = workspaceRepository.readAbsoluteText(wsId, b)
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


private fun createShellTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
    supportsBackground: Boolean = true,
) = Tool(
    name = "workspace_shell",
    description = buildShellToolDescription(layout, defaultCwd, supportsBackground),
    parameters = {
        InputSchema.Obj(
            properties = shellToolSchema(defaultCwd, shellPresetsPath(layout)),
            required = emptyList(),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val targetWorkspaceId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        val targetLayout = resolveEffectiveLayout(workspaceRepository, workspaceId, targetWorkspaceId, layout)
        val presetsPath = shellPresetsPath(targetLayout)
        // 预设模式：`preset` 指向工作区内的预设库条目（模板 + ${占位符}），由 preset_args 填值。
        val (resolvedCommand, presetError) =
            resolveShellCommandOrError(workspaceRepository, targetWorkspaceId, presetsPath, params)
        if (presetError != null) return@Tool listOf(UIMessagePart.Text(presetError))
        val command = resolvedCommand ?: error("command is required (or provide `preset`)")
        val ignoredCommand = !params.string("preset").isNullOrBlank() && !params.string("command").isNullOrBlank()
        HardlineCommandGuard.checkCommand(command)?.let { blockedReason ->
            return@Tool listOf(
                UIMessagePart.Text(
                    ToolErrors.envelopeFor(error = ToolErrors.HARDLINE_BLOCKED, message = blockedReason).toString(),
                ),
            )
        }
        val cwdParam = params.string("cwd")
        val rawCwd = cwdParam ?: if (targetWorkspaceId == workspaceId) defaultCwd.orEmpty() else ""
        // 显式传入的绝对 cwd 也要过路径边界校验：远程工作区误传 /workspace、/sdcard 时
        // 直接给指路提示，而不是被 relativeToWorkspace 拼成 <remoteDir>/workspace 后报 cd 失败。
        if (cwdParam != null && cwdParam.startsWith("/")) {
            validateWorkspaceTargetPath(workspaceRepository, targetWorkspaceId, cwdParam)
        }
        val cwd = targetLayout.relativeToWorkspace(rawCwd)
        val timeoutRaw = params.string("timeout")
        val timeoutSeconds = timeoutRaw?.toLongOrNull()
        if (timeoutRaw != null && timeoutSeconds == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    ToolErrors.envelopeFor(error = "invalid_argument", message = workspaceToolMessage(R.string.workspace_tool_timeout_invalid, timeoutRaw, SHELL_TIMEOUT_MAX_SECONDS.toString()), extra = ToolErrors.extraOf("timeout" to timeoutRaw, "maxTimeoutSeconds" to SHELL_TIMEOUT_MAX_SECONDS)).toString(),
                ),
            )
        }
        // 超上限时夹取到上限，并在结果里注明实际生效值；未传时沿用原默认 30 秒。
        val effectiveTimeoutSeconds = timeoutSeconds?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?: (WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS / 1_000L)
        val timeoutMillis = effectiveTimeoutSeconds * 1_000L
        // env: { 环境变量名: vault凭证名 } —— 进程内解密注入，不落盘、AI 不见明文
        val (injectedEnv, envError) = resolveInjectedEnv(params)
        if (envError != null) return@Tool listOf(UIMessagePart.Text(envError))

        val beforeSnapshot = takeWorkspaceChangeSnapshot(workspaceRepository, targetWorkspaceId, cwd)
        val result = workspaceRepository.executeCommand(
            workspaceId, command, cwd, timeoutMillis,
            targetId = targetWorkspaceId, env = injectedEnv,
        )
        val afterSnapshot = takeWorkspaceChangeSnapshot(workspaceRepository, targetWorkspaceId, cwd)
        val diffSkipped = beforeSnapshot.skipped || afterSnapshot.skipped
        val changes = if (diffSkipped) emptyList() else WorkspaceChangeDiff.compare(beforeSnapshot, afterSnapshot)
        val changeDiff =
            if (changes.isNotEmpty()) WorkspaceChangeDiff.buildDiff(changes, beforeSnapshot, afterSnapshot) else ""

        listOf(
            UIMessagePart.Text(
                text = buildShellOutputJson(
                    result = result,
                    effectiveTimeoutSeconds = effectiveTimeoutSeconds,
                    requestedTimeoutSeconds = timeoutSeconds,
                    supportsBackground = supportsBackground,
                    ignoredCommand = ignoredCommand,
                    diffSkipped = diffSkipped,
                    changes = changes,
                ).toString(),
                metadata = changeDiff.takeIf { it.isNotBlank() }
                    ?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createCreateFolderTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_create_folder",
    description = "Create a directory (and any missing parents) in the workspace. No-op if it already exists.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                    putWorkspaceProperty()
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_create_folder") || it.pathOutsideWritableRoots(layout, "path") },
    execute = {
        val wsId = resolveTargetWorkspaceId(workspaceRepository, it.jsonObject, workspaceId)
        val path = it.jsonObject.absolutePath("path")
        validateWorkspaceTargetPath(workspaceRepository, wsId, path)
        val entry = workspaceRepository.createAbsoluteFolder(wsId, path)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createReadFolderTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_folder",
    description = "Recursively list a directory as an indented tree (entry count and depth capped).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                    putWorkspaceProperty()
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_folder") },
    execute = {
        val wsId = resolveTargetWorkspaceId(workspaceRepository, it.jsonObject, workspaceId)
        val path = it.jsonObject.absolutePath("path")
        validateWorkspaceTargetPath(workspaceRepository, wsId, path)
        val result = workspaceRepository.readFolderTree(wsId, path)
        listOf(UIMessagePart.Text(formatWorkspaceTree(path, result)))
    },
)

private fun createRunBackgroundTool(
    workspaceId: String,
    layout: WorkspaceLayout,
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
                            "A plaintext metadata field can be referenced as `credentialName.<field>` " +
                            "(e.g. {\"AWS_ACCESS_KEY_ID\": \"MY_S3.access_key_id\"}); plaintext fields are not " +
                            "gated by authorization because they are stored unencrypted. " +
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
        val rawCommand = params.string("command") ?: error("command is required")
        val trimmedCmd = rawCommand.trimEnd()
        val strippedAmpersand = trimmedCmd.endsWith("&") && !trimmedCmd.endsWith("&&")
        val command = if (strippedAmpersand) trimmedCmd.dropLast(1).trimEnd() else rawCommand
        val targetLayout = resolveEffectiveLayout(workspaceRepository, workspaceId, wsId, layout)
        val cwdParam = params.string("cwd")
        val rawCwd = cwdParam ?: if (wsId == workspaceId) defaultCwd.orEmpty() else ""
        // 同 workspace_shell：显式绝对 cwd 先过路径边界校验，避免拼成 <remoteDir>/workspace 后 cd 失败。
        if (cwdParam != null && cwdParam.startsWith("/")) {
            validateWorkspaceTargetPath(workspaceRepository, wsId, cwdParam)
        }
        val cwd = targetLayout.relativeToWorkspace(rawCwd)

        // env: { 环境变量名: vault凭证名 } —— 同 workspace_shell：进程内解密注入，不落盘、AI 不见明文
        val (injectedEnv, envError) = resolveInjectedEnv(params)
        if (envError != null) return@Tool listOf(UIMessagePart.Text(envError))

        val status = workspaceRepository.startBackground(wsId, command, cwd, injectedEnv)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("id", status.id)
                    // start() 返回的真实状态：命令秒退时这里就是 exited，不用再轮询一轮才知道。
                    put("status", if (status.running) "running" else "exited")
                    if (!status.running) status.exitCode?.let { put("exitCode", it) }
                    if (strippedAmpersand) {
                        put("warning", "Stripped trailing '&': workspace_run_background already runs the command in its own persistent background process.")
                    }
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
                    // 查指定 id 时明确 found：未知 id 返回 found:false，不再与“没有任务”混在一起。
                    if (taskId != null) {
                        put("found", statuses.isNotEmpty())
                        put("id", taskId)
                    }
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
                    // kill 成功当且仅当该 id 的任务存在（已移除）：killed:false 即无此任务。
                    put("found", killed)
                    put("killed", killed)
                }.toString()
            )
        )
    },
)


private fun createListTool(
    workspaceRepository: WorkspaceRepository,
): Tool = Tool(
    name = "workspace_list",
    description = buildString {
        append("List all available workspaces with their id, name, type (local or ssh), workspaceDir, backgroundShell support, and shell status. ")
        append("Use a workspace name (or id) as the 'workspace' parameter of workspace_shell / workspace_read_file / workspace_write_file / workspace_edit_file ")
        append("to run commands or access files in that workspace instead of the current one. Prefer the name.")
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
                                    put("type", if (w.isSsh()) "ssh" else "local")
                                    put("workspaceDir", w.workspaceDir() ?: WorkspaceManager.ROOTFS_WORKSPACE_DIR)
                                    put("backgroundShell", w.capabilities().backgroundShell)
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
