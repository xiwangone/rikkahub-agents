package me.rerere.rikkahub.data.vault

/**
 * 凭证值清洗器：在**落库前**统一剔除不可见控制符。
 *
 * 为什么必须在写入时统一清洗：凭证值随后会被注入 shell 环境变量、HTTP 请求头、
 * 命令行与日志。不可见控制符（如 \u0001、\u001b 转义序列）会破坏命令解析、
 * 伪造终端输出、污染日志，且肉眼不可见、排查困难。写入时清洗一次，
 * 胜过在每个使用点各自兜底。
 *
 * 规则：仅保留可打印字符 + 制表符 + 换行 + 回车。
 * - 保留 `\n` / `\r`：PEM 私钥与多行 token 依赖换行，剔除会直接破坏密钥内容。
 * - 保留 `\t`：部分配置格式（.netrc / ini 等）以制表符分隔。
 *
 * 纯函数、无 Android 依赖，可直接单测。
 */
internal object CredentialValueSanitizer {

    /** 清洗后的值；结果为空表示"原值只含不可见字符"。 */
    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        var changed = false
        for (ch in raw) {
            if (isAllowed(ch)) {
                sb.append(ch)
            } else {
                changed = true
            }
        }
        return if (changed) sb.toString() else raw
    }

    /** 是否含将被剔除的字符（供上层给出明确报错，而不是静默丢弃）。 */
    fun hasRejectedChars(raw: String): Boolean = raw.any { !isAllowed(it) }

    /** 允许：制表符 / 换行 / 回车 / 可打印字符（>= 0x20 且 != 0x7F）。 */
    private fun isAllowed(ch: Char): Boolean = when (ch) {
        '\t', '\n', '\r' -> true
        else -> ch.code >= 0x20 && ch.code != 0x7F
    }
}
