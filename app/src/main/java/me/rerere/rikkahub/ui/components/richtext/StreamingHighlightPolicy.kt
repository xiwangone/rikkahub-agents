package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * 围栏代码块的结构信息（纯解析产物，不含任何 UI 状态），供流式渲染的缓存卫生判定使用。
 */
internal data class CodeFenceFragment(
    val code: String,
    val language: String,
    /** 是否出现围栏结束标记。纯结构判据，与「输出流是否结束」无关。 */
    val closed: Boolean,
)

/**
 * 从 CODE_FENCE 节点取出正文、语言与闭合状态；结构不完整时返回 null（此时没有可高亮的正文）。
 */
internal fun extractCodeFence(
    node: ASTNode,
    content: String,
): CodeFenceFragment? {
    // 不能直接取 CODE_FENCE_CONTENT 的内容，因为首行 indent 没有包含在内；
    // 因此往上找最后一个 EOL，用它作为代码块正文的起始偏移。
    val contentStartIndex = node.children.indexOfFirst { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
    if (contentStartIndex == -1) return null
    val eolElement =
        node.children.subList(0, contentStartIndex).findLast { it.type == MarkdownTokenTypes.EOL } ?: return null
    val codeContentStartOffset = eolElement.endOffset
    val codeContentEndOffset =
        node.children.findLast { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }?.endOffset ?: return null

    return CodeFenceFragment(
        code = content.substring(codeContentStartOffset, codeContentEndOffset).trimIndent(),
        language = node.findChildOfTypeRecursive(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(content) ?: "plaintext",
        closed = node.findChildOfTypeRecursive(MarkdownTokenTypes.CODE_FENCE_END) != null,
    )
}

/**
 * 流式期间的高亮缓存策略。定稿判据只有「块是否闭合」这一处，UI 侧不再叠加第二层判断。
 *
 * - 未闭合块（流式中间帧）不写缓存：有限容量的 LRU 否则会被中间帧灌满，定稿帧反而拿不到缓存。
 *   代价是「始终以未闭合状态结束」的代码块不进缓存，这类块占比很小。
 * - 已闭合块以完整代码作为高亮输入：折叠只裁剪显示行数，因此折叠/展开切换不会被当成新键。
 */
internal object HighlightStreamingPolicy {
    fun writesCache(
        blockClosed: Boolean,
    ): Boolean = blockClosed

    fun cacheSource(
        blockClosed: Boolean,
        fullCode: String,
        visibleCode: String,
    ): String = if (blockClosed) fullCode else visibleCode
}
