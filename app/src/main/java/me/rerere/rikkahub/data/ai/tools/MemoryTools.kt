package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
import me.rerere.rikkahub.utils.toLocalString
fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String, String) -> AssistantMemory,
    onUpdate: suspend (Int, String, String) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit,
    onSearch: suspend (String) -> List<AssistantMemory>,
    onListAll: suspend () -> List<AssistantMemory>,
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """"
            Store/retrieve long-term facts across conversations (create/edit/delete/list).
            Prefer edit over create when a related record exists; merge similar entries.
            Memories auto-appear in <memories> each turn; do not echo them back unless asked.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                                add("list")
                            }
                        )
                        put("description", "Operation to perform: create, edit, delete, or list")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                    put("tier", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("core")
                                add("conditional")
                            }
                        )
                        put("description", "Memory tier: core (default, always injected) or conditional (retrieved on demand via memory_search)")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val tier = params["tier"]?.jsonPrimitive?.contentOrNull ?: "core"
            val payload = when (action) {
                "list" -> {
                    val memories = onListAll()
                    buildJsonObject {
                        put("action", "list")
                        put("count", memories.size)
                        put("memories", buildJsonArray {
                            memories.forEach { m ->
                                add(buildJsonObject {
                                    put("id", m.id)
                                    put("tier", m.tier)
                                    put("content", m.content)
                                })
                            }
                        })
                    }
                }
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content, tier))
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onUpdate(id, content, tier))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete, list]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "memory_search",
        description = """
            Search conditional memories (tier=conditional, not injected by default) by keyword.
            Use this when a task involves a specific environment / scenario (PC / ECS / credentials /
            MCP / Backend / deployment details / past decisions) and you need the related memory —
            it is NOT in the always-injected <memories> tag. Returns matching memory entries (id, tier, content).
            Also usable to look up any memory across conversations.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("keyword", buildJsonObject {
                        put("type", "string")
                        put("description", "Keyword to search memory content (e.g. 'PC', 'ECS', '凭证', 'Backend')")
                    })
                },
                required = listOf("keyword")
            )
        },
        execute = {
            val keyword = it.jsonObject["keyword"]?.jsonPrimitive?.contentOrNull ?: error("keyword is required")
            val results = onSearch(keyword)
            val payload = buildJsonObject {
                put("keyword", keyword)
                put("results", buildJsonArray {
                    results.forEach { m ->
                        add(buildJsonObject {
                            put("id", m.id)
                            put("tier", m.tier)
                            put("content", m.content)
                        })
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
)
