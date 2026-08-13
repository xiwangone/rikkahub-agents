package me.rerere.rikkahub.data.vault

/**
 * 解析 load-creds.sh 风格的凭证文件：
 *   export KEY="value"          # 描述
 *   # ============ 分组名 ============
 *
 * 解析出 (name, value, description, group) 列表，供导入。
 */
object CredentialImporter {

    /** 分组注释行 → 组名映射（load-creds.sh 中文化注释） */
    private val groupByKeyword = listOf(
        "代码托管" to "Git",
        "AI 模型" to "AI",
        "ECS" to "ECS",
        "服务器" to "ECS",
        "MCP" to "MCP",
        "消息通知" to "Notification",
        "通知" to "Notification",
        "Reasonix" to "Reasonix",
        "网络" to "Network",
        "SSH" to "SSH",
    )

    data class ParsedEntry(
        val name: String,
        val value: String,
        val description: String,
        val group: String,
    )

    fun parse(content: String): List<ParsedEntry> {
        val result = mutableListOf<ParsedEntry>()
        var currentGroup = "Other"
        var pendingComment: String? = null
        val lines = content.lineSequence().iterator()
        while (lines.hasNext()) {
            val rawLine = lines.next()
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                // 分组注释：如 "# ============ 代码托管 / Git ============"
                line.startsWith("#") && line.contains("====") -> {
                    val keyword = line.removePrefix("#").removePrefix("=").trim()
                    currentGroup = detectGroup(keyword)
                    pendingComment = null
                }

                // 普通注释：作为下一条 export 的描述
                line.startsWith("#") -> {
                    val comment = line.removePrefix("#").trim()
                    if (comment.isNotBlank() && !comment.contains("警示") && !comment.contains("⚠")) {
                        pendingComment = comment
                    }
                }

                // export KEY="value"（支持多行值：引号未闭合时收集后续行——私钥 PEM 等）
                line.startsWith("export ") -> {
                    val export = line.removePrefix("export ").trim()
                    val eq = export.indexOf('=')
                    if (eq <= 0) continue
                    val name = export.substring(0, eq).trim()
                    var rawValue = export.substring(eq + 1).trim()
                    if (rawValue.startsWith("\"") && !isQuotedClosed(rawValue)) {
                        val sb = StringBuilder(rawValue)
                        while (lines.hasNext()) {
                            sb.append('\n').append(lines.next())
                            if (isQuotedClosed(sb.toString())) break
                        }
                        rawValue = sb.toString()
                    }
                    val value = unquote(rawValue)
                    if (name.isEmpty() || value.isEmpty()) continue
                    result += ParsedEntry(
                        name = name,
                        value = value,
                        description = pendingComment ?: "",
                        group = currentGroup,
                    )
                    pendingComment = null
                }
            }
        }
        return result
    }

    /** 判断双引号值是否已闭合（值以未转义的 " 结尾）。 */
    private fun isQuotedClosed(s: String): Boolean {
        val trimmed = s.trimEnd()
        return trimmed.endsWith("\"") && !trimmed.endsWith("\\\"")
    }

    /** 从分组注释文本识别组名 */
    private fun detectGroup(raw: String): String {
        for ((keyword, group) in groupByKeyword) {
            if (raw.contains(keyword)) return group
        }
        return "Other"
    }

    /** 去掉首尾引号（支持 "..." 和 '...'） */
    private fun unquote(s: String): String {
        if (s.length >= 2 && ((s.first() == '"' && s.last() == '"') || (s.first() == '\'' && s.last() == '\''))) {
            return s.substring(1, s.length - 1)
        }
        return s
    }
}
