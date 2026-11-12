package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.OpenRouterReasoningMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import kotlin.time.Clock
import me.rerere.common.log.AppLogger


internal fun parseMessage(jsonObject: JsonObject): UIMessage {
    val role = MessageRole.fromValueOrNull(jsonObject["role"]?.jsonPrimitiveOrNull?.contentOrNull)
        ?: MessageRole.ASSISTANT
    val content = extractTextContent(jsonObject["content"])
    val reasoning = extractReasoningText(jsonObject)
    val reasoningMetadata = jsonObject["reasoning_details"]?.jsonArrayOrNull?.let {
        OpenRouterReasoningMetadata(reasoningDetails = it).toMetadata()
    }
    val toolCalls = jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
    val images = jsonObject["images"] as? JsonArray ?: JsonArray(emptyList())

    return UIMessage(
        role = role,
        parts = buildList {
            if (!reasoning.isNullOrEmpty() || reasoningMetadata != null) {
                add(
                    UIMessagePart.Reasoning(
                        reasoning = reasoning.orEmpty(),
                        createdAt = Clock.System.now(),
                        finishedAt = null,
                        metadata = reasoningMetadata,
                    )
                )
            }
            addAll(parseToolCallParts(toolCalls))
            if (content.isNotEmpty()) add(UIMessagePart.Text(content))
            addAll(parseImageParts(images))
        },
        annotations = parseAnnotations(
            jsonArray = jsonObject["annotations"]?.jsonArrayOrNull ?: JsonArray(emptyList())
        ),
    )
}

private fun extractTextContent(contentElement: kotlinx.serialization.json.JsonElement?): String =
    parseChatCompletionsContent(contentElement).text

private fun extractReasoningText(jsonObject: JsonObject): String? =
    jsonObject["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: jsonObject["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: parseChatCompletionsContent(jsonObject["content"]).reasoning

private fun parseToolCallParts(toolCalls: JsonArray): List<UIMessagePart.Tool> =
    toolCalls.mapNotNull { tc ->
        val obj = tc.jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (!type.isNullOrEmpty() && type != "function") {
            AppLogger.w("ChatCompletionsAPI", "skipping unsupported tool call type: $type")
            return@mapNotNull null
        }
        val fnObj = obj["function"]?.jsonObject
        val thoughtSignature = extractGoogleThoughtSignature(obj)
        UIMessagePart.Tool(
            toolCallId = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
            toolName = fnObj?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
            input = fnObj?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "",
            output = emptyList(),
            metadata = thoughtSignature?.let { GoogleThoughtMetadata(thoughtSignature = it).toMetadata() },
        )
    }

private fun parseImageParts(images: JsonArray): List<UIMessagePart.Image> =
    images.mapNotNull { image ->
        val imageObject = image.jsonObjectOrNull ?: return@mapNotNull null
        if (imageObject["type"]?.jsonPrimitive?.contentOrNull != "image_url") return@mapNotNull null
        val url = imageObject["image_url"]?.jsonObjectOrNull?.get("url")?.jsonPrimitive?.contentOrNull
            ?: return@mapNotNull null
        val parsed = parseImageDataUri(url) ?: return@mapNotNull null
        UIMessagePart.Image(parsed.base64)
    }

internal fun parseAnnotations(jsonArray: JsonArray): List<UIMessageAnnotation> {
        return jsonArray.mapNotNull { element ->
            val type =
                element.jsonObject["type"]?.jsonPrimitive?.contentOrNull
            when (type) {
                "url_citation" -> {
                    UIMessageAnnotation.UrlCitation(
                        title = element.jsonObject["url_citation"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
                            ?: "",
                        url = element.jsonObject["url_citation"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            ?: "",
                    )
                }

                else -> {
                    // Newer providers add annotation types (file_citation, web_search_result,
                    // ...). Skip unknown/missing types instead of throwing, which would crash
                    // the whole stream for the user.
                    AppLogger.w("ChatCompletionsAPI", "skipping unknown annotation type: $type")
                    null
                }
            }
        }
    }

internal fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            // 各 provider 汇报缓存命中的字段形状不统一，按方言兜底解析（#1576）：
            // OpenAI 嵌套 -> Moonshot 顶层 cached_tokens -> DeepSeek prompt_cache_hit_tokens
            cachedTokens = jsonObject["prompt_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: jsonObject["cached_tokens"]?.jsonPrimitive?.intOrNull
                ?: jsonObject["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
                ?: 0,
            // OpenRouter reports the generation cost (USD) here when the request asks for it
            // via usage:{include:true}. Other OpenAI-compatible providers omit it -> null.
            cost = jsonObject["cost"]?.jsonPrimitive?.doubleOrNull
        )
    }

internal fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
        val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
        val texts = filter { it is UIMessagePart.Text }.size
        return gonnaSend == texts && texts == 1
    }


/**
 * Google 的 OpenAI 兼容端点（/chat/completions）会在 functionCall 上返回
 * `extra_content.google.thought_signature`，第二轮请求必须在同一个 tool_call 上原样回传，
 * 否则被拒：`Function call is missing a thought_signature in functionCall parts`。
 *
 * 流式挂在 `choices[].delta.tool_calls[]`，非流式挂在 `choices[].message.tool_calls[]`，
 * 两处字段形状相同，故解析路径共用本函数。其它 OpenAI 兼容供应商不返回该对象 -> 返回 null，
 * 请求体与本批之前完全一致。
 */
internal fun extractGoogleThoughtSignature(toolCall: JsonObject): String? =
    toolCall["extra_content"]?.jsonObjectOrNull
        ?.get("google")?.jsonObjectOrNull
        ?.get("thought_signature")?.jsonPrimitiveOrNull?.contentOrNull
