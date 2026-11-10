package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.uuid.Uuid
import me.rerere.common.log.AppLogger


internal fun commonRoleToGoogleRole(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "user"
            MessageRole.SYSTEM -> "system"
            MessageRole.ASSISTANT -> "model"
            MessageRole.TOOL -> "user" // google api中, tool结果是用户role发送的
        }
    }

internal fun googleRoleToCommonRole(role: String): MessageRole {
        return when (role) {
            "user" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            "model" -> MessageRole.ASSISTANT
            // 兼容服务端返回非标准 role（如空串、"assistant"）：记日志后按 ASSISTANT 处理，
            // 避免整轮生成因一个未知 role 崩溃。
            else -> {
                AppLogger.w("GoogleProvider", "googleRoleToCommonRole: unknown role \"$role\", treating as assistant")
                MessageRole.ASSISTANT
            }
        }
    }

internal fun parseGoogleMessage(message: JsonObject): UIMessage {
        val role = googleRoleToCommonRole(
            message["role"]?.jsonPrimitive?.contentOrNull ?: "model"
        )
        val content = message["content"]?.jsonObject ?: error("No content")
        val parts = content["parts"]?.jsonArray?.mapIndexedNotNull { i, part -> parseMessagePart(part.jsonObject, i) } ?: emptyList()

        val groundingMetadata = message["groundingMetadata"]?.jsonObject
        AppLogger.i("GoogleProvider", "parseMessage: $groundingMetadata")
        val annotations = parseSearchGroundingMetadata(groundingMetadata)

        return UIMessage(
            role = role,
            parts = parts,
            annotations = annotations
        )
    }

internal fun parseSearchGroundingMetadata(jsonObject: JsonObject?): List<UIMessageAnnotation> {
        if (jsonObject == null) return emptyList()
        val groundingChunks = jsonObject["groundingChunks"]?.jsonArray ?: emptyList()
        val chunks = groundingChunks.mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val uri = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = web["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            UIMessageAnnotation.UrlCitation(
                title = title,
                url = uri
            )
        }
        AppLogger.i("GoogleProvider", "parseSearchGroundingMetadata: $chunks")
        return chunks
    }

    @Suppress("CyclomaticComplexMethod") // 多模态响应的各分片类型平铺分派，拆开反而难与协议字段对照
internal fun parseMessagePart(jsonObject: JsonObject, index: Int = 0): UIMessagePart? {
        return when {
            jsonObject.containsKey("text") -> {
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                if (thought) UIMessagePart.Reasoning(
                    reasoning = text,
                    createdAt = Clock.System.now(),
                    finishedAt = null,
                    metadata = thoughtSignature?.let {
                        buildJsonObject { put("thoughtSignature", JsonPrimitive(it)) }
                    },
                ) else UIMessagePart.Text(text)
            }

            jsonObject.containsKey("functionCall") -> {
                val functionCall = jsonObject["functionCall"] as? JsonObject
                val toolName = (functionCall?.get("name") as? JsonPrimitive)?.contentOrNull
                if (functionCall == null || toolName.isNullOrBlank()) {
                    AppLogger.w("GoogleProvider", "parseMessagePart: skipping functionCall without name")
                    return null
                }
                UIMessagePart.Tool(
                    toolCallId = (functionCall["id"] as? JsonPrimitive)?.contentOrNull ?: Uuid.random().toString(),
                    toolName = toolName,
                    input = json.encodeToString(functionCall["args"] ?: buildJsonObject {}),
                    output = emptyList(),
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                    ).toMetadata()
                )
            }

            jsonObject.containsKey("toolCall") -> {
                // containsKey 只保证键存在，值仍可能是 null：用安全转型，取不到对象就跳过该分片。
                val call = jsonObject["toolCall"] as? JsonObject
                if (call == null) {
                    AppLogger.w("GoogleProvider", "parseMessagePart: skipping toolCall with null value")
                    return null
                }
                UIMessagePart.ServerTool(call["id"]?.jsonPrimitive?.contentOrNull ?: "google-server-$index", call["toolType"]?.jsonPrimitive?.contentOrNull ?: "", call["args"], status = ServerToolStatus.IN_PROGRESS, metadata = ServerToolMetadata(protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT, call = jsonObject, callIndex = index).toMetadata())
            }
            jsonObject.containsKey("toolResponse") -> {
                val response = jsonObject["toolResponse"] as? JsonObject
                if (response == null) {
                    AppLogger.w("GoogleProvider", "parseMessagePart: skipping toolResponse with null value")
                    return null
                }
                UIMessagePart.ServerTool(response["id"]?.jsonPrimitive?.contentOrNull ?: "google-server-$index", response["toolType"]?.jsonPrimitive?.contentOrNull ?: "", output = response["response"], status = ServerToolStatus.COMPLETED, metadata = ServerToolMetadata(protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT, result = jsonObject, resultIndex = index).toMetadata())
            }
            jsonObject.containsKey("inlineData") -> {
                val inlineData = jsonObject["inlineData"] as? JsonObject
                if (inlineData == null) {
                    AppLogger.w("GoogleProvider", "parseMessagePart: skipping inlineData with null value")
                    return null
                }
                val mime = inlineData["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                val data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                require(mime.startsWith("image/")) {
                    "Only image mime type is supported"
                }
                // 如果是思考过程中的草稿图，直接忽略
                if (thought) {
                    return UIMessagePart.Reasoning(
                        reasoning = "[Draft Image]\n",
                        createdAt = Clock.System.now(),
                        finishedAt = null
                    )
                }
                UIMessagePart.Image(
                    // Every other producer/consumer in this codebase (Base64ImageToLocalFileTransformer,
                    // FileEncoder.encodeBase64, etc.) expects a proper data URL, not a bare payload -
                    // see issue #37.
                    url = "data:$mime;base64,$data",
                    metadata = GoogleThoughtMetadata(thoughtSignature = thoughtSignature).toMetadata()
                )
            }

            else -> {
                AppLogger.w("GoogleProvider", "parseMessagePart: skipping unrecognized part, keys=${jsonObject.keys}")
                null
            }
        }
    }

