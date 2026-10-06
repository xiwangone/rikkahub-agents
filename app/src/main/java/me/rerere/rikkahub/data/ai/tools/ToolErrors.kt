package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

/**
 * 工具错误信封的**唯一实现**（v2，JSON-RPC 2.0 对齐）。
 *
 * 形状（对齐 JSON-RPC 2.0 §5.1 Error Object，https://www.jsonrpc.org/specification）：
 * ```json
 * {"code": -32602, "message": "x and y are required", "data": {"error": "invalid_argument", "recovery": "fix_param"}}
 * ```
 * - `code`：整数。JSON-RPC 标准码（-32700/-32600/-32601/-32602/-32603）与 MCP 扩展码
 *   （-32000/-32002/-32800/-32801，见 MCP 2024-11-05 规范）。映射不到标准码的归入
 *   -32000（server error，规范保留给实现自定义），细粒度业务码放在 `data.error`。
 * - `message`：一句话人类可读原因。
 * - `data.error`：细粒度业务码（沿用本仓库既有 snake_case 词汇）。
 * - `data.recovery`：`retry` / `fix_param` / `abort`，语义对齐 gRPC status code
 *   （https://grpc.io/docs/guides/status-codes/）：
 *   - `retry` ← UNAVAILABLE / RESOURCE_EXHAUSTED / DEADLINE_EXCEEDED：瞬时故障，可退避重试；
 *   - `fix_param` ← INVALID_ARGUMENT / NOT_FOUND / OUT_OF_RANGE / FAILED_PRECONDITION：
 *     调用方可修复（改参、换标识、先修环境），修好再调，不要无脑重试；
 *   - `abort` ← PERMISSION_DENIED / UNIMPLEMENTED / INTERNAL / CANCELLED / DATA_LOSS /
 *     UNAUTHENTICATED：重试无意义，放弃并上报。
 * - `data.hint`：可选，下一步可执行动作（没有就不给）。
 * - `data` 其余键为透传上下文（如 `current`、`available`），不得覆盖保留键。
 *
 * 与 MCP 的对应关系：MCP 规定"工具执行失败"是 `isError: true` 的 result 而非协议层
 * JSON-RPC error（协议 error 调用方无法恢复，工具 error 模型可以自纠）。
 * 本仓库工具为进程内调用、无 JSON-RPC 传输层，因此直接以 JSON-RPC error object
 * 形状作为工具结果返回给模型——模型视角下它就是一次"可自纠的失败"。
 *
 * [categoryFor] / [recoveryFor] 是 snake_case 业务码到（code, recovery）的**唯一映射表**；
 * 迁移脚本从本文件解析该表，保证 Kotlin 与迁移逻辑同源。
 *
 * v2 变更：v1 扁平形状 `{"error","detail","recovery"}` 已整体迁移到本形状。
 *
 * 参考：
 * - JSON-RPC 2.0 Specification §5.1：https://www.jsonrpc.org/specification
 * - MCP 规范（错误码）：https://spec.modelcontextprotocol.io
 * - gRPC Status Codes：https://grpc.io/docs/guides/status-codes/
 * - LangChain 动态工具选择：https://langchain-ai.github.io/langgraph/how-tos/many-tools/
 * - LangChain4j Tool Search：https://docs.langchain4j.dev/tutorials/tools
 */
object ToolErrors {
    // ---- JSON-RPC 2.0 §5.1 标准码 ----
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // ---- MCP 扩展码 ----
    /** 实现自定义错误的通用桶：映射不到标准码的归入此处，细粒度见 data.error。 */
    const val SERVER_ERROR = -32000
    /** MCP：请求的资源不存在。 */
    const val RESOURCE_NOT_FOUND = -32002
    /** MCP：请求被取消。 */
    const val REQUEST_CANCELLED = -32800
    /** MCP：内容超限。 */
    const val CONTENT_TOO_LARGE = -32801

    // ---- data.error 细粒度业务码（沿用既有 snake_case 词汇） ----
    const val INVALID_ARGUMENT = "invalid_argument"
    const val INVALID_PATH = "invalid_path"
    const val NOT_FOUND = "not_found"
    const val PERMISSION_DENIED = "permission_denied"
    const val UNSUPPORTED = "unsupported"
    const val TIMEOUT = "timeout"
    const val INTERNAL = "internal_error"

    /** 执行期抛异常时的统一码。 */
    const val TOOL_FAILED = "tool_failed"

    /** 单次工具执行撞上墙钟预算（未开始 / 执行中被取消）。 */
    const val TOOL_CANCELLED_WALL_CLOCK = "tool_cancelled_wall_clock"

    // ---- data.recovery（gRPC 对齐，见文件头） ----
    const val RECOVERY_RETRY = "retry"
    const val RECOVERY_FIX_PARAM = "fix_param"
    const val RECOVERY_ABORT = "abort"

    /**
     * 业务码 → JSON-RPC 分类码。未列出的默认 [SERVER_ERROR]。
     * 注意：本表是 [categoryFor] 的唯一数据源，迁移脚本从此处解析。
     */
    // CATEGORY_MAP_BEGIN
    private val CATEGORY_MAP: Map<String, Int> = mapOf(
        // -- INVALID_PARAMS：参数校验失败 --
        "invalid_argument" to INVALID_PARAMS,
        "invalid_value" to INVALID_PARAMS,
        "invalid_namespace" to INVALID_PARAMS,
        "invalid_package" to INVALID_PARAMS,
        "invalid_mode" to INVALID_PARAMS,
        "invalid_key" to INVALID_PARAMS,
        "invalid_count" to INVALID_PARAMS,
        "invalid_pos" to INVALID_PARAMS,
        "invalid_range" to INVALID_PARAMS,
        "INVALID_CHART" to INVALID_PARAMS,
        "INVALID_RANGE" to INVALID_PARAMS,
        "INVALID_TIME" to INVALID_PARAMS,
        "missing_path" to INVALID_PARAMS,
        "missing_required_arg" to INVALID_PARAMS,
        "missing_text" to INVALID_PARAMS,
        "missing_count" to INVALID_PARAMS,
        "missing_pos" to INVALID_PARAMS,
        "missing_start" to INVALID_PARAMS,
        "missing_end" to INVALID_PARAMS,
        "nth_out_of_range" to INVALID_PARAMS,
        "bad_url" to INVALID_PARAMS,
        "bad_request" to INVALID_PARAMS,
        "scheme_not_allowed" to INVALID_PARAMS,
        "backup_password_missing" to INVALID_PARAMS,
        "js_parse_failed" to INVALID_PARAMS,
        "not_a_directory" to INVALID_PARAMS,
        "unsafe_zip_entry" to INVALID_PARAMS,
        "invalid_zip" to INVALID_PARAMS,
        // -- METHOD_NOT_FOUND：未知工具/方法 --
        "unknown_tool" to METHOD_NOT_FOUND,
        "no_handler" to METHOD_NOT_FOUND,
        "bad_method" to METHOD_NOT_FOUND,
        // -- PARSE_ERROR：参数 JSON 解析失败 --
        "invalid_tool_args" to PARSE_ERROR,
        // -- INTERNAL_ERROR：执行期异常 --
        "internal_error" to INTERNAL_ERROR,
        "tool_failed" to INTERNAL_ERROR,
        "tool_exception" to INTERNAL_ERROR,
        "exec_failed" to INTERNAL_ERROR,
        "shizuku_call_failed" to INTERNAL_ERROR,
        "shizuku_bad_response" to INTERNAL_ERROR,
        "termux_run_failed" to INTERNAL_ERROR,
        "send_failed" to INTERNAL_ERROR,
        "launch_failed" to INTERNAL_ERROR,
        "open_failed" to INTERNAL_ERROR,
        "write_failed" to INTERNAL_ERROR,
        "dispatch_failed" to INTERNAL_ERROR,
        "check_failed" to INTERNAL_ERROR,
        // -- RESOURCE_NOT_FOUND：资源不存在 --
        "not_found" to RESOURCE_NOT_FOUND,
        "no_match" to RESOURCE_NOT_FOUND,
        "no_active_window" to RESOURCE_NOT_FOUND,
        "no_clickable_ancestor" to RESOURCE_NOT_FOUND,
        "wrong_foreground_app" to RESOURCE_NOT_FOUND,
        "conversation_not_found" to RESOURCE_NOT_FOUND,
        "package_not_found" to RESOURCE_NOT_FOUND,
        "skill_not_found" to RESOURCE_NOT_FOUND,
        "preset_not_found" to RESOURCE_NOT_FOUND,
        "no_session" to RESOURCE_NOT_FOUND,
        "no_launch_intent" to RESOURCE_NOT_FOUND,
        "source_unreadable" to RESOURCE_NOT_FOUND,
        "parent_missing" to RESOURCE_NOT_FOUND,
        // -- REQUEST_CANCELLED：取消 --
        "user_cancelled" to REQUEST_CANCELLED,
        "tool_cancelled_wall_clock" to REQUEST_CANCELLED,
        // -- CONTENT_TOO_LARGE：内容超限 --
        "file_too_large_for_telegram_bot" to CONTENT_TOO_LARGE,
        "skill_file_too_large" to CONTENT_TOO_LARGE,
    )
    // CATEGORY_MAP_END

    /**
     * 业务码 → recovery。未列出的默认 [RECOVERY_FIX_PARAM]
     * （大多数工具错误调用方可修复；瞬时故障与权限/取消类需显式标注）。
     * 注意：本表是 [recoveryFor] 的唯一数据源，迁移脚本从此处解析。
     */
    // RECOVERY_MAP_BEGIN
    private val RECOVERY_MAP: Map<String, String> = mapOf(
        // -- retry：瞬时故障（gRPC UNAVAILABLE / RESOURCE_EXHAUSTED / DEADLINE_EXCEEDED） --
        "timeout" to RECOVERY_RETRY,
        "command_timeout" to RECOVERY_RETRY,
        "tool_timeout" to RECOVERY_RETRY,
        "browser_task_timeout" to RECOVERY_RETRY,
        "network_error" to RECOVERY_RETRY,
        "connect_failed" to RECOVERY_RETRY,
        "tcp_unreachable" to RECOVERY_RETRY,
        "sftp_get_failed" to RECOVERY_RETRY,
        "sftp_put_failed" to RECOVERY_RETRY,
        "browser_busy" to RECOVERY_RETRY,
        "another_recording_in_progress" to RECOVERY_RETRY,
        "screenshot_unavailable" to RECOVERY_RETRY,
        "launch_did_not_focus" to RECOVERY_RETRY,
        "browser_launch_failed" to RECOVERY_RETRY,
        "tool_schema_not_loaded" to RECOVERY_RETRY,
        // -- abort：重试无意义（gRPC PERMISSION_DENIED / UNIMPLEMENTED / INTERNAL /
        // -- CANCELLED / DATA_LOSS / UNAUTHENTICATED） --
        "permission_denied" to RECOVERY_ABORT,
        "NO_PERMISSION" to RECOVERY_ABORT,
        "shizuku_permission_denied" to RECOVERY_ABORT,
        "termux_permission_denied" to RECOVERY_ABORT,
        "termux_permission_not_granted" to RECOVERY_ABORT,
        "key_not_whitelisted" to RECOVERY_ABORT,
        "op_not_whitelisted" to RECOVERY_ABORT,
        "directory_not_granted" to RECOVERY_ABORT,
        "auth_failed" to RECOVERY_ABORT,
        "token_invalid" to RECOVERY_ABORT,
        "host_key_changed" to RECOVERY_ABORT,
        "saved_host_has_no_usable_credentials" to RECOVERY_ABORT,
        "user_cancelled" to RECOVERY_ABORT,
        "tool_cancelled_wall_clock" to RECOVERY_ABORT,
        "loop_detected" to RECOVERY_ABORT,
        "unknown_tool" to RECOVERY_ABORT,
        "no_handler" to RECOVERY_ABORT,
        "bad_method" to RECOVERY_ABORT,
        "internal_error" to RECOVERY_ABORT,
        "tool_failed" to RECOVERY_ABORT,
        "tool_exception" to RECOVERY_ABORT,
        "exec_failed" to RECOVERY_ABORT,
        "shizuku_call_failed" to RECOVERY_ABORT,
        "shizuku_bad_response" to RECOVERY_ABORT,
        "termux_run_failed" to RECOVERY_ABORT,
        "send_failed" to RECOVERY_ABORT,
        "launch_failed" to RECOVERY_ABORT,
        "open_failed" to RECOVERY_ABORT,
        "write_failed" to RECOVERY_ABORT,
        "dispatch_failed" to RECOVERY_ABORT,
        "check_failed" to RECOVERY_ABORT,
        "credential_decrypt_failed" to RECOVERY_ABORT,
        "activity_not_exported" to RECOVERY_ABORT,
        "listing_not_supported" to RECOVERY_ABORT,
        "no_biometrics_enrolled" to RECOVERY_ABORT,
        "ask_user_unavailable" to RECOVERY_ABORT,
        "browser_session_lost" to RECOVERY_ABORT,
        "hardware_unavailable" to RECOVERY_ABORT,
        "unsafe_zip_entry" to RECOVERY_ABORT,
        // fix_param 其余默认（INVALID_ARGUMENT / NOT_FOUND / FAILED_PRECONDITION 类）
    )
    // RECOVERY_MAP_END

    /** 业务码 → JSON-RPC 分类码，未映射的归入 [SERVER_ERROR]。 */
    fun categoryFor(error: String): Int = CATEGORY_MAP[error] ?: SERVER_ERROR

    /** 业务码 → recovery，未映射的默认 [RECOVERY_FIX_PARAM]。 */
    fun recoveryFor(error: String): String = RECOVERY_MAP[error] ?: RECOVERY_FIX_PARAM

    /**
     * 业务码 → 人类可读的一句话（首字母大写的空格分隔形式）。
     * 调用方没有自定义 message 时的兜底；有具体上下文时应优先传自定义 message。
     */
    fun messageFor(error: String): String =
        error.replace('_', ' ').replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }

    /**
     * 从人类可读的错误消息反推业务码（关键词启发式）。
     * 用于存量 `*Err(msg: String)` helper 的迁移；新代码应直接传明确的业务码。
     */
    fun classifyMessage(message: String): String {
        val low = message.lowercase()
        return when {
            "not found" in low || "no such" in low -> "not_found"
            "denied" in low || "not granted" in low || "permission" in low -> "permission_denied"
            "timeout" in low || "timed out" in low -> "timeout"
            "unavailable" in low -> "service_unavailable"
            "required" in low || "must" in low -> "invalid_argument"
            "invalid" in low -> "invalid_argument"
            "failed" in low -> "tool_failed"
            else -> "invalid_argument"
        }
    }

    private val RESERVED = setOf("error", "recovery", "hint")

    /**
     * 显式 code 版：调用方明确知道 JSON-RPC 分类码时使用。
     * 输出 `{"code","message","data":{"error","recovery","hint",...extra}}`。
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
                if (k !in RESERVED) put(k, v)
            }
        })
    }

    /**
     * 业务码版：code/recovery 从 [categoryFor]/[recoveryFor] 推导。
     * 适用于 275 处存量迁移与未来新增工具——调用方只需给业务码与人类可读 message。
     */
    fun envelopeFor(
        error: String,
        message: String,
        recovery: String? = null,
        hint: String? = null,
        extra: Map<String, JsonElement> = emptyMap(),
    ): JsonObject = envelope(
        code = categoryFor(error),
        message = message,
        error = error,
        recovery = recovery ?: recoveryFor(error),
        hint = hint,
        extra = extra,
    )

    fun parts(
        code: Int,
        message: String,
        error: String? = null,
        recovery: String? = null,
        hint: String? = null,
        extra: Map<String, JsonElement> = emptyMap(),
    ): List<UIMessagePart> =
        listOf(UIMessagePart.Text(envelope(code, message, error, recovery, hint, extra).toString()))

    /** 便捷：把「String / Boolean / Number / JsonElement / null」裸值对转成 `Map<String, JsonElement>`（省去逐处写 JsonPrimitive）。 */
    fun extraOf(vararg pairs: Pair<String, Any?>): Map<String, JsonElement> =
        pairs.associate { (k, v) ->
            k to when (v) {
                null -> JsonNull
                is JsonElement -> v
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        }

    fun partsFor(
        error: String,
        message: String,
        recovery: String? = null,
        hint: String? = null,
        extra: Map<String, JsonElement> = emptyMap(),
    ): List<UIMessagePart> =
        listOf(UIMessagePart.Text(envelopeFor(error, message, recovery, hint, extra).toString()))

    fun text(
        code: Int,
        message: String,
        error: String? = null,
        recovery: String? = null,
        hint: String? = null,
        extra: Map<String, JsonElement> = emptyMap(),
    ): String = envelope(code, message, error, recovery, hint, extra).toString()

    fun textFor(
        error: String,
        message: String,
        recovery: String? = null,
        hint: String? = null,
        extra: Map<String, JsonElement> = emptyMap(),
    ): String = envelopeFor(error, message, recovery, hint, extra).toString()
}
