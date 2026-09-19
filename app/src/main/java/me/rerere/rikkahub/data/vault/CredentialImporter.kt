package me.rerere.rikkahub.data.vault

/**
 * 凭证文件导入：格式识别 + 各格式解析分发。
 *
 * 支持与「导出」**一一对称**的四种格式，识别顺序为「内容特征优先、扩展名兜底」：
 * - [Format.VAULT]     加密包 .vault（需口令）—— 解析在 [VaultExporter.importEntries]（与导出 [VaultExporter.exportWithGroups] 对称）
 * - [Format.CSV]       明文 CSV            —— 解析在 [VaultFormats.fromCsv]（与导出 [VaultFormats.toCsv] 对称）
 * - [Format.BITWARDEN] 明文 Bitwarden JSON —— 解析在 [VaultFormats.fromBitwarden]（与导出 [VaultFormats.toBitwarden] 对称）
 * - [Format.LOADCREDS] load-creds.sh       —— 解析在本文件 [parse]（与导出 [VaultExporter.toLoadCreds] 对称）
 *
 * 各格式统一解析为 [ParsedEntry]，交由 [CredentialVaultRepository.importEntries] 逐条 upsert，
 * 与既有 load-creds.sh 导入保持同一策略（同名条目：值留空保留原值、值不同则覆盖并回报）。
 *
 * load-creds.sh 文本格式：
 *   export KEY="value"          # 描述
 *   # ============ 分组名 ============
 */
object CredentialImporter {

    /** 导入格式（与 [VaultFormats] 的 FORMAT_* 导出格式一一对应）。 */
    enum class Format {
        VAULT,
        CSV,
        BITWARDEN,
        LOADCREDS,
    }

    /** 内容特征不足时的扩展名兜底映射。 */
    private val formatByExtension = mapOf(
        "vault" to Format.VAULT,
        "csv" to Format.CSV,
        "json" to Format.BITWARDEN,
        "sh" to Format.LOADCREDS,
        "bash" to Format.LOADCREDS,
    )

    /** load-creds.sh 特征：export 赋值行（跨行匹配）。 */
    private val exportLineRegex = Regex("""(?m)^\s*export\s+[A-Za-z_][A-Za-z0-9_]*\s*=""")

    /**
     * 识别导入格式：内容特征优先（改文件名不改变内容，故比扩展名可靠），扩展名兜底。
     *
     * 无法识别时返回 null——调用方据此给出明确错误，而不是当成空内容静默导入 0 条。
     */
    fun detectFormat(fileName: String?, content: String): Format? {
        val text = content.trimStart()
        // 1) JSON 类：靠格式标识区分加密包与 Bitwarden
        if (text.startsWith("{")) {
            if (text.contains("\"rikkahub-vault\"")) return Format.VAULT
            if (text.contains("\"items\"")) return Format.BITWARDEN
        }
        // 2) shell 风格（load-creds.sh）
        if (exportLineRegex.containsMatchIn(content) || text.startsWith("#!")) return Format.LOADCREDS
        // 3) CSV 特征：首行为表头（首列 name）
        if (looksLikeCsv(content)) return Format.CSV
        // 4) 扩展名兜底（含无表头的 CSV 等）
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return formatByExtension[ext]
    }

    /** CSV 特征：首个非空行是表头（首列 name 且存在列分隔符）。表头由 [VaultFormats.toCsv] 写出。 */
    private fun looksLikeCsv(content: String): Boolean {
        val firstLine = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val firstCol = firstLine?.substringBefore(',')?.trim()?.trim('"')
        return firstLine != null && firstLine.contains(',') && firstCol.equals("name", ignoreCase = true)
    }

    /**
     * 按格式解析为统一的导入条目。
     *
     * 各格式的解析实现与其导出实现在同一文件或相邻文件，改动需成对同步（见各分支注释）。
     *
     * @param password 仅 [Format.VAULT] 需要；口令错或包被篡改时由 [VaultExporter.importEntries] 抛异常。
     */
    fun parseAsEntries(
        content: String,
        format: Format,
        password: String = "",
    ): List<ParsedEntry> = when (format) {
        // 与导出 VaultExporter.toLoadCreds 对称
        Format.LOADCREDS -> parse(content)
        // 与导出 VaultExporter.exportWithGroups 对称
        Format.VAULT -> VaultExporter.importEntries(content, password)
        // 与导出 VaultFormats.toCsv 对称
        Format.CSV -> VaultFormats.fromCsv(content).map { it.toParsedEntry() }
        // 与导出 VaultFormats.toBitwarden 对称
        Format.BITWARDEN -> VaultFormats.fromBitwarden(content).map { it.toParsedEntry() }
    }

    /** 分组注释行 → 组名映射（load-creds.sh 手写文件里的中文注释） */
    private val groupByKeyword = listOf(
        "代码托管" to "Git",
        "AI 模型" to "AI",
        "ECS" to "ECS",
        "服务器" to "ECS",
        "MCP" to "MCP",
        "消息通知" to "Notification",
        "通知" to "Notification",
        "Backend" to "Backend",
        "网络" to "Network",
        "SSH" to "SSH",
    )

    /** 已知分组 id（导出直接写 id，导入需按 id 精确还原）。 */
    private val knownGroups =
        setOf("Git", "AI", "ECS", "MCP", "Notification", "Backend", "Network", "SSH", "Other")

    data class ParsedEntry(
        val name: String,
        val value: String,
        val description: String,
        val group: String,
        val publicKey: String = "",
        /** 类型（缺省为空 = 未分类，写入时由推断补全） */
        val type: String = "",
    )

    /**
     * 解析 load-creds.sh 风格的凭证文件（与导出 [VaultExporter.toLoadCreds] 完全对称）：
     *   export KEY="value"          # 描述
     *   # ============ 分组名 ============
     *
     * 解析出 (name, value, description, group, publicKey, type) 列表，供导入。
     */
    fun parse(content: String): List<ParsedEntry> {
        val result = mutableListOf<ParsedEntry>()
        var currentGroup = "Other"
        var pendingComment: String? = null
        var pendingPublicKey: String? = null
        var pendingType: String? = null
        val lines = content.lineSequence().iterator()
        while (lines.hasNext()) {
            val rawLine = lines.next()
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                // 分组注释：如 "# ============ 代码托管 / Git ============"（去掉 # 与两侧的 = 装饰）
                line.startsWith("#") && line.contains("====") -> {
                    val keyword = line.removePrefix("#").trim().trim('=').trim()
                    currentGroup = detectGroup(keyword)
                    pendingComment = null
                }

                // 公钥注释：如 "# SSH公钥: ssh-ed25519 AAAA... comment"（公钥公开，非机密）
                line.startsWith("#") && (line.contains("SSH公钥") || line.contains("公钥:") || line.startsWith("# pub:")) -> {
                    val pub = line.substringAfter(':').trim()
                    if (pub.startsWith("ssh-") || pub.startsWith("ecdsa-") || pub.startsWith("sk-")) {
                        pendingPublicKey = pub
                    }
                }

                // 类型注释：如 "# type: api-key"（导出时写入，用于往返保留类型；不认识的值忽略）
                line.startsWith("#") && line.removePrefix("#").trim().startsWith("type:") -> {
                    val t = line.substringAfter(':').trim()
                    if (CredentialType.isValid(t)) pendingType = t
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
                        publicKey = pendingPublicKey ?: "",
                        type = pendingType ?: "",
                    )
                    pendingComment = null
                    pendingPublicKey = null
                    pendingType = null
                }
            }
        }
        return result
    }

    /**
     * 判断双引号值是否已闭合（结尾的 " **未被转义**）。
     *
     * 判定要看引号前面**连续反斜杠的奇偶**：奇数个才算转义。只看最后两个字符会把
     * `"...trailing\\"`（值是「以反斜杠结尾」，导出后行尾为 `\\"`）误判成未闭合，
     * 于是继续搜集后续行、把结构吃坏。
     */
    private fun isQuotedClosed(s: String): Boolean {
        val trimmed = s.trimEnd()
        if (!trimmed.endsWith("\"")) return false
        var backslashes = 0
        var i = trimmed.length - 2
        while (i >= 0 && trimmed[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 0
    }

    /**
     * 从分组注释文本识别组名（与导出 [VaultExporter.toLoadCreds] 对称）：
     * 1. 导出写的是分组 id（`# ==== Git ====`）→ 按 id 精确匹配；
     * 2. 手写文件常用中文关键词（`# ==== 代码托管 / Git ====`）→ 关键词映射；
     * 3. 其余无中文的取值按原文保留（自定义分组不至于被降级成 Other），
     *    含中文的说明性文本仍回落到 Other。
     */
    private fun detectGroup(raw: String): String {
        knownGroups.firstOrNull { it.equals(raw, ignoreCase = true) }?.let { return it }
        for ((keyword, group) in groupByKeyword) {
            if (raw.contains(keyword)) return group
        }
        // 无 CJK 字符时视为分组标识符原样保留；CJK 说明性文本（无关键词命中）仍归入 Other
        val looksLikeGroupId = raw.isNotBlank() && raw.none { it.code > 0x2E80 }
        return if (looksLikeGroupId) raw else "Other"
    }

    /**
     * 去掉首尾引号（支持 `"..."` 和 `'...'`）。
     *
     * 双引号值还要**反转导出时的 shell 转义**（[VaultExporter.toLoadCreds] 会把
     * `\` `"` `$` 反引号写成带反斜杠的形式），否则「导出 → 导入」对含这些字符的值
     * 就不对称——用户会看到值里平白多出反斜杠。单引号是 shell 里唯一的无转义引号，原样返回。
     */
    private fun unquote(s: String): String {
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') {
            return unescapeShellDoubleQuoted(s.substring(1, s.length - 1))
        }
        if (s.length >= 2 && s.first() == '\'' && s.last() == '\'') {
            return s.substring(1, s.length - 1)
        }
        return s
    }

    /** 双引号内被反斜杠转义后仍为字面量的字符（与 [VaultExporter.toLoadCreds] 的转义集合一致）。 */
    private val escapableInDoubleQuotes = setOf('\\', '"', '$', '`')

    /** 反转双引号内的 shell 转义：`\\` → `\`、`\"` → `"`、`\$` → `$`、反斜杠+反引号 → 反引号。 */
    private fun unescapeShellDoubleQuoted(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val next = s.getOrNull(i + 1)
            if (c == '\\' && next != null && next in escapableInDoubleQuotes) {
                sb.append(next)
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
