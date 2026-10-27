package me.rerere.rikkahub.utils

/**
 * 补齐未闭合的 Markdown 标记，仅用于**渲染前**，不修改存储的原文。
 *
 * 为什么需要：流式回复被中断（取消 / 连接被重置）时，文本里可能留下
 * 奇数个行内反引号，或一个没闭合的代码围栏。残缺标记会让解析器把后续
 * 内容吞进代码块、或干脆不渲染 —— 表现成「内容少了一段」，而数据其实是
 * 完整的。补一个闭合标记，已到达的内容就能正常显示。
 *
 * 规则刻意保守：只按出现次数判奇偶，不做语义解析（宁可多出一个空代码块，
 * 也不改写正文）。
 */
fun String.withClosedMarkdownFences(): String {
    if (isEmpty()) return this

    val fenceCount = Regex("```").findAll(this).count()
    var out = if (fenceCount % 2 != 0) "$this\n```" else this

    // 围栏补过之后，围栏占用的反引号数 = (fenceCount + 补的那一个) * 3
    val fencesAfter = fenceCount + fenceCount % 2
    val inlineTicks = out.count { it == '`' } - fencesAfter * 3
    if (inlineTicks > 0 && inlineTicks % 2 != 0) out = "$out`"

    return out
}
