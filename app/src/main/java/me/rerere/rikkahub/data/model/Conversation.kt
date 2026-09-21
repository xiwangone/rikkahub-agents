package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.mergeApprovalProgress
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.log.AppLog
import java.time.Instant
import kotlin.uuid.Uuid

private const val TAG_CONVERSATION = "Conversation"

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    // Set only by SubAgentEngine when subagent_dispatch resolves an explicit model_id.
    // Null (the default, and what every persisted conversation decodes to) means "use the
    // assistant/settings default" - see ChatService.handleMessageComplete.
    val chatModelId: Uuid? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 会话级作用域覆盖（目前由 SubAgentEngine 写入；null = 跟随助手设置）：
    //  - workspaceIdOverride：该会话的 workspace_* 工具绑定到哪个工作区（子代理可跑在独立工作区）
    //  - toolScopeOverride：工具白名单（只减不增，未知/失效名字静默丢弃并回显）
    val workspaceIdOverride: Uuid? = null,
    val toolScopeOverride: List<String>? = null,
    // 生成循环的步数上限覆盖（子代理会话用 max_trips 映射而来；null = 用全局默认）
    val maxToolStepsOverride: Int? = null,
    /** 该会话是否由子代理派发产生（用于会话列表单独分组）。 */
    val isSubAgentRun: Boolean = false,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .collectAllParts()
            .mapNotNull { it.fileUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()
        // 一次性建立 messageId -> 节点下标 的索引：原实现对每条消息都全量扫描节点，
        // 长会话（数百个节点 × 每块携带的全量消息）下是 O(N²)，流式期间每 120ms
        // 触发一次会明显拖慢；索引化后为 O(N)。
        val nodeIndexByMessageId = HashMap<Uuid, Int>(newNodes.size * 2)
        newNodes.forEachIndexed { nodeIndex, node ->
            node.messages.forEach { m -> nodeIndexByMessageId[m.id] = nodeIndex }
        }

        messages.forEachIndexed { index, message ->
            // 先按 id 定位消息原本所属的节点：请求链路上的 transformer
            // （工作区提醒 / 时间提醒 / 提示词注入 / OCR）会在列表头部插入合成消息，
            // 导致 messages 的下标与 messageNodes 的下标错位。若仍按 index 对应，
            // 同一节点的消息会被写到别的节点上、并被当成「新消息」追加，
            // 表现为节点内出现多余分支（UI 上的「2/2」）。
            val existingNodeIndex = nodeIndexByMessageId[message.id]
            val targetIndex = existingNodeIndex ?: index

            val node = newNodes.getOrElse(targetIndex) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            val existingMessageIndex = newMessages.indexOfFirst { it.id == message.id }
            if (existingMessageIndex >= 0) {
                // 审批状态单调：新快照只能带来「更进展」的审批状态，过期的 Auto 不得把
                // 已经置位的 Pending / Approved / Denied / Answered 回退（详见 ToolApprovalMerge.kt）。
                // 这是「审批按钮一闪即没」类故障的根本防护：闪回由后续旧快照回灌引起。
                val merged = message.mergeApprovalProgress(newMessages[existingMessageIndex])
                if (merged !== message) {
                    AppLog.w(
                        TAG_CONVERSATION,
                        "mergeApprovalProgress: blocked stale snapshot regression on message ${message.id}",
                    )
                }
                newMessages[existingMessageIndex] = merged
                newMessageIndex = existingMessageIndex
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            if (targetIndex > newNodes.lastIndex) {
                newNodes.add(newNode)
                nodeIndexByMessageId[message.id] = newNodes.lastIndex
            } else {
                newNodes[targetIndex] = newNode
                nodeIndexByMessageId[message.id] = targetIndex
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 本地附件引用集合（含工具调用结果中的嵌套附件）。
 *
 * 供待发送队列的附件清理判断「这条消息撤销后，它的附件是否还有人引用」。
 */
internal fun List<UIMessagePart>.localFileUrls(): Set<String> =
    collectAllParts().mapNotNull { it.fileUri()?.toString() }.toSet()

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * 提取 part 中引用的本地文件 URI，新增文件类型时只需在此处添加。
 */
private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}
