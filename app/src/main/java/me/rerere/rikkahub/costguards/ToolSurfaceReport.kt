package me.rerere.rikkahub.costguards

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.SurfaceTier
import me.rerere.rikkahub.data.ai.tools.ToolSurfacePolicy

/**
 * L4 observability — tool-surface quantifier.
 *
 * The tool definitions ride in every request and (together with the system prompt) sit at
 * the very front of the prompt, ahead of the message history. Two consequences make this
 * worth measuring:
 *
 *  1. They are the largest fixed per-turn cost, so any trimming has to start from data.
 *  2. Their ORDER and CONTENT are part of the cached prefix — a reordering or a re-worded
 *     schema invalidates the cache for the entire conversation that follows. Reporting a
 *     stable `surface_hash` / `order_is_sorted` lets a caller notice such a change.
 *
 * Estimation is deliberately cheap and provider-independent: `chars / 3`, the same ratio
 * [me.rerere.rikkahub.data.ai.ContextBudgetPlanner] uses, applied to the rendered
 * `name + description + JSON schema`. It is an estimate, not a tokenizer.
 *
 * Read-only; no side effects.
 */
private const val CHARS_PER_TOKEN = 3

private val json = Json { encodeDefaults = false }

/** Coarse origin label so the report can show WHERE the surface size comes from. */
private fun classifySource(name: String): String =
    when {
        name.startsWith("mcp__") -> "mcp"
        name.startsWith("workspace_") -> "workspace"
        name.startsWith("memory") -> "memory"
        name.startsWith("skill") || name == "use_skill" || name == "list_skills" -> "skills"
        name == "conversation_search" || name == "recent_chats" -> "conversation"
        name == "search_web" || name == "scrape_web" -> "web_search"
        name.startsWith("subagent") -> "subagent"
        name.startsWith("vault") -> "vault"
        name.startsWith("workflow") || name.startsWith("schedule_job") || name.startsWith("cron") -> "automation"
        else -> "local"
    }

/** Rendered size of the JSON schema part of one tool (0 when serialisation fails). */
private fun schemaCharsOf(tool: Tool): Int =
    runCatching {
        tool.parameters()?.let { json.encodeToString(InputSchema.serializer(), it).length } ?: 0
    }.getOrDefault(0)

/** Rendered size of one tool as it will be serialised into the request: name + description + schema. */
private fun estimateChars(tool: Tool): Int =
    tool.name.length + tool.description.length + schemaCharsOf(tool)

/**
 * 裁剪后的**近似**尺寸（对应“精简工具说明”启用时）。
 *
 * 与 `data/ai/tools/ChatToolFactory` 的裁剪规则**同判据**（都走 [ToolSurfacePolicy.decide]，
 * 避免两套档位逻辑漂移），但尺寸只做**量级估算**，不逐字节复刻：
 *  - HOT：不变；
 *  - WARM：描述按首段估（≤400 字符），**schema 完整保留**；
 *  - COLD：描述按一行（≤120）+ 固定提示句，**schema 计 0**。
 *
 * 用途：回答“开裁剪能省多少”，而不是“省到的字节数”。
 */
private fun estimateTrimmedChars(tool: Tool): Int {
    val schemaChars = schemaCharsOf(tool)
    return when (ToolSurfacePolicy.decide(tool.name).tier) {
        SurfaceTier.HOT -> tool.name.length + tool.description.length + schemaChars
        SurfaceTier.WARM -> {
            val desc =
                if (tool.description.length <= WARM_DESCRIPTION_KEEP_CHARS) {
                    tool.description.length
                } else {
                    tool.description.substringBefore("\n\n").replace("\n", " ").trim()
                        .length.coerceAtMost(WARM_COMPACT_CHARS)
                }
            tool.name.length + desc + schemaChars
        }
        SurfaceTier.COLD ->
            tool.name.length + tool.description.length.coerceAtMost(COLD_SINGLE_LINE_CHARS) + COLD_HINT_CHARS
    }
}

/** 与 ChatToolFactory 的 WARM 阈值保持一致（描述短于它就不动）。 */
private const val WARM_DESCRIPTION_KEEP_CHARS = 200

/** WARM 档描述收敛后的上限（首段 + 400 字符封顶）。 */
private const val WARM_COMPACT_CHARS = 400

/** COLD 档单行描述上限。 */
private const val COLD_SINGLE_LINE_CHARS = 120

/** COLD 档在描述尾部追加的固定提示句长度（“Before calling, use get_tool_schema …”）。 */
private const val COLD_HINT_CHARS = 110

fun toolSurfaceReportTool(
    // Deferred view of the assembled surface. getTools() adds this tool part-way through
    // building the list, so reading eagerly would under-report; execute() runs long after
    // assembly finished, so the lambda sees the complete set.
    toolsProvider: () -> List<Tool>,
): Tool =
    Tool(
        name = "tool_surface_report",
        description =
            """
            Report a size breakdown of the tool definitions currently attached to this turn: how many
            tools, their estimated token weight (chars / 3 of name + description + JSON schema), the
            per-source breakdown, and the largest individual tools. Also reports whether the list is in
            canonical (sorted) order and a stable hash of its contents, so a caller can tell whether the
            tool surface — which is part of the cached prompt prefix — has changed. Use before trimming
            tool descriptions, or to check whether a prompt-cache miss was caused by a tool change.
            Read-only.
            """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties =
                    buildJsonObject {
                        put(
                            "top_n",
                            buildJsonObject {
                                put("type", "integer")
                                put("description", "How many of the largest tools to list (default 8, max 40).")
                            },
                        )
                    },
                required = emptyList(),
            )
        },
        execute = { args -> runToolSurfaceReport(args, toolsProvider()) },
    )

/**
 * Builds the report payload for [tools]. Extracted so the assembly exit
 * (`ChatToolFactory`) can re-point the report at the surface the model actually receives —
 * the tool's own provider closure is bound inside `LocalTools` before S2/S4 trimming runs,
 * so measuring through it would always report pre-trim numbers.
 */
fun runToolSurfaceReport(
    args: JsonElement?,
    toolsInput: List<Tool>,
    onlyToolsUnmatched: List<String> = emptyList(),
): List<UIMessagePart> {
            val topN =
                (args as? JsonObject)
                    ?.get("top_n")
                    ?.jsonPrimitive
                    ?.intOrNull
                    ?: 8
            val tools = toolsInput.distinctBy { it.name }
            val names = tools.map { it.name }
            val sortedNames = names.sorted()

            val sized = tools.map { it to estimateChars(it) }
            val totalChars = sized.sumOf { it.second }
            val totalTokens = (totalChars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

            val bySource =
                sized
                    .groupBy { classifySource(it.first.name) }
                    .map { (source, entries) ->
                        val chars = entries.sumOf { it.second }
                        Triple(source, entries.size, (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN)
                    }
                    .sortedByDescending { it.third }

            val largest = sized.sortedByDescending { it.second }.take(topN.coerceIn(1, 40))

            // 裁剪后的投影（假设「精简工具说明」开启）：用于量化“开裁剪能省多少”。
            val trimmedChars = sized.sumOf { (tool, _) -> estimateTrimmedChars(tool) }
            val trimmedTokens = (trimmedChars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
            val tierCounts = tools.groupingBy { ToolSurfacePolicy.decide(it.name).tier }.eachCount()

            val payload =
                buildJsonObject {
                    put("tool_count", tools.size)
                    put("est_chars", totalChars)
                    put("est_tokens", totalTokens)
                    put("order_is_sorted", names == sortedNames)
                    // 白名单里拼错/过期的名字：不报错但静默失效，这里显式列出来便于自查。
                    if (onlyToolsUnmatched.isNotEmpty()) {
                        put(
                            "only_tools_unmatched",
                            buildJsonArray { onlyToolsUnmatched.forEach { add(it) } },
                        )
                    }
                    put("surface_hash", names.joinToString("\u0000").hashCode().toUInt().toString(16))
                    // Content hash: catches a re-worded description / schema that keeps the same
                    // names and order but still busts the cached prefix.
                    put(
                        "content_hash",
                        sized.joinToString("\u0000") { "${it.first.name}:${it.second}" }
                            .hashCode().toUInt().toString(16),
                    )
                    put(
                        "trimmed",
                        buildJsonObject {
                            put("est_tokens", trimmedTokens)
                            put("saved_tokens", totalTokens - trimmedTokens)
                            put(
                                "saved_pct",
                                if (totalTokens == 0) 0 else (totalTokens - trimmedTokens) * 100 / totalTokens,
                            )
                            put("hot", tierCounts[SurfaceTier.HOT] ?: 0)
                            put("warm", tierCounts[SurfaceTier.WARM] ?: 0)
                            put("cold", tierCounts[SurfaceTier.COLD] ?: 0)
                            put(
                                "note",
                                "projection only: assumes the 'trim tool descriptions' switch is ON; this request is not trimmed by it",
                            )
                        },
                    )
                    put(
                        "by_source",
                        buildJsonArray {
                            bySource.forEach { (source, count, tokens) ->
                                add(
                                    buildJsonObject {
                                        put("source", source)
                                        put("tools", count)
                                        put("est_tokens", tokens)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "largest",
                        buildJsonArray {
                            largest.forEach { (tool, chars) ->
                                add(
                                    buildJsonObject {
                                        put("name", tool.name)
                                        put("est_tokens", (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN)
                                        put("source", classifySource(tool.name))
                                    },
                                )
                            }
                        },
                    )
                    // Prompt-cache eligibility: providers only cache a prefix that already
                    // exceeds a minimum length (1024 tokens for OpenAI / Gemini Flash /
                    // Sonnet-class, 4096 for Gemini Pro / Opus-class). Below that, prefix
                    // stabilisation buys nothing.
                    put("min_cacheable_note", "most providers require >=1024 prefix tokens, some >=4096; below that the prefix is never cached")
                }
    return listOf(UIMessagePart.Text(payload.toString()))
}
