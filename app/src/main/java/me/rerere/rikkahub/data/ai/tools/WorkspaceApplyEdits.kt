package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceLayout

/**
 * 跨文件事务编辑工具（App 侧对应物：`工具/multi-edit.py` 的工作区版本）。
 *
 * 语义：所有文件的替换先在**内存**里做完，全部成功才统一写盘；任何一条失败都
 * **不写任何文件**。用于「同一改动跨多文件」（例如给每个语言目录下的 strings.xml 插同一个键），
 * 避免逐个 `workspace_edit_file` 造成半套改动。
 *
 * 单独成文件（而不是塞进 WorkspaceTools.kt）：职责独立，且该文件函数数已到静态检查门限。
 */
internal fun createApplyEditsTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_apply_edits",
    description = """
        Apply text edits across MULTIPLE files transactionally: every edit must match (staged
        in memory) before ANY file is written; if one fails, no file changes at all. Prefer
        this over repeated workspace_edit_file calls when one change spans several files.
        Per file: {path, edits: [{old_text, new_text}], replace_all?}. Paths absolute inside the workspace.
    """.trimIndent().replace("\n", " "),
    parameters = { applyEditsParameters() },
    // 与 workspace_write_file / edit_file / create_folder 一致：任一路径落在可写区之外就要审批。
    // 漏了这一条时，工具被自动放行后能直接写到可写区之外（免审批越界写）。
    // 参数是 JsonElement（不是 JsonObject），解析失败按“需要审批”处理，免得漏审。
    needsApproval = { args ->
        if (needsApproval("workspace_apply_edits")) {
            true
        } else {
            runCatching {
                args.jsonObject["files"]
                    ?.jsonArray
                    ?.any { it.pathOutsideWritableRoots(layout, "path") }
            }.getOrNull() ?: true
        }
    },
    execute = { args ->
        runApplyEdits(resolveTargetWorkspaceId(workspaceRepository, args.jsonObject, workspaceId), workspaceRepository, args)
    },
)

private fun applyEditsParameters(): InputSchema.Obj =
    InputSchema.Obj(
        properties = buildJsonObject {
            putWorkspaceProperty()
            put("files", buildJsonObject {
                put("type", "array")
                put(
                    "description",
                    "Files to edit, applied in order. All edits are validated before writing.",
                )
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Absolute path inside the workspace files area (e.g. /workspace/notes.md).")
                        })
                        put("replace_all", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Replace every occurrence in this file. Defaults to false.")
                        })
                        put("edits", buildJsonObject {
                            put("type", "array")
                            put("description", "Ordered list of {old_text, new_text} for this file.")
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
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("path")); add(JsonPrimitive("edits"))
                    })
                })
            })
        },
        required = listOf("files"),
    )

/**
 * 执行：先把所有文件的替换在内存里做完（任一处不匹配即整体失败、不写盘），
 * 全部成功后再统一写盘；写盘中途失败时把已写文件写回原文。
 */
private suspend fun runApplyEdits(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    args: JsonElement,
): List<UIMessagePart> {
    val filesJson = args.jsonObject["files"] as? JsonArray
        ?: return ToolErrors.partsFor(ToolErrors.INVALID_ARGUMENT,
            "files is required (array of {path, edits})",
        )
    if (filesJson.isEmpty()) {
        return ToolErrors.partsFor(ToolErrors.INVALID_ARGUMENT, "files must not be empty")
    }

    // path -> (newText, replacements)；同一路径出现两次时，第二次基于第一次的结果继续编辑。
    val staged = LinkedHashMap<String, Pair<String, Int>>()
    // path -> 写盘前原文；写盘中途失败时用于把已写文件写回。
    val originals = LinkedHashMap<String, String>()
    filesJson.forEachIndexed { fileIndex, element ->
        val file = element.jsonObject
        val path = file.absolutePath("path")
        val edits = file["edits"] as? JsonArray
            ?: return ToolErrors.partsFor(ToolErrors.INVALID_ARGUMENT,
                "files[$fileIndex].edits is required",
            )
        if (edits.isEmpty()) {
            return ToolErrors.partsFor(ToolErrors.INVALID_ARGUMENT,
                "files[$fileIndex].edits must not be empty",
            )
        }
        val replaceAll = file["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        var working = staged[path]?.first
            ?: workspaceRepository.readAbsoluteText(workspaceId, path).also { originals[path] = it }
        var count = staged[path]?.second ?: 0
        edits.forEachIndexed { editIndex, editElement ->
            val item = editElement.jsonObject
            val oldText = item.string("old_text")
                ?: return ToolErrors.partsFor(ToolErrors.INVALID_ARGUMENT,
                    "files[$fileIndex].edits[$editIndex].old_text is required",
                )
            val newText = item.string("new_text")
                ?: return ToolErrors.partsFor(ToolErrors.INVALID_ARGUMENT,
                    "files[$fileIndex].edits[$editIndex].new_text is required",
                )
            if (oldText.isEmpty()) {
                return ToolErrors.partsFor(ToolErrors.INVALID_ARGUMENT,
                    "files[$fileIndex].edits[$editIndex].old_text must not be empty",
                )
            }
            // 逐级尝试 exact -> line_trimmed -> block_anchor（见 TextReplacers.kt）
            val r = try {
                replaceText(working, oldText, newText, replaceAll)
            } catch (e: IllegalArgumentException) {
                // 任一处失败 → 整体不写盘（与 workspace_edit_file 的批量语义一致）
                return ToolErrors.partsFor(ToolErrors.INVALID_ARGUMENT,
                    "files[$fileIndex].edits[$editIndex] failed: ${e.message} (path: $path)",
                )
            }
            working = r.updated
            count += r.replacements
        }
        staged[path] = working to count
    }

    // 全部匹配成功 → 统一写盘；中途任一处写失败时把已写文件（含写失败的本文件）
    // 写回原文，对外仍满足“任一处失败不写任何文件”。
    val writtenPaths = mutableListOf<String>()
    val written = buildJsonArray {
        for ((path, pair) in staged) {
            val entry = runCatching {
                workspaceRepository.writeAbsoluteText(workspaceId, path, pair.first, overwrite = true)
            }.getOrElse { e ->
                val rollbackFailures = rollbackToOriginals(workspaceId, workspaceRepository, writtenPaths + path, originals)
                val rollbackNote = rollbackFailures
                    .takeIf { it.isNotEmpty() }
                    ?.let { "; rollback failed for ${it.joinToString()}" }
                    .orEmpty()
                return ToolErrors.partsFor(ToolErrors.INTERNAL,
                    "write failed for $path after all edits matched: ${e.message}$rollbackNote",
                )
            }
            writtenPaths += path
            addJsonObject {
                put("path", entry.path)
                put("replacements", pair.second)
                put("sizeBytes", entry.sizeBytes)
            }
        }
    }
    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("files", written)
                put("fileCount", staged.size)
            }.toString()
        )
    )
}

/**
 * 把 [paths] 逐个写回 [originals] 中的原文；单个回滚失败不影响其余文件，
 * 返回回滚失败的路径（调用方把结果拼进错误信息，避免静默留半套改动）。
 */
private suspend fun rollbackToOriginals(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    paths: List<String>,
    originals: Map<String, String>,
): List<String> {
    val failures = mutableListOf<String>()
    for (path in paths) {
        val original = originals[path] ?: continue
        runCatching {
            workspaceRepository.writeAbsoluteText(workspaceId, path, original, overwrite = true)
        }.onFailure { failures += path }
    }
    return failures
}
