package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class HttpException(
    message: String,
    val statusCode: Int? = null,
    /** 服务端错误体里的机器可读错误码，如 OpenAI 的 `content_policy_violation`。 */
    val providerErrorCode: String? = null,
    /** 服务端错误体里的错误类型，如 `invalid_request_error`、`ResponsibleAIPolicyViolation`。 */
    val providerErrorType: String? = null,
) : RuntimeException(message)

/**
 * 把服务端返回的错误体解析成 [HttpException]。
 *
 * [statusCode] 是产生该错误体的 HTTP 状态码：下游的重试策略要靠它区分「确定性的 4xx」与
 * 「值得重试的 5xx / 无状态码」，不传会让 4xx 也被反复重试，所以能拿到就传。
 *
 * 同时尽量提取服务端的机器可读 `code` / `type`（顶层或 error 对象内）：各平台的内容风控等语义
 * 主要以这两个字段区分（OpenAI/Azure `content_policy_violation`、`content_filter`，
 * 阿里百炼 `data_inspection_failed`），只按文案匹配既容易漏也容易误判。
 */
fun JsonElement.parseErrorDetail(statusCode: Int? = null): HttpException {
    // 顶层可能直接带 code/type（如 {"code": "DataInspectionFailed"}），也可能只嵌在 error 里
    // 先取嵌套的 error.type/code（更具体）：顶层 type 常是笼统的 "error"，只说“缺失项”无用
    val providerErrorCode = stringField("error", "code") ?: stringField("code")
    val providerErrorType = stringField("error", "type") ?: stringField("type")
    return when (this) {
        is JsonObject -> {
            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                // 递归解析找到的字段值
                this[foundField]!!.parseErrorDetail(statusCode)
            } else {
                // 如果没有找到任何错误字段，序列化整个对象
                HttpException(
                    message = Json.encodeToString(JsonElement.serializer(), this),
                    statusCode = statusCode,
                    providerErrorCode = providerErrorCode,
                    providerErrorType = providerErrorType,
                )
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array", statusCode)
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail(statusCode)
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容 (covers JsonNull too — it's a JsonPrimitive subclass)
            HttpException(this.jsonPrimitive.content, statusCode, providerErrorCode, providerErrorType)
        }
    }
}

/** 按路径取字符串标量；任何一层缺失、为 null 或不是标量都返回 null。 */
private fun JsonElement.stringField(vararg path: String): String? {
    var node: JsonElement = this
    for (key in path) {
        node = (node as? JsonObject)?.get(key) ?: return null
    }
    val primitive = node as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content
}
