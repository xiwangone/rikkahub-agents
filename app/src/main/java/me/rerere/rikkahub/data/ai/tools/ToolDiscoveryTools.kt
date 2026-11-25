package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.optString

/**
 * 按需检索工具说明的元工具（渐进式披露）。
 *
 * 冷档工具的注入描述会被精简成一行，模型需要完整用法时用这两个工具取：
 * - `list_tools(keyword?)`：列出当前可用工具（名称 + 一行用途），可按关键词过滤；
 * - `get_tool_schema(name | names)`：取回单个或多个工具的完整说明与参数表。
 *
 * 注意：[allTools] 必须是**未精简**的完整工具列表，否则取不到完整说明。
 */
internal fun effectiveDiscoveryTier(
    toolName: String,
    trimEnabled: Boolean = true,
    scope: List<String> = emptyList(),
    extraCold: Set<String> = emptySet(),
): SurfaceTier {
    if (toolName in extraCold) return SurfaceTier.COLD
    if (!trimEnabled) return ToolSurfacePolicy.tierOf(toolName, extraCold)
    return if (scope.isEmpty()) {
        ToolSurfacePolicy.tierOf(toolName, extraCold)
    } else {
        ToolSurfacePolicy.tierOfWithScope(toolName, scope, extraCold)
    }
}

internal fun buildToolDiscoveryTools(
    allTools: List<Tool>,
    conversationId: String? = null,
    extraCold: Set<String> = emptySet(),
    context: Context? = null,
    trimEnabled: Boolean = true,
    scope: List<String> = emptyList(),
): List<Tool> =
    listOf(
        listToolsTool(allTools, conversationId, extraCold, trimEnabled, scope),
        getToolSchemaTool(allTools, conversationId, extraCold, context, trimEnabled, scope),
    )

private fun listToolsTool(
    allTools: List<Tool>,
    conversationId: String?,
    extraCold: Set<String> = emptySet(),
    trimEnabled: Boolean = true,
    scope: List<String> = emptyList(),
): Tool =
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
            val keyword = input.jsonObject.optString("keyword")?.trim().orEmpty()
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
                                        val tier = effectiveDiscoveryTier(tool.name, trimEnabled, scope, extraCold)
                                        put("tier", tier.name.lowercase())
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

@Suppress("LongParameterList")
private fun getToolSchemaTool(
    allTools: List<Tool>,
    conversationId: String?,
    extraCold: Set<String> = emptySet(),
    context: Context? = null,
    trimEnabled: Boolean = true,
    scope: List<String> = emptyList(),
): Tool =
    Tool(
        name = "get_tool_schema",
        description = """
            Return the full description and parameter schema of one or more tools by name. Use it after
            list_tools when you need the exact arguments of tools whose injected schema is empty.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties =
                    buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "Exact tool name, as returned by list_tools.")
                        })
                        put("names", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                            put("description", "Optional list of exact tool names to unlock in a single batch.")
                        })
                    },
                required = emptyList(),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val singleName = obj.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
            val batchNames =
                (obj["names"] as? JsonArray)
                    ?.mapNotNull { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()

            val payload =
                if (batchNames.isNotEmpty()) {
                    val requested = (listOfNotNull(singleName) + batchNames).distinct()
                    val found = mutableListOf<JsonObject>()
                    val notFound = mutableListOf<String>()
                    requested.forEach { targetName ->
                        val tool = allTools.firstOrNull { it.name == targetName }
                        if (tool == null) {
                            notFound.add(targetName)
                        } else {
                            unlockColdTool(tool, conversationId, extraCold, context, trimEnabled, scope)
                            found.add(schemaJsonFor(tool))
                        }
                    }
                    buildJsonObject {
                        put("count", found.size)
                        put("tools", JsonArray(found))
                        if (notFound.isNotEmpty()) {
                            put("not_found", JsonArray(notFound.map { JsonPrimitive(it) }))
                        }
                    }
                } else {
                    val name = singleName.orEmpty()
                    val tool = allTools.firstOrNull { it.name == name }
                    if (tool == null) {
                        ToolErrors.envelopeFor(
                            error = "invalid_argument",
                            message = "unknown tool '$name'",
                            hint = "Call list_tools first to see the available names.",
                        )
                    } else {
                        unlockColdTool(tool, conversationId, extraCold, context, trimEnabled, scope)
                        schemaJsonFor(tool)
                    }
                }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

private fun unlockColdTool(
    tool: Tool,
    conversationId: String?,
    extraCold: Set<String>,
    context: Context?,
    trimEnabled: Boolean,
    scope: List<String>,
) {
    if (effectiveDiscoveryTier(tool.name, trimEnabled, scope, extraCold) == SurfaceTier.COLD) {
        ToolSurfaceSession.markLoaded(conversationId, tool.name)
        context?.let { ToolUsageTracker.recordUnlock(it, tool.name) }
    }
}

private fun schemaJsonFor(tool: Tool): JsonObject =
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

/** 复用注入侧的"一行摘要"规则，保证 list_tools 与注入描述一致。 */
private fun String.toSingleLine(maxChars: Int = 120): String {
    val normalized = replace("\n", " ").trim()
    val head = normalized.substringBefore(". ").substringBefore("。")
    return if (head.length <= maxChars) head else head.take(maxChars).trimEnd() + "…"
}
