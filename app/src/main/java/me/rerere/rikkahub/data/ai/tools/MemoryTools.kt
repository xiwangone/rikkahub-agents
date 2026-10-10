package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
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

private fun memorySummaryContent(content: String): String =
    content.take(80) + if (content.length > 80) "…" else ""

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
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("create")
                            add("edit")
                            add("delete")
                            add("list")
                        })
                        put("description", "Operation to perform: create, edit, delete, or list")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of a memory record in the current scope (required for edit/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                    put("tier", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("core")
                            add("conditional")
                        })
                        put("description", "Optional memory tier: core (always injected) or conditional (retrieved on demand). When editing, omitted means keep the existing tier.")
                    })
                },
                required = listOf("action"),
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val tier = params["tier"]?.jsonPrimitive?.contentOrNull
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
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    memoryPayload(onCreation(content, tier ?: "core"), scopeId)
                }
                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    memoryPayload(onUpdate(id, content, tier), scopeId)
                }
                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                        put("scope_id", scopeId)
                    }
                }
                else -> error("unknown action: $action, must be one of [create, edit, delete, list]")
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
                        put("description", "Keyword to search memory content (e.g. 'PC', 'ECS', '凭证', 'Backend')")
                    })
                },
                required = listOf("keyword"),
            )
        },
        execute = {
            val keyword = it.jsonObject["keyword"]?.jsonPrimitive?.contentOrNull ?: error("keyword is required")
            val results = onSearch(keyword)
            val payload = buildJsonObject {
                put("keyword", keyword)
                put("scope_id", scopeId)
                put("results", buildJsonArray {
                    results.forEach { memory ->
                        add(memoryPayload(memory, scopeId, memorySummaryContent(memory.content)))
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    ),
)
