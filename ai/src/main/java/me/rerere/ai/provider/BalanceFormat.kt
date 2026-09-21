package me.rerere.ai.provider

import kotlinx.serialization.json.JsonObject
import java.util.Locale
import me.rerere.common.http.getByKey

/**
 * 把余额接口的响应格式化成展示文本。
 *
 * - [BalanceOption.fields] 非空：逐项求值，按 `标签 单位值后缀` 组装，用 ` · ` 连接
 *   （例：`月度余额 $58.48 · 5h 窗口 $0.42/14`）；
 * - 否则回退到单字段 [BalanceOption.resultPath]（旧配置）：数值保留两位小数。
 *
 * 求值走 common 的 JSON 表达式引擎（支持 `a.b`、`a[0]` 与算术）；单项求值失败只影响该项。
 */
internal fun formatBalance(body: JsonObject, option: BalanceOption): String {
    val fields = option.fields.filter { it.path.isNotBlank() }
    if (fields.isNotEmpty()) {
        return fields.joinToString(" · ") { field ->
            val raw = runCatching { body.getByKey(field.path) }.getOrDefault("")
            val shown = raw.toDoubleOrNull()?.let { "%.2f".format(Locale.US, it) } ?: raw
            listOf(field.label, "${field.unit}$shown${field.suffix}")
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
    }
    if (option.resultPath.isBlank()) return ""
    val value = runCatching { body.getByKey(option.resultPath) }.getOrDefault("")
    return value.toFloatOrNull()?.let { "%.2f".format(Locale.US, it) } ?: value
}
