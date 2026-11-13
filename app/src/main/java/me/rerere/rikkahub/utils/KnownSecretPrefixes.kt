package me.rerere.rikkahub.utils

/**
 * 已知凭证前缀正则表：日志脱敏（LogRedactor）与工具输出掩码（SecretMasker）共用的唯一口径。
 *
 * 两处消费方掩法不同——日志留前 3 后 3 便于排查，工具输出全掩防进模型上下文——
 * 但“什么算凭证”的判定必须一致。此前两边阈值不同（日志 sk- {6,}、工具输出 sk- {20,}），
 * 一段 10 字符的 sk- token 在日志里被掩、在工具输出里原样进了模型上下文。
 * 新增厂商前缀只改这里；阈值一律取两边旧口径中更敏感（更小）的一档。
 */
object KnownSecretPrefixes {
    /** 各条目的原始正则片段（不含 `(?i)` 与前置边界，由消费方按需组合）。 */
    val patterns: List<String> = listOf(
        """sk-[A-Za-z0-9_-]{6,}""",
        """sk-ant-[A-Za-z0-9_-]{6,}""",
        """xai-[A-Za-z0-9_-]{6,}""",
        """gsk_[A-Za-z0-9]{6,}""",
        """AIza[A-Za-z0-9_-]{10,}""",
        """(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{10,}""",
        """github_pat_[A-Za-z0-9_]{10,}""",
        """(?:AKIA|ASIA)[A-Z0-9]{12,}""",
        """hf_[A-Za-z0-9]{10,}""",
        """r8_[A-Za-z0-9]{10,}""",
        """tvly-[A-Za-z0-9_-]{6,}""",
        """ya29\.[A-Za-z0-9._-]{10,}""",
        """xox[baprs]-[A-Za-z0-9-]{10,}""",
        """Bearer\s+[A-Za-z0-9._~+/=-]{8,}""",
        // JWT：三段 base64url，整体足够长才判定（避免误掩普通文本）
        """eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""",
    )

    /** 消费方共用的组合形态：大小写不敏感，且要求前缀前一位不是词字符（避免把长词里的片段当凭证）。 */
    fun combinedRegex(): Regex =
        Regex(
            """(?i)(?<![A-Za-z0-9_-])(?:${patterns.joinToString("|")})""",
        )

    /** 逐条形态：供按条替换的消费方使用，与 [combinedRegex] 同一匹配口径。 */
    fun patternRegexes(): List<Regex> =
        patterns.map { Regex("""(?i)(?<![A-Za-z0-9_-])$it""") }
}
