package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository

/**
 * `workspace_search_code` —— 结构化内容搜索（P33）。
 *
 * 痛点：宽泛 `grep` 的输出可达数十 MB，被截断后段不可见 → AI 误判"无匹配"。
 * 本工具只返回**命中行 + 总量 + 截断标记**（上下文占用恒定），参数经单引号引用防注入。
 */
internal fun createSearchCodeTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_search_code",
    description =
        "Search file contents in the workspace and return structured matches (file, line, text) plus total count and a truncation flag. " +
            "Prefer this over a broad grep when you only need matching lines.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Extended regular expression (ERE) to search for")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional search root relative to the workspace files root" +
                            if (!defaultCwd.isNullOrBlank()) ". Defaults to '$defaultCwd'." else ". Defaults to root.",
                    )
                })
                put("glob", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional file name filter, e.g. '*.kt' (mapped to grep --include)")
                })
                put("max_results", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum matched lines to return (default 100, max 500)")
                })
            },
            required = listOf("pattern"),
        )
    },
    needsApproval = { needsApproval("workspace_search_code") },
    execute = {
        val params = it.jsonObject
        val pattern =
            params["pattern"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
                ?: error("pattern is required")
        val relPath = (params["path"]?.jsonPrimitive?.contentOrNull ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val glob = params["glob"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
        val max = (params["max_results"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100)
            .coerceIn(1, 500)

        // 单引号引用：防命令注入（pattern/path/glob 均来自模型）
        fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val include = if (glob != null) "--include=${shq(glob)} " else ""
        val target = if (relPath.isBlank()) "." else shq(relPath)
        val grep = "grep -rnE $include${shq(pattern)} $target 2>/dev/null"
        val cmd = "{ $grep | head -n ${max + 1}; printf '\\n---TOTAL---\\n'; $grep | wc -l; }"

        val result = workspaceRepository.executeCommand(workspaceId, cmd, "")
        val raw = result.stdout
        val totalIdx = raw.indexOf("---TOTAL---")
        val hitsPart = if (totalIdx >= 0) raw.substring(0, totalIdx) else raw
        val total =
            if (totalIdx >= 0) raw.substring(totalIdx + "---TOTAL---".length).trim().toIntOrNull() ?: 0 else 0
        val hitLines = hitsPart.lines().filter { it.isNotBlank() }
        val truncated = hitLines.size > max
        val matches =
            hitLines.take(max).mapNotNull { line ->
                val firstColon = line.indexOf(':')
                if (firstColon <= 0) return@mapNotNull null
                val secondColon = line.indexOf(':', firstColon + 1)
                if (secondColon <= 0) return@mapNotNull null
                val file = line.substring(0, firstColon)
                val lineNo = line.substring(firstColon + 1, secondColon).toIntOrNull()
                    ?: return@mapNotNull null
                buildJsonObject {
                    put("file", file)
                    put("line", lineNo)
                    put("text", line.substring(secondColon + 1).take(500))
                }
            }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("matches", buildJsonArray { matches.forEach { add(it) } })
                    put("returned", matches.size)
                    put("total", total)
                    put("truncated", truncated)
                }.toString(),
            ),
        )
    },
)

