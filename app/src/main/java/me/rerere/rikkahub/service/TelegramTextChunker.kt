package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Split [s] into pieces no longer than [n] code units, preferring natural break
 * points and never slicing through a UTF-16 surrogate pair.
 *
 * Cut preference (highest to lowest):
 *  1. Last "\n\n" paragraph break inside the trailing window of [n].
 *  2. Last single "\n" newline inside the trailing window.
 *  3. Hard `n`, walked back by 1 if it would land between a high surrogate at
 *     `n - 1` and its low surrogate at `n` — without this, multi-byte emoji get
 *     sliced (current chunk ends with an orphaned high surrogate, next chunk
 *     starts with a dangling low surrogate, both render as tofu boxes).
 *
 * Internal so the unit test in `:app`'s test source set can exercise it directly
 * — the production caller is `TelegramBotService.chunk`, which is a thin wrapper.
 */
internal fun chunkForTelegram(
    s: String,
    n: Int,
): List<String> {
    if (s.length <= n) return listOf(s)
    val out = mutableListOf<String>()
    var rem = s
    while (rem.length > n) {
        val window = n / 2
        val paraCut = rem.lastIndexOf("\n\n", n).let { if (it > window) it + 2 else -1 }
        val nlCut = if (paraCut < 0) rem.lastIndexOf('\n', n).let { if (it > window) it else -1 } else -1
        var cut =
            when {
                paraCut > 0 -> paraCut
                nlCut > 0 -> nlCut
                else -> n
            }
        if (cut in 1 until rem.length &&
            rem[cut - 1].isHighSurrogate() &&
            rem[cut].isLowSurrogate()
        ) {
            cut -= 1
        }
        out.add(rem.substring(0, cut))
        rem = rem.substring(cut).trimStart('\n')
    }
    if (rem.isNotEmpty()) out.add(rem)
    return out
}

/**
 * Truncate a streaming render to fit Telegram's per-message char cap, while keeping the
 * markdown well-formed enough for the HTML renderer downstream:
 *  - Prefer cutting at the last newline within the trailing 400 chars before the cap,
 *    so we don't slice through a word or a tag.
 *  - If the cut leaves an odd number of triple-backtick fences, append "\n```" to close
 *    the open fence — otherwise the renderer treats the rest of the message as a code
 *    block, which then falls back to plain text on Telegram's parse.
 *  - Always append "…" to signal truncation to the user.
 */
internal fun truncateForLiveEdit(
    s: String,
    max: Int,
): String {
    if (s.length <= max) return s
    val window = 400
    val hardCut = max - 4 // headroom for "…" and a possible "\n```"
    val searchFrom = (hardCut - window).coerceAtLeast(0)
    val nl = s.lastIndexOf('\n', hardCut).let { if (it >= searchFrom) it else hardCut }
    var sub = s.substring(0, nl)
    // If we sit inside an unclosed ``` fence, close it so HTML render stays valid.
    val fenceCount = Regex("```").findAll(sub).count()
    if (fenceCount % 2 == 1) sub += "\n```"
    return "$sub\n…"
}

/** 提取 UIMessage 中的纯文本部分。 */
internal fun assistantTextOf(m: UIMessage): String =
    m.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
