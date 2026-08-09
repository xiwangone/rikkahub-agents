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

        content.lineSequence().forEach { rawLine ->
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

                // export KEY="value"
                line.startsWith("export ") -> {
                    val export = line.removePrefix("export ").trim()
                    val eq = export.indexOf('=')
                    if (eq <= 0) return@forEach
                    val name = export.substring(0, eq).trim()
                    val value = unquote(export.substring(eq + 1).trim())
                    if (name.isEmpty() || value.isEmpty()) return@forEach
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
