package me.rerere.rikkahub.utils

import java.util.Locale

/**
 * 日志脱敏工具：避免在请求日志 / 应用日志中暴露明文凭证（如 `Authorization: Bearer sk-xxx`）。
 *
 * 策略分三层，全部「前 3 后 3 + ***」脱敏（长度不足 6 时完全隐藏）：
 *  1. 结构化字段：请求头（[maskHeader]）与 URL query（[maskUrl]）按**字段名**判定；
 *  2. 已知前缀 token：`sk-…` / `xai-…` / `gsk_…` / `AIza…` / `ghp_…` / `AKIA…` / JWT 等；
 *  3. 键值形态（[maskText]）：自由文本里 `api_key=…` / `"token": "…"` 这类**带敏感键名**的值。
 *
 * 设计约束（踩坑教训）：启发式必须**保守**——只在「键名明确敏感」或「token 前缀明确」时才掩，
 * 不做通用高熵串猜测，避免把正常日志文本误掩成 *** 反而无法排查。
 */
object LogRedactor {
    /** 请求头中的敏感字段：匹配时对值做脱敏 */
    private val SENSITIVE_HEADER_KEYS =
        setOf(
            "authorization",
            "proxy-authorization",
            "x-api-key",
            "api-key",
            "apikey",
            "x-auth-token",
            "x-access-token",
            "token",
            "cookie",
            "set-cookie",
        )

    /** URL query 中的敏感参数名：匹配时对值做脱敏 */
    private val SENSITIVE_QUERY_KEYS =
        setOf(
            "key",
            "api_key",
            "apikey",
            "api-key",
            "x-api-key",
            "token",
            "access_token",
            "auth",
            "authorization",
            "secret",
            "client_secret",
            "password",
            "pwd",
            "sign",
            "sig",
        )

    /** 自由文本中「敏感键名 = 值」形态的键名集合（大小写不敏感，见 [KEYED_SECRET_REGEX]） */
    private const val KEYED_SECRET_KEYS =
        "authorization|proxy-authorization|x-api-key|api[-_]?key|apikey|x-auth-token|x-access-token|" +
            "access[-_]?token|auth[-_]?token|refresh[-_]?token|client[-_]?secret|secret|password|passwd|pwd|" +
            "private[-_]?key|bearer"

    /**
     * 自由文本里 URL query 形态的敏感参数名（`?key=…` / `&token=…`）。
     * 与 [KEYED_SECRET_KEYS] 分开：query 必须带 `?` / `&` 前缀，这样普通散文里的
     * `key=xxx`（文档示例、缓存键名等）不会被误掩。
     */
    private const val QUERY_SECRET_KEYS =
        "key|api_key|apikey|api-key|x-api-key|token|access_token|auth|authorization|secret|client_secret|" +
            "password|pwd|sign|sig"

    /**
     * 已知凭证前缀的 token（覆盖常见厂商）。分组顺序无关，正则本身按前缀区分。
     * 注意：**没有固定前缀**的 key（如自建/国内厂商）不在此列，由 [KEYED_SECRET_REGEX] 兜底。
     */
    private val SECRET_PREFIX_REGEX =
        Regex(
            """(?i)(?<![A-Za-z0-9_-])(?:""" +
                """sk-[A-Za-z0-9_-]{6,}""" +
                """|sk-ant-[A-Za-z0-9_-]{6,}""" +
                """|xai-[A-Za-z0-9_-]{6,}""" +
                """|gsk_[A-Za-z0-9]{6,}""" +
                """|AIza[A-Za-z0-9_-]{10,}""" +
                """|(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{10,}""" +
                """|github_pat_[A-Za-z0-9_]{10,}""" +
                """|(?:AKIA|ASIA)[A-Z0-9]{12,}""" +
                """|hf_[A-Za-z0-9]{10,}""" +
                """|r8_[A-Za-z0-9]{10,}""" +
                """|ya29\.[A-Za-z0-9._-]{10,}""" +
                """|xox[baprs]-[A-Za-z0-9-]{10,}""" +
                """|Bearer\s+[A-Za-z0-9._~+/=-]{8,}""" +
                """)""",
        )

    /** JWT：三段 base64url，整体足够长才判定（避免误掩普通文本） */
    private val JWT_REGEX =
        Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")

    /**
     * 自由文本中「敏感键名 + 分隔符 + 值」形态：`api_key=xxx`、`"token": "xxx"`、`password: xxx`。
     * 值要求 ≥8 位且不含空白，避免把普通短词误掩。
     */
    private val KEYED_SECRET_REGEX =
        Regex(
            """(?i)(\b(?:$KEYED_SECRET_KEYS)\b["']?\s*[:=]\s*["']?)([A-Za-z0-9._~+/=:@-]{8,})""",
        )

    /** URL query 形态（`?key=…` / `&token=…`）的值脱敏，保留参数名。 */
    private val QUERY_SECRET_REGEX =
        Regex(
            """(?i)([?&](?:$QUERY_SECRET_KEYS)=)([^&\s"'<>]{8,})""",
        )

    private val BEARER_PREFIX_REGEX = Regex("""(?i)^Bearer\s+""")

    /**
     * 对敏感值脱敏：前 3 后 3 + ***；长度不足 6 时完全隐藏。
     */
    fun maskSecret(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length <= 6) return "***"
        return trimmed.take(3) + "***" + trimmed.takeLast(3)
    }

    /**
     * 判断请求头是否敏感（大小写不敏感）。
     */
    fun isSensitiveHeader(key: String): Boolean = key.lowercase(Locale.getDefault()) in SENSITIVE_HEADER_KEYS

    /**
     * 单个请求头脱敏：敏感字段的值替换为脱敏后的值。
     */
    fun maskHeader(
        key: String,
        value: String,
    ): String = if (isSensitiveHeader(key)) maskSecret(value) else value

    /**
     * 对请求头 map 脱敏。
     */
    fun maskHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (key, value) -> maskHeader(key, value) }

    /**
     * 对 URL 脱敏：query 中敏感参数的值替换为 `***`。
     */
    fun maskUrl(url: String): String {
        val queryIndex = url.indexOf('?')
        if (queryIndex < 0) return url
        val base = url.substring(0, queryIndex)
        val query = url.substring(queryIndex + 1)
        val maskedQuery =
            query.split('&').joinToString("&") { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) {
                    pair
                } else {
                    val key = pair.substring(0, eq)
                    if (key.lowercase(Locale.getDefault()) in SENSITIVE_QUERY_KEYS) {
                        "$key=***"
                    } else {
                        pair
                    }
                }
            }
        return "$base?$maskedQuery"
    }

    /**
     * 对自由文本脱敏：依次替换
     *  1. 已知前缀 token（`sk-…` / `AIza…` / `Bearer …` 等）
     *  2. JWT
     *  3. 「敏感键名 = 值」形态的值（覆盖**无固定前缀**的 key）
     * 不改变文本结构，可安全用于 JSON body / 崩溃堆栈 / 日志导出。
     */
    fun maskText(text: String): String {
        var out = SECRET_PREFIX_REGEX.replace(text) { match ->
            val raw = match.value
            if (BEARER_PREFIX_REGEX.containsMatchIn(raw)) {
                "Bearer " + maskSecret(raw.substringAfter(' '))
            } else {
                maskSecret(raw)
            }
        }
        out = JWT_REGEX.replace(out) { match -> maskSecret(match.value) }
        out =
            QUERY_SECRET_REGEX.replace(out) { match ->
                // group(1) = `?key=`（保留），group(2) = 值
                match.groupValues[1] + maskSecret(match.groupValues[2])
            }
        out =
            KEYED_SECRET_REGEX.replace(out) { match ->
                // group(1) = 键名 + 分隔符（保留原样），group(2) = 值
                match.groupValues[1] + maskSecret(match.groupValues[2])
            }
        return out
    }
}
