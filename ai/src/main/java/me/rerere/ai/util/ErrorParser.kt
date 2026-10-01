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
    // code/type 只在最外层取一次：文案要逐层往下递归，若每层各自重取，递归到 message 标量时就丢光了
    // （顶层可能直接带，如 {"code": "DataInspectionFailed"}；也可能只嵌在 error 里，如 OpenAI/Azure）
    val providerErrorCode = stringField("error", "code") ?: stringField("code")
    val providerErrorType = stringField("error", "type") ?: stringField("type")
    return HttpException(
        message = errorMessageText(),
        statusCode = statusCode,
        providerErrorCode = providerErrorCode,
        providerErrorType = providerErrorType,
    )
}

/**
 * 挑出人类可读的报错文案：按 error → detail → message → description 的顺序取第一个存在的字段并逐层递归；
 * 一层都没有时把整个对象序列化当文案（保证服务端原话不丢）。
 */
private fun JsonElement.errorMessageText(): String =
    when (this) {
        is JsonObject -> {
            val errorFields = listOf("error", "detail", "message", "description")
            val foundField = errorFields.firstOrNull { this[it] != null }
            if (foundField != null) {
                this[foundField]!!.errorMessageText()
            } else {
                Json.encodeToString(JsonElement.serializer(), this)
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) "Unknown error: Empty JSON array" else this.first().errorMessageText()
        }

        is JsonPrimitive -> {
            // 基本类型直接取内容（JsonNull 也是 JsonPrimitive 的子类）
            this.jsonPrimitive.content
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
