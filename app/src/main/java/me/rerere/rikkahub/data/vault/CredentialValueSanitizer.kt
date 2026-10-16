package me.rerere.rikkahub.data.vault

/**
 * 凭证值清洗器：在**落库前**统一剔除不可见字符。
 *
 * 为什么必须在写入时统一清洗：凭证值随后会被注入 shell 环境变量、HTTP 请求头、
 * 命令行与日志。不可见字符（\u0001、\u001b 转义序列、零宽字符）会破坏命令解析、
 * 伪造终端输出、污染日志，且肉眼不可见、排查困难。写入时清洗一次，
 * 胜过在每个使用点各自兜底。
 *
 * 规则：仅保留可打印字符 + 制表符 + 换行，并把 `\r\n` 归一成 `\n`。
 * - 保留 `\n`：PEM 私钥与多行 token 依赖换行，剔除会直接破坏密钥内容。
 * - 保留 `\t`：部分配置格式（.netrc / ini 等）以制表符分隔。
 * - **`\r\n` → `\n`（单独保留 `\r`，见下）**：Windows 端粘贴/导出/换行工具很常见。
 *   OpenSSH 读 PEM 时，**`-----BEGIN/END ...-----` 这两行行尾多一个 `\r` 就直接失败**
 *   （整份 CRLF、或只有尾行 CRLF，都报 `error in libcrypto`；只有中间正文行
 *   带 `\r` 反而能读 —— 所以不能只"去掉正文里的 CR"，要归一行尾）。
 *   单独出现的 `\r`（老 Mac 行尾、或真值本身含 CR）保持原样，避免改动可能的真值。
 * - **剔除 Cf 类格式符**（BOM \uFEFF、零宽 \u200B/\u200D、软连字符 \u00AD、
 *   方向覆盖 \u202A/\u202E、Word Joiner \u2060 …）：这类字符**代码点 ≥ 0x20**，
 *   旧实现按 "code >= 0x20 即保留" 放行，于是注释里承诺的"剔除不可见控制符"
 *   实际只覆盖了 ASCII 控制符。它们正是从网页/邮件/IM 粘贴时长相带进来的：
 *   肉眼看不见、加密原样入库、UI 显示正常，最后表现为
 *   `ssh-keygen -y: error in libcrypto`（往合法 PEM 的 base64 段里塞一个
 *   `\u200B`，报错与"密钥本身坏了"完全一样）。
 *   中文/日文等非 ASCII **可打印**文字属于别的类别，不受影响。
 *
 * 纯函数、无 Android 依赖，可直接单测。
 */
internal object CredentialValueSanitizer {

    /** 清洗后的值；结果为空表示"原值只含不可见字符"。 */
    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw
        // 快路径：绝大多数值本来就干净（长值如 PEM 逐字符判定要拷贝一次，能免则免）
        var first = -1
        for (i in raw.indices) {
            if (!isAllowed(raw, i)) { first = i; break }
        }
        if (first < 0) return raw
        val sb = StringBuilder(raw.length)
        sb.append(raw, 0, first)
        for (i in first until raw.length) {
            if (isAllowed(raw, i)) sb.append(raw[i])
        }
        return sb.toString()
    }

    /** 是否含将被剔除的字符（供上层给出明确报错，而不是静默丢弃）。 */
    fun hasRejectedChars(raw: String): Boolean =
        raw.indices.any { !isAllowed(raw, it) }

    /** 允许：制表符 / 换行 / 非行尾成对的回车 / 可打印字符（且非 Cf 格式符）。 */
    private fun isAllowed(s: String, index: Int): Boolean {
        val ch = s[index]
        return when (ch) {
            '\t', '\n' -> true
            // 紧跟 \n 的 \r 是"CRLF 行尾"，归一时丢掉；单独的 \r 当内容保留
            '\r' -> !(index + 1 < s.length && s[index + 1] == '\n')
            else -> ch.code >= 0x20 && ch.code != 0x7F &&
                Character.getType(ch) != CfFormat
        }
    }

    /** Unicode 通用类别 Cf（格式控制符）= `java.lang.Character.FORMAT`。 */
    private const val CfFormat = 16
}
