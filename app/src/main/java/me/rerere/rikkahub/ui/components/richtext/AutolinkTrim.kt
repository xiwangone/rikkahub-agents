package me.rerere.rikkahub.ui.components.richtext

// 裸链接后处理：词法器产出的自动链接节点可能把链接后的中文、全角标点、
// 括号说明以及空格后的词一并吞进链接文本，导致点击后打不开。
// 这里按裸链接的字符规则把多余尾巴切掉，切掉的部分原样返回，由调用方
// 以普通文本重新输出，不丢弃任何文字。
//
// 切分规则：
// 1. 裸链接内不含空白：第一个空白字符（ASCII 空白、全角空格、不换行空格）
//    即链接结束；
// 2. 链接只能由 RFC 3986 允许的字符组成，第一个不允许的字符即链接结束；
// 3. 尾部标点按常见自动链接规则处理：末尾的 `.,:;!?*_~'"` 等去掉，
//    右括号 `)` / `]` 仅在括号不平衡时去掉（括号平衡的链接保持原样）；
// 4. 切完后仍是带协议头的链接才生效，否则原样返回，避免把非链接
//    文本或相对路径误改。

private val URL_CHARACTERS: Set<Char> =
    (('a'..'z') + ('A'..'Z') + ('0'..'9')).toSet() +
        "-._~:/?#[]@!$&'()*+,;=%".toSet()

private val TRAILING_PUNCTUATION: Set<Char> =
    setOf('.', ',', ':', ';', '!', '?', '*', '_', '~', '\'', '"', '`')

/**
 * 把一段自动链接文本切成（链接，尾巴）两部分。
 *
 * 尾巴是被词法器多吞进去的文字（中文、全角标点、括号说明、`]` 后越过
 * 空格吞掉的词等），调用方应把尾巴以普通文本重新输出，不得丢弃。
 * 链接本身干净（尾巴为空）或输入不是裸链接时，返回（原串，空串）。
 */
internal fun splitAutolink(raw: String): Pair<String, String> {
    if (raw.isEmpty()) return raw to ""
    // 裸链接内不含空白：第一个空白处即链接结束（`]` 越空格的 case 在此切分）
    val whitespaceIndex =
        raw.indexOfFirst { it.isWhitespace() || it == '\u3000' || it == '\u00A0' }
    val head = if (whitespaceIndex < 0) raw else raw.substring(0, whitespaceIndex)
    // 第一个不在 RFC 3986 允许集内的字符即链接结束
    val invalidIndex = head.indexOfFirst { it !in URL_CHARACTERS }
    val core = if (invalidIndex < 0) head else head.substring(0, invalidIndex)
    var end = core.length
    while (end > 0) {
        when (val c = core[end - 1]) {
            ')' -> {
                val prefix = core.substring(0, end)
                if (prefix.count { it == ')' } > prefix.count { it == '(' }) {
                    end--
                } else {
                    break
                }
            }

            ']' -> {
                val prefix = core.substring(0, end)
                if (prefix.count { it == ']' } > prefix.count { it == '[' }) {
                    end--
                } else {
                    break
                }
            }

            in TRAILING_PUNCTUATION -> end--

            else -> break
        }
    }
    val url = core.substring(0, end)
    if (!isBareLink(url)) return raw to ""
    return url to raw.substring(url.length)
}

internal fun isBareLink(url: String): Boolean =
    (url.startsWith("https://") && url.length > "https://".length) ||
        (url.startsWith("http://") && url.length > "http://".length) ||
        (url.startsWith("www.") && url.length > "www.".length)

/**
 * 自动链接节点的完整切分结果（[splitAutolink] 的渲染层扩展）。
 *
 * 背景：词法器会把**行内代码里的 URL** 也识别成 GFM autolink（反引号一起进节点），
 * 也会把链接前的中文/标点吞进来。旧 [splitAutolink] 遇到**首字符不合法**（如反引号）
 * 就直接放弃切分、整段当链接 → 反引号被显示出来、下划线一路延伸到后面的中文。
 *
 * 三种情况：
 * - [code] 非空：原文是反引号包裹的（code span 被误判）→ 按行内代码渲染，不生成链接；
 * - [url] 非空：[prefix] / [trailing] 都是普通文本，原样输出（不丢字）；
 * - [url] 为空：不是裸链接，整段按普通文本输出。
 */
internal data class AutolinkParts(
    val prefix: String = "",
    val url: String = "",
    val trailing: String = "",
    val code: String? = null,
)

private val URL_SCHEMES = listOf("https://", "http://", "www.")

internal fun splitAutolinkParts(raw: String): AutolinkParts {
    if (raw.isEmpty()) return AutolinkParts()
    // 反引号包裹的 code span（可能夹在文本中）→ 剥出 code，两侧原样保留
    val tickStart = raw.indexOf('`')
    if (tickStart >= 0) {
        val tickEnd = raw.indexOf('`', tickStart + 1)
        if (tickEnd > tickStart + 1) {
            return AutolinkParts(
                prefix = raw.substring(0, tickStart),
                code = raw.substring(tickStart + 1, tickEnd),
                trailing = raw.substring(tickEnd + 1),
            )
        }
    }
    // 链接前可能夹着普通文本：从首个 scheme 起切，之前的部分当普通文本
    val start = URL_SCHEMES.map { raw.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
    if (start < 0) return AutolinkParts(prefix = raw)
    val (url, trailing) = splitAutolink(raw.substring(start))
    if (url.isEmpty()) return AutolinkParts(prefix = raw)
    return AutolinkParts(prefix = raw.substring(0, start), url = url, trailing = trailing)
}
