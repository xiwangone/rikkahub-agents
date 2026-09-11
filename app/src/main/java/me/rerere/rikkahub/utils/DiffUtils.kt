package me.rerere.rikkahub.utils

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils

private const val DEFAULT_CONTEXT_LINES = 3

/**
 * 生成 [oldText] 到 [newText] 的 unified diff 文本, 内容相同时返回 null
 */
fun generateUnifiedDiff(
    oldText: String,
    newText: String,
    path: String,
    contextLines: Int = DEFAULT_CONTEXT_LINES,
): String? {
    if (oldText == newText) return null
    // 空文本按「无行」处理：Kotlin 的 "".lines() 返回 [""]（含一个空串元素），
    // 直接使用会让 diff 里凭空多出一行增/删（新增文件出现一条红色空行、
    // 删除文件出现一条绿色空行），既错误又影响红绿判读。
    val oldLines = if (oldText.isEmpty()) emptyList() else oldText.lines()
    val newLines = if (newText.isEmpty()) emptyList() else newText.lines()
    val patch = DiffUtils.diff(oldLines, newLines)
    if (patch.deltas.isEmpty()) return null
    return UnifiedDiffUtils
        .generateUnifiedDiff("a/$path", "b/$path", oldLines, patch, contextLines)
        .joinToString("\n")
}
