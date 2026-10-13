package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class CompactedMessageView(
    val messages: List<UIMessage>,
    val compaction: ConversationCompaction?,
    val rawTailStartIndex: Int,
    /** Present only for the request that created a new automatic compaction. */
    val newlyCreatedAutoCompaction: ConversationCompaction? = null,
)

object ContextCompactionView {
    /**
     * 合成摘要消息的身份必须对同一份 [compaction] 保持稳定：`UIMessage.user()` 默认每次
     * 生成随机 id 与"当前时间"，而时间提醒会被注入到请求里第一条用户消息之前 —— 身份每次
     * 都变，等于整段请求前缀每次都不同，压缩之后提供方的提示词缓存会完全命中不了。
     *
     * `sourceEndNodeId` 是 MessageNode 的 id（不是消息 id），复用它做这条合成消息的 id 不会
     * 与真实消息冲突。
     *
     * 摘要尾部追加一段固定的「执行提示」（校准注）：压缩把结构化的 tool_call/result 对
     * 文字化之后，摘要里只剩“做过什么”的叙述，模型容易在思考中反复预演计划却不发起工具调用。
     * 这段提示直接对冲该模式。追加在摘要文本内部而非独立消息：[mergeGeneratedMessages] 的位置
     * 映射严格依赖 `summary + tail` 的视图结构，插入独立消息会让 tail 整体错位；而文本对同一份
     * compaction 是确定性的，因此提示词缓存前缀不受影响（与上面用 sourceEndNodeId 做 id 同一考量）。
     */
    private const val SUMMARY_CALIBRATION_NOTE =
        "\n\n[执行提示] 以上是此前对话的摘要，仅供回溯。其中记录的工具调用与结果均已真实发生，" +
            "不要重新执行已成功的调用。工具环境本身没有变化：需要行动时直接发起工具调用，" +
            "不要在思考中复述计划。若摘要与工具的实际状态不一致，先用只读工具核实再行动。" +
            "摘要末尾的「## 待办」小节是下一步行动清单，优先处理其中的事项；该小节不存在时忽略此句。"

    internal fun summaryMessage(compaction: ConversationCompaction): UIMessage =
        UIMessage.user(compaction.summary + SUMMARY_CALIBRATION_NOTE).copy(
            createdAt = Instant.fromEpochMilliseconds(compaction.createdAt.toEpochMilli())
                .toLocalDateTime(TimeZone.currentSystemDefault()),
            id = compaction.sourceEndNodeId,
        )

    fun build(
        conversation: Conversation,
        compaction: ConversationCompaction?,
    ): CompactedMessageView {
        if (compaction == null) return rawView(conversation)

        val sourceEndIndex = conversation.messageNodes.indexOfFirst {
            it.id == compaction.sourceEndNodeId
        }
        val resolvedTailStartIndex = compaction.tailStartNodeId?.let { tailStartNodeId ->
            conversation.messageNodes.indexOfFirst { it.id == tailStartNodeId }
        }
        // A non-null tailStartNodeId that no longer resolves (its node was deleted) falls back
        // to sourceEndIndex + 1, same as a null tailStartNodeId: the next node simply becomes
        // the tail start rather than invalidating the whole compaction.
        val tailStartIndex = resolvedTailStartIndex?.takeIf { it >= 0 } ?: (sourceEndIndex + 1)
        if (
            sourceEndIndex < 0 ||
            tailStartIndex != sourceEndIndex + 1 ||
            tailStartIndex !in 0..conversation.messageNodes.size
        ) {
            return rawView(conversation)
        }

        return CompactedMessageView(
            messages = ContextCompactionPresentation.stripDisplayTools(
                listOf(summaryMessage(compaction)) +
                    conversation.currentMessages.drop(tailStartIndex),
            ),
            compaction = compaction,
            rawTailStartIndex = tailStartIndex,
        )
    }

    /**
     * Merges a generation result produced from a compacted context back into the complete
     * conversation. The synthetic summary is request-only and must never become a message node.
     */
    fun mergeGeneratedMessages(
        conversation: Conversation,
        view: CompactedMessageView,
        generatedMessages: List<UIMessage>,
    ): Conversation {
        require(view.compaction != null) { "A compacted view is required" }

        val inputSize = view.messages.size
        val nodes = conversation.messageNodes.toMutableList()
        // message id -> (nodeIndex, messageIndex), built once instead of the nested
        // `nodes.indexOfFirst { node -> node.messages.any { ... } }` scan this replaced (O(tail
        // x totalNodes) per streamed chunk, issue #109). First occurrence wins, matching
        // indexOfFirst. Kept in sync below whenever a node gains a message or a node is appended,
        // so a later generated message in this same call can still resolve against it.
        val locationByMessageId = HashMap<Uuid, Pair<Int, Int>>()
        nodes.forEachIndexed { nodeIndex, node ->
            node.messages.forEachIndexed { messageIndex, storedMessage ->
                locationByMessageId.putIfAbsent(storedMessage.id, nodeIndex to messageIndex)
            }
        }
        generatedMessages.forEachIndexed { index, message ->
            val location = locationByMessageId[message.id]
            if (location != null) {
                val (nodeIndex, messageIndex) = location
                val node = nodes[nodeIndex]
                val replacement = ContextCompactionPresentation.preserveDisplayTools(
                    previous = node.messages[messageIndex],
                    replacement = message,
                )
                if (node.messages[messageIndex] != replacement) {
                    nodes[nodeIndex] = node.copy(
                        messages = node.messages.toMutableList().apply {
                            this[messageIndex] = replacement
                        },
                    )
                }
            } else if (index >= inputSize) {
                // The generated list is [summary] + tail, so generated index i >= 1 corresponds
                // to node view.rawTailStartIndex + i - 1. On the normal (non-regenerate) path the
                // view's tail always runs to the end of the conversation, so this always lands
                // past the last node and falls through to the append below - byte-identical to
                // before this was made positional for the regenerate case.
                val boundaryNodeIndex = view.rawTailStartIndex + index - 1
                if (boundaryNodeIndex <= nodes.lastIndex) {
                    val node = nodes[boundaryNodeIndex]
                    val newMessages = node.messages + message
                    nodes[boundaryNodeIndex] = node.copy(
                        messages = newMessages,
                        selectIndex = newMessages.lastIndex,
                    )
                    locationByMessageId.putIfAbsent(message.id, boundaryNodeIndex to newMessages.lastIndex)
                } else {
                    nodes += message.toMessageNode()
                    locationByMessageId.putIfAbsent(message.id, nodes.lastIndex to 0)
                }
            }
        }

        return conversation.copy(messageNodes = nodes)
    }

    private fun rawView(conversation: Conversation) = CompactedMessageView(
        messages = ContextCompactionPresentation.stripDisplayTools(conversation.currentMessages),
        compaction = null,
        rawTailStartIndex = 0,
    )

    /** (node id, selected message id) for every node up to and including the compaction's source-end node, or null if that node is absent. */
    fun compactedPrefixSignature(
        nodes: List<MessageNode>,
        compaction: ConversationCompaction,
    ): List<Pair<Uuid, Uuid>>? {
        val end = nodes.indexOfFirst { it.id == compaction.sourceEndNodeId }
        if (end < 0) return null
        return nodes.take(end + 1).map { it.id to it.currentMessage.id }
    }

    /** True when [after] still carries the exact compacted prefix that [before] had. */
    fun compactedPrefixUnchanged(
        compaction: ConversationCompaction,
        before: List<MessageNode>,
        after: List<MessageNode>,
    ): Boolean {
        val expected = compactedPrefixSignature(before, compaction) ?: return false
        return compactedPrefixSignature(after, compaction) == expected
    }
}
