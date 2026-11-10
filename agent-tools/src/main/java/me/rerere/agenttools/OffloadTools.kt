package me.rerere.agenttools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.WorkspaceFileEntry
import java.io.IOException

/**
 * 大输出落盘机制.
 *
 * 长日志、完整文件 dump、diff、转录、生成代码写进 `offloads/` 目录,
 * 而不是原样塞进会话上下文. 只回给模型一小段 preview, 完整内容留在磁盘,
 * 使上下文保持精炼, 同时每个大产物都成为可引用的引用.
 */
private const val OFFLOAD_DIR = "offloads/"
private const val PREVIEW_CHARS = 400
const val OFFLOAD_HINT_BYTES = 12 * 1024

fun createOffloadTools(
    io: AgentWorkspaceIO,
    approvalOverrides: Map<String, Boolean>,
): List<Tool> {
    fun needsApproval(name: String) = resolveApproval(approvalOverrides, name)
    return listOf(
        createWriteOffloadTool(io, ::needsApproval),
        createListOffloadTool(io, ::needsApproval),
    )
}

private fun createWriteOffloadTool(
    io: AgentWorkspaceIO,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "workspace_offload",
    description = """
        Write a potentially large block of text (logs, full file dumps, diffs, transcripts,
        generated code) into the workspace offload area and return a reference.

        Returns {"path","bytes","preview"} — only the first $PREVIEW_CHARS chars are echoed back
        (preview) to keep the context lean; the FULL content is saved to disk under
        offloads/<name>.md. Use the returned path as a reference (point the user at it,
        or read it back later in smaller passes via workspace_read_file).
        Prefer this over dumping large outputs (>$OFFLOAD_HINT_BYTES bytes) into the conversation.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "File name base (no spaces; letters/digits/-/_ only), e.g. 'crash_log'. Written as <name>.md")
                    put("pattern", "^[A-Za-z0-9_-]+$")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Full content to persist (UTF-8). Single write is capped at 2MB (the workspace write limit); split larger content into multiple offloads.")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Overwrite an existing file with the same name. Defaults to false; pass true to replace it.")
                })
            },
            required = listOf("name", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_offload") },
    execute = {
        val params = it.jsonObject
        val name = sanitizeName(params["name"]?.jsonPrimitive?.contentOrNull)
        val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val path = "$OFFLOAD_DIR$name.md"
        val bytes = text.toByteArray(Charsets.UTF_8).size
        io.writeText(path, text, overwrite)
        val preview = when {
            text.length <= PREVIEW_CHARS -> text
            else -> text.take(PREVIEW_CHARS) + "\n…[+${text.length - PREVIEW_CHARS} chars; full at $path]"
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("path", path)
            put("bytes", bytes)
            put("preview", preview)
        }.toString()))
    },
)

private fun createListOffloadTool(
    io: AgentWorkspaceIO,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "workspace_offload_list",
    description = """
        List offoaded artifacts under the workspace offload area (name, size, updatedAt).
        Use this to discover what was previously offloaded before re-reading it.
        On a listing failure returns {"count":-1,"error":"..."} instead of an empty list,
        so a failure is not mistaken for an empty offload area.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = listOf()) },
    needsApproval = { needsApproval("workspace_offload_list") },
    execute = {
        var listError: String? = null
        val files = try {
            io.listFiles(OFFLOAD_DIR)
        } catch (e: IOException) {
            listError = e.message ?: "Failed to list offloads"
            emptyList()
        } catch (e: IllegalArgumentException) {
            listError = e.message ?: "Failed to list offloads"
            emptyList()
        } catch (e: IllegalStateException) {
            listError = e.message ?: "Failed to list offloads"
            emptyList()
        }
        // 列目录失败与"没有落盘文件"必须区分: 失败时返回 error 且 count 为 -1,
        // 而不是空列表加 count=0.
        val payload = buildJsonObject {
            put("path", OFFLOAD_DIR)
            put("count", if (listError == null) files.size else -1)
            if (listError != null) put("error", listError)
            put("files", buildJsonArray { files.forEach { add(it.toJson()) } })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun sanitizeName(raw: String?): String {
    val cleaned = (raw ?: "offload").replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
    return cleaned.ifBlank { "offload" }
}

private fun WorkspaceFileEntry.toJson(): JsonObject = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("bytes", sizeBytes)
    put("updatedAt", updatedAt)
}