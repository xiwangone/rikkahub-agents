package me.rerere.rikkahub.data.ai.mcp.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 将 RikkaHub 统一的 [Tool] 模型映射为 MCP 工具定义（tools/list 输出），
 * 以及将工具执行结果 [List<UIMessagePart>] 映射为 MCP content 数组。
 *
 * `Tool.parameters()` 已经是 JSON Schema 形态（`InputSchema.Obj`：properties + required），
 * 可直接透传为标准 MCP inputSchema。
 */
internal fun Tool.toMcpToolDefinition(): JsonObject {
    val schema = parameters()
    val inputSchema = when (schema) {
        is InputSchema.Obj -> buildJsonObject {
            put("type", "object")
            put("properties", schema.properties)
            schema.required?.takeIf { it.isNotEmpty() }?.let {
                put("required", JsonArray(it.map(::JsonPrimitive)))
            }
        }
        null -> buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(emptyMap()))
        }
    }
    return buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", inputSchema)
    }
}

/**
 * 将单个工具结果 part 转为 MCP content item（text / image）。
 * 其余类型（视频、音频、嵌套工具等）以序列化文本兜底，保证模型能看到完整内容。
 */
internal fun UIMessagePart.toMcpContent(): JsonObject = when (this) {
    is UIMessagePart.Text -> buildJsonObject {
        put("type", "text")
        put("text", text)
    }
    is UIMessagePart.Image -> buildJsonObject {
        put("type", "image")
        put("data", url)
    }
    else -> buildJsonObject {
        put("type", "text")
        put("text", toString())
    }
}