package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

/**
 * 工具错误信封的**唯一实现**。
 *
 * 背景：此前 8 个模块各自写了一份（`errEnv` / `errEnvelope`，签名还各不相同：有的
 * `(error, detail)`、有的 `(code, detail, extra)`、有的多一个 `recovery`），导致模型看到的
 * 错误形状随工具而变，排查时也要逐处辨认。这里统一为一处，输出形状**沿用既有键名**，
 * 不破坏已经依赖它的模型行为与日志。
 *
 * 输出形状：
 * ```json
 * {"error": "<code>", "detail": "<message>", "recovery": "<下一步建议，可选>"}
 * ```
 * ⚠ 建议字段的键名用**已有的 `recovery`**（不是新造的 `hint`）：仓库多处（`BrowserController`、
 * 生成循环的 loop/tool_not_found 信封、`KeyboardTools`）与单测都已约定这个键名，中途改名会破坏契约。
 * `extra` 里的键会被合并，但**不得覆盖** error / detail / recovery。
 *
 * 约定（与脚本侧的统一报错约定对齐）：
 *  - `code` 用稳定的 snake_case 机器可读串（见下方常量）；
 *  - `detail` 是一句话人读原因；
 *  - `hint` 给出**下一步可执行动作**（没有就不给，不要写空话）。
 */
object ToolErrors {
    const val INVALID_ARGUMENT = "invalid_argument"
    const val INVALID_PATH = "invalid_path"
    const val NOT_FOUND = "not_found"
    const val PERMISSION_DENIED = "permission_denied"
    const val UNSUPPORTED = "unsupported"
    const val TIMEOUT = "timeout"
    const val INTERNAL = "internal_error"

    /** 执行期抛异常时的统一码（原由生成循环内联拼装）。 */
    const val TOOL_FAILED = "tool_failed"

    /** 单次工具执行撞上墙钟预算（未开始 / 执行中被取消）。 */
    const val TOOL_CANCELLED_WALL_CLOCK = "tool_cancelled_wall_clock"

    private val RESERVED = setOf("error", "detail", "recovery")

    fun envelope(
        code: String,
        message: String,
        hint: String? = null,
        extra: Map<String, JsonElement> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("error", code)
        put("detail", message)
        // 键名保持既有的 `recovery`（见文件头注释）。
        if (!hint.isNullOrBlank()) put("recovery", hint)
        for ((k, v) in extra) {
            if (k !in RESERVED) put(k, v)
        }
    }

    fun parts(
        code: String,
        message: String,
        hint: String? = null,
        extra: Map<String, JsonElement> = emptyMap(),
    ): List<UIMessagePart> = listOf(UIMessagePart.Text(envelope(code, message, hint, extra).toString()))

    fun text(
        code: String,
        message: String,
        hint: String? = null,
        extra: Map<String, JsonElement> = emptyMap(),
    ): String = envelope(code, message, hint, extra).toString()
}
