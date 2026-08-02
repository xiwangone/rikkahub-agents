package me.rerere.llamacpp

import kotlinx.serialization.json.Json
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts the app's messages and tools into the OpenAI-shaped request that
 * llama.cpp's chat template layer consumes. The shape is deliberately the same one the
 * app already builds for Chat Completions, so there is one wire format to reason about.
 */
object ChatRequestMapper {

    fun toRequestJson(messages: List<UIMessage>, tools: List<Tool>): String {
        val root = JSONObject()
        root.put("messages", messagesArray(messages))
        // Absent rather than empty: an empty array makes some templates emit a tool
        // preamble describing no tools at all.
        if (tools.isNotEmpty()) {
            root.put("tools", toolsArray(tools))
        }
        return root.toString()
    }

    fun toolDeclarations(tools: List<Tool>): List<ToolDeclaration> {
        val array = toolsArray(tools)
        return tools.mapIndexed { index, tool ->
            ToolDeclaration(
                name = tool.name,
                jsonBytes = array.getJSONObject(index).toString().toByteArray().size,
            )
        }
    }

    private fun messagesArray(messages: List<UIMessage>): JSONArray {
        val array = JSONArray()
        for (message in messages) {
            val role = when (message.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
                MessageRole.TOOL -> "tool"
            }

            val text = message.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }

            val obj = JSONObject()
            obj.put("role", role)
            obj.put("content", text)

            // An assistant turn that called tools carries them so the template can
            // render the call, and the matching tool results follow as tool messages.
            val toolParts = message.parts.filterIsInstance<UIMessagePart.Tool>()
            if (role == "assistant" && toolParts.isNotEmpty()) {
                val calls = JSONArray()
                for (part in toolParts) {
                    calls.put(
                        JSONObject()
                            .put("id", part.toolCallId)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", part.toolName)
                                    // Normalized the same way ChatCompletionsAPI does, so a
                                    // stream cut mid-argument never sends broken JSON.
                                    .put("arguments", part.inputAsJson().toString()),
                            )
                    )
                }
                obj.put("tool_calls", calls)
            }
            array.put(obj)

            // Every executed call needs a matching tool message, or a strict template
            // rejects the conversation for an unresolved tool_call_id.
            if (role == "assistant") {
                for (part in toolParts.filter { it.isExecuted }) {
                    val output = part.output
                        .filterIsInstance<UIMessagePart.Text>()
                        .joinToString("") { it.text }
                    array.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("name", part.toolName)
                            .put("tool_call_id", part.toolCallId)
                            .put("content", output)
                    )
                }
            }
        }
        return array
    }

    private fun toolsArray(tools: List<Tool>): JSONArray {
        val array = JSONArray()
        for (tool in tools) {
            // parameters is a lambda returning InputSchema?, and InputSchema is
            // @Serializable, so encode it with kotlinx and re-read it as org.json to
            // keep one JSON object model in this file.
            val schema = tool.parameters()
            val schemaJson = if (schema == null) {
                JSONObject().put("type", "object").put("properties", JSONObject())
            } else {
                JSONObject(Json.encodeToString(InputSchema.serializer(), schema))
            }
            array.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", schemaJson)
                    )
            )
        }
        return array
    }
}
