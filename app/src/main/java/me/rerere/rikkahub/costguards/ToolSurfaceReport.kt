package me.rerere.rikkahub.costguards

import kotlinx.serialization.json.Json
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

/** Rendered size of one tool as it will be serialised into the request: name + description + schema. */
private fun estimateChars(tool: Tool): Int {
    val schemaChars =
        runCatching {
            tool.parameters()?.let { json.encodeToString(InputSchema.serializer(), it).length } ?: 0
        }.getOrDefault(0)
    return tool.name.length + tool.description.length + schemaChars
}

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
        execute = { args ->
            val topN =
                (args as? JsonObject)
                    ?.get("top_n")
                    ?.jsonPrimitive
                    ?.intOrNull
                    ?: 8
            val tools = toolsProvider().distinctBy { it.name }
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

            val payload =
                buildJsonObject {
                    put("tool_count", tools.size)
                    put("est_chars", totalChars)
                    put("est_tokens", totalTokens)
                    put("order_is_sorted", names == sortedNames)
                    put("surface_hash", names.joinToString("\u0000").hashCode().toUInt().toString(16))
                    // Content hash: catches a re-worded description / schema that keeps the same
                    // names and order but still busts the cached prefix.
                    put(
                        "content_hash",
                        sized.joinToString("\u0000") { "${it.first.name}:${it.second}" }
                            .hashCode().toUInt().toString(16),
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
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )
