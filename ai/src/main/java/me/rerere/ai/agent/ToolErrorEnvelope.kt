package me.rerere.ai.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

/**
 * AI 内核层工具错误信封唯一实现（对齐 JSON-RPC 2.0 §5.1 与 MCP 扩展错误码）。
 *
 * 格式形状：
 * ```json
 * {
 *   "code": -32603,
 *   "message": "...",
 *   "data": {
 *     "error": "internal_error",
 *     "recovery": "abort",
 *     "hint": "..."
 *   }
 * }
 * ```
 *
 * app 侧的 [me.rerere.rikkahub.data.ai.tools.ToolErrors] 是本实现的门面：
 * 它保留业务码 → (code, recovery) 映射表与便捷 API，JSON 构建走这里。
 */
object ToolErrorEnvelope {
    // ---- JSON-RPC 2.0 §5.1 标准码 ----
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // ---- MCP 扩展码 ----
    const val SERVER_ERROR = -32000
    const val RESOURCE_NOT_FOUND = -32002
    const val REQUEST_CANCELLED = -32800
    const val CONTENT_TOO_LARGE = -32801

    // ---- 恢复指引建议 ----
    const val RECOVERY_RETRY = "retry"
    const val RECOVERY_FIX_PARAM = "fix_param"
    const val RECOVERY_ABORT = "abort"

    private val RESERVED_DATA_KEYS = setOf("error", "recovery", "hint")

    /**
     * 显式指定 JSON-RPC 分类码与恢复语义。
     */
    fun envelope(
        code: Int,
        message: String,
        error: String? = null,
        recovery: String? = null,
        hint: String? = null,
        extra: Map<String, JsonElement> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("code", code)
        put("message", message)
        put("data", buildJsonObject {
            if (error != null) put("error", error)
            if (recovery != null) put("recovery", recovery)
            if (!hint.isNullOrBlank()) put("hint", hint)
            for ((k, v) in extra) {
                if (k !in RESERVED_DATA_KEYS) put(k, v)
            }
        })
    }

    /**
     * 便捷构造：返回标准工具错误消息 Part 列表。
     */
    fun partsFor(
        error: String,
        message: String,
        code: Int = SERVER_ERROR,
        recovery: String = RECOVERY_ABORT,
        hint: String? = null,
        extra: Map<String, JsonElement> = emptyMap(),
    ): List<UIMessagePart> = listOf(
        UIMessagePart.Text(
            envelope(
                code = code,
                message = message,
                error = error,
                recovery = recovery,
                hint = hint,
                extra = extra,
            ).toString(),
        ),
    )
}
