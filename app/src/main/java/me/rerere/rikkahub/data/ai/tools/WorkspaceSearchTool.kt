package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceLayout

/**
 * `workspace_search_code` —— 结构化内容搜索（P33）。
 *
 * 痛点：宽泛 `grep` 的输出可达数十 MB，被截断后段不可见 → AI 误判"无匹配"。
 * 本工具只返回**命中行 + 总量 + 截断标记**（上下文占用恒定），参数经单引号引用防注入。
 */
internal fun createSearchCodeTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_search_code",
    description =
        "Search file contents in the workspace and return structured matches (file, line, text) plus total count and a truncation flag. " +
            "Prefer this over a broad grep when you only need matching lines.",
    parameters = { searchCodeParameters(defaultCwd) },
    needsApproval = { needsApproval("workspace_search_code") },
    execute = {
        val params = it.jsonObject
        val wsId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        val targetLayout = resolveEffectiveLayout(workspaceRepository, workspaceId, wsId, layout)
        val pattern =
            params.string("pattern")?.takeIf { s -> s.isNotBlank() }
                ?: error("pattern is required")
        val rawPath = params.string("path")?.trim() ?: if (wsId == workspaceId) defaultCwd.orEmpty() else ""
        if (rawPath.startsWith("/")) {
            validateWorkspaceTargetPath(workspaceRepository, wsId, rawPath)
        }
        val relPath = targetLayout.relativeToWorkspace(rawPath)
        val glob = params.string("glob")?.takeIf { s -> s.isNotBlank() }
        val max = (params.int("max_results") ?: 100).coerceIn(1, 500)

        val cmd = buildSearchCodeCommand(pattern, relPath, glob, max)
        val result = workspaceRepository.executeCommand(wsId, cmd, "")
        listOf(
            UIMessagePart.Text(
                formatSearchCodeOutput(result, max, relPath, pattern, targetLayout.workspaceDir).toString(),
            ),
        )
    },
)

private fun searchCodeParameters(defaultCwd: String?): InputSchema.Obj =
    InputSchema.Obj(
        properties = buildJsonObject {
            putWorkspaceProperty()
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

private fun buildSearchCodeCommand(
    pattern: String,
    relPath: String,
    glob: String?,
    max: Int,
): String {
    fun shq(s: String) = "'" + s.replace("'", "'\''") + "'"
    val include = if (glob != null) "--include=${shq(glob)} " else ""
    val target = if (relPath.isBlank()) "." else shq(relPath)
    val grep = "grep -rnE $include${shq(pattern)} $target 2>/dev/null"
    val checkCmd = "grep -rnE $include${shq(pattern)} $target >/dev/null; printf '%s' \$?"
    return "{ printf '\n---HITS---\n'; $grep | head -n ${max + 1}; " +
        "printf '\n---TOTAL---\n'; $grep | wc -l; " +
        "printf '\n---EXIT---\n'; $checkCmd; }"
}

private fun formatSearchCodeOutput(
    result: WorkspaceCommandResult,
    max: Int,
    relPath: String,
    pattern: String,
    workspaceDir: String,
): JsonObject {
    val raw = result.stdout
    val hitsIdx = raw.indexOf("---HITS---")
    val totalIdx = raw.indexOf("---TOTAL---")
    val exitIdx = raw.indexOf("---EXIT---")
    val hitsPart = when {
        hitsIdx >= 0 && totalIdx > hitsIdx -> raw.substring(hitsIdx + "---HITS---".length, totalIdx)
        totalIdx >= 0 -> raw.substring(0, totalIdx)
        else -> raw
    }
    val totalEnd = if (exitIdx > totalIdx) exitIdx else raw.length
    val total =
        if (totalIdx >= 0) raw.substring(totalIdx + "---TOTAL---".length, totalEnd).trim().toIntOrNull() ?: 0 else 0
    val grepExit =
        if (exitIdx >= 0) raw.substring(exitIdx + "---EXIT---".length).trim().toIntOrNull() ?: 0 else 0
    val hitLines = hitsPart.lines().filter { it.isNotBlank() }
    val truncated = hitLines.size > max
    val matches = hitLines.take(max).mapNotNull(::parseGrepHitLine)
    return buildJsonObject {
        put("matches", buildJsonArray { matches.forEach { add(it) } })
        put("returned", matches.size)
        put("total", total)
        put("truncated", truncated)
        if (result.timedOut) {
            put("timedOut", true)
            put("warning", "Search timed out before finishing; narrow 'path' or 'glob' to speed up the scan.")
        } else if (grepExit >= 2 && matches.isEmpty()) {
            val stderrTail = if (result.stderr.isNotBlank()) " stderr: ${result.stderr.trim().take(200)}" else ""
            put(
                "warning",
                "grep exited with status $grepExit (search path '$relPath' may not exist inside $workspaceDir, or pattern '$pattern' is not a valid extended regular expression).$stderrTail",
            )
        }
    }
}

private fun parseGrepHitLine(line: String): JsonObject? {
    val firstColon = line.indexOf(':')
    if (firstColon <= 0) return null
    val secondColon = line.indexOf(':', firstColon + 1)
    if (secondColon <= 0) return null
    val file = line.substring(0, firstColon)
    val lineNo = line.substring(firstColon + 1, secondColon).toIntOrNull() ?: return null
    // 敏感路径的匹配行同样要掩：read 已掩、search 不掩 = 一条绕过路径
    // （grep `PASSWORD` 就能把 .env 的值原样带进模型上下文）。
    val rawText = line.substring(secondColon + 1)
    val safeText = if (isSensitiveCredentialPath(file)) redactCredentialFileText(rawText) else rawText
    return buildJsonObject {
        put("file", file)
        put("line", lineNo)
        put("text", safeText.take(500))
        if (safeText != rawText) put("redacted", true)
    }
}
