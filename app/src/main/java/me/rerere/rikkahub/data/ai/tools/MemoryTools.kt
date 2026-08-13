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
import java.time.LocalDate

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String, String) -> AssistantMemory,
    onUpdate: suspend (Int, String, String) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit,
    onSearch: suspend (String) -> List<AssistantMemory>,
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores long-term information across conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove).
            - No relevant record: `create` + `content`
            - Existing relevant record: `edit` + `id` + `content`
            - Outdated/irrelevant record: `delete` + `id`
            Memories will automatically appear in the <memories> tag in later conversations.
            **tier 分层（2026-08-13）**: core（默认）= 常驻注入（纪律/决策/指针，每轮都在）；conditional = 按需检索（场景细节，默认不注入，任务涉及相关场景时先调 memory_search 检索再使用）。
            **注意**: 需要环境/场景细节（PC/ECS/凭证/MCP/Reasonix 等）时，先调用 memory_search 检索相关记忆——不要假设记忆里没有。
            Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political views, sex life, criminal records).
            You may store: preferred name, preferences, plans, work-related notes, chat style preferences, first chat time, etc.
            Do not show memory content directly in the conversation unless the user explicitly asks.
            Today is ${LocalDate.now().toLocalString(true)}.
            Similar memories should be merged; prefer updating existing records.

            Examples:
            {"action":"create","content":"User prefers brief replies and is more active on weekends."}
            {"action":"edit","id":12,"content":"User’s preferred name updated to “A-Xing”, prefers Chinese replies."}
            {"action":"delete","id":7}
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
                            }
                        )
                        put("description", "Operation to perform: create, edit, or delete")
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

                else -> error("unknown action: $action, must be one of [create, edit, delete]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "memory_search",
        description = """
            Search conditional memories (tier=conditional, not injected by default) by keyword.
            Use this when a task involves a specific environment / scenario (PC / ECS / credentials /
            MCP / Reasonix / deployment details / past decisions) and you need the related memory —
            it is NOT in the always-injected <memories> tag. Returns matching memory entries (id, tier, content).
            Also usable to look up any memory across conversations.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("keyword", buildJsonObject {
                        put("type", "string")
                        put("description", "Keyword to search memory content (e.g. 'PC', 'ECS', '凭证', 'Reasonix')")
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
