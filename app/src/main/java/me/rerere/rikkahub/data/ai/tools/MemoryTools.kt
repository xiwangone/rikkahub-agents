package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.optInt
import me.rerere.common.http.optString
import me.rerere.rikkahub.data.model.AssistantMemory

private fun memoryPayload(
    memory: AssistantMemory,
    scopeId: String,
    content: String = memory.content,
) = buildJsonObject {
    put("id", memory.id)
    put("content", content)
    put("tier", memory.tier)
    put("scope_id", scopeId)
}

private const val MEMORY_LIST_SUMMARY_CHARS = 80
private const val MEMORY_SEARCH_MAX_CHARS = 1_500

private fun memorySummaryContent(content: String, maxChars: Int = MEMORY_LIST_SUMMARY_CHARS): String =
    content.take(maxChars) + if (content.length > maxChars) "…" else ""

private fun memoryToolSchema(): InputSchema.Obj = InputSchema.Obj(
    properties = buildJsonObject {
        put("action", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("create"); add("edit"); add("delete"); add("list"); add("get") })
            put("description", "Operation to perform: create, edit, delete, list, or get")
        })
        put("id", buildJsonObject {
            put("type", "integer")
            put("description", "The id of a memory record in the current scope (required for edit/delete/get)")
        })
        put("content", buildJsonObject {
            put("type", "string")
            put("description", "The content of the memory record (required for create/edit)")
        })
        put("tier", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("core"); add("conditional") })
            put("description", "Optional memory tier: core (always injected) or conditional (retrieved on demand). When editing, omitted means keep the existing tier.")
        })
    },
    required = listOf("action"),
)

fun buildMemoryTools(
    scopeId: String,
    onCreation: suspend (String, String) -> AssistantMemory,
    onUpdate: suspend (Int, String, String?) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit,
    onSearch: suspend (String) -> List<AssistantMemory>,
    onListAll: suspend () -> List<AssistantMemory>,
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            Manage long-term memories only within the current assistant's configured memory scope (global or assistant-isolated): create, edit, delete, or list.
            Prefer editing a related record over creating a duplicate. Core memories are injected every turn; conditional memories are retrieved only on demand.
        """.trimIndent(),
        parameters = { memoryToolSchema() },
        execute = {
            val params = it.jsonObject
            val action = params.optString("action") ?: error("action is required")
            val tier = params.optString("tier")
            val payload = when (action) {
                "list" -> {
                    val memories = onListAll()
                    buildJsonObject {
                        put("action", "list")
                        put("scope_id", scopeId)
                        put("count", memories.size)
                        put("memories", buildJsonArray {
                            memories.forEach { memory ->
                                add(memoryPayload(memory, scopeId, memorySummaryContent(memory.content)))
                            }
                        })
                    }
                }
                "create" -> {
                    val content = params.optString("content") ?: error("content is required")
                    memoryPayload(onCreation(content, tier ?: "core"), scopeId)
                }
                "edit" -> {
                    val id = params.optInt("id") ?: error("id is required")
                    val content = params.optString("content") ?: error("content is required")
                    memoryPayload(onUpdate(id, content, tier), scopeId)
                }
                "get" -> {
                    val id = params.optInt("id") ?: error("id is required")
                    val memory = onListAll().firstOrNull { it.id == id }
                        ?: error("memory record not found in current scope: $id")
                    memoryPayload(memory, scopeId, memory.content)
                }
                "delete" -> {
                    val id = params.optInt("id") ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                        put("scope_id", scopeId)
                    }
                }
                else -> error("unknown action: $action, must be one of [create, edit, delete, list, get]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    ),
    Tool(
        name = "memory_search",
        description = """
            Search conditional memories (details not injected every turn) by keyword, only within the current assistant's configured memory scope. Use when a task needs details absent from <memories>.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("keyword", buildJsonObject {
                        put("type", "string")
                        put("description", "Keyword to search memory content (e.g. 'PC', 'ECS', 'credential', 'Backend')")
                    })
                },
                required = listOf("keyword"),
            )
        },
        execute = {
            val keyword = it.jsonObject.optString("keyword") ?: error("keyword is required")
            val results = onSearch(keyword)
            val payload = buildJsonObject {
                put("keyword", keyword)
                put("scope_id", scopeId)
                put("results", buildJsonArray {
                    results.forEach { memory ->
                        add(memoryPayload(memory, scopeId, memorySummaryContent(memory.content, MEMORY_SEARCH_MAX_CHARS)))
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    ),
)
