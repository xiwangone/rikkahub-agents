package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 按需检索工具说明的元工具（渐进式披露）。
 *
 * 低频工具的注入描述会被精简成一行，模型需要完整用法时用这两个工具取：
 * - `list_tools(keyword?)`：列出当前可用工具（名称 + 一行用途），可按关键词过滤；
 * - `get_tool_schema(name)`：取回某个工具的完整说明与参数表。
 *
 * 注意：[allTools] 必须是**未精简**的完整工具列表，否则取不到完整说明。
 */
internal fun buildToolDiscoveryTools(
    allTools: List<Tool>,
    conversationId: String? = null,
): List<Tool> =
    listOf(
        listToolsTool(allTools, conversationId),
        getToolSchemaTool(allTools, conversationId),
    )

private fun listToolsTool(allTools: List<Tool>, conversationId: String?): Tool =
    Tool(
        name = "list_tools",
        description = """
            List the tools currently available in this conversation (name plus a one-line purpose),
            optionally filtered by a keyword. Use it when a tool you expect is not in the tool list,
            or before calling get_tool_schema.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties =
                    buildJsonObject {
                        put("keyword", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional case-insensitive substring matched against tool name or description.")
                        })
                    },
                required = emptyList(),
            )
        },
        execute = { input ->
            val keyword = input.jsonObject["keyword"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val matched =
                allTools.filter { tool ->
                    keyword.isBlank() ||
                        tool.name.contains(keyword, ignoreCase = true) ||
                        tool.description.contains(keyword, ignoreCase = true)
                }
            val payload =
                buildJsonObject {
                    put("count", matched.size)
                    put(
                        "tools",
                        buildJsonArray {
                            matched.forEach { tool ->
                                add(
                                    buildJsonObject {
                                        put("name", tool.name)
                                        put("purpose", tool.description.toSingleLine())
                                        val tier = ToolSurfacePolicy.tierOf(tool.name)
                                        put("tier", tier.name.lowercase())
                                        // Only cold tools can lack a full schema; hot/warm always ship
                                        // the complete one. Reporting false for them is misleading and
                                        // invites pointless get_tool_schema round trips.
                                        put(
                                            "schema_loaded",
                                            tier != SurfaceTier.COLD ||
                                                ToolSurfaceSession.isLoaded(conversationId, tool.name),
                                        )
                                    },
                                )
                            }
                        },
                    )
                    if (matched.isEmpty()) {
                        put("hint", "No tool matched. Try a shorter keyword, e.g. \"ssh\" or \"vault\".")
                    }
                }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

private fun getToolSchemaTool(allTools: List<Tool>, conversationId: String?): Tool =
    Tool(
        name = "get_tool_schema",
        description = """
            Return the full description and parameter schema of one tool by name. Use it after
            list_tools when you need the exact arguments of a tool whose injected description is short.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties =
                    buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "Exact tool name, as returned by list_tools.")
                        })
                    },
                required = listOf("name"),
            )
        },
        execute = { input ->
            val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val tool = allTools.firstOrNull { it.name == name }
            if (tool != null && ToolSurfacePolicy.tierOf(tool.name) == SurfaceTier.COLD) {
                ToolSurfaceSession.markLoaded(conversationId, tool.name)
            }
            val payload =
                if (tool == null) {
                    buildJsonObject {
                        put("error", "unknown tool '$name'")
                        put("hint", "Call list_tools first to see the available names.")
                    }
                } else {
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        val schema = tool.parameters()
                        put(
                            "parameters",
                            if (schema == null) {
                                kotlinx.serialization.json.JsonNull
                            } else {
                                Json.encodeToJsonElement(InputSchema.serializer(), schema)
                            },
                        )
                        put("needsApprovalByDefault", ToolApprovalDefaults.requiresApproval(tool.name))
                    }
                }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

/** 复用注入侧的"一行摘要"规则，保证 list_tools 与注入描述一致。 */
private fun String.toSingleLine(maxChars: Int = 120): String {
    val normalized = replace("\n", " ").trim()
    val head = normalized.substringBefore(". ").substringBefore("。")
    return if (head.length <= maxChars) head else head.take(maxChars).trimEnd() + "…"
}
