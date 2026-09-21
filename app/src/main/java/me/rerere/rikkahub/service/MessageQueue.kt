package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.localFileUrls
import kotlin.uuid.Uuid

/** 一条排队等待发送的用户消息（在真正发出前不进对话历史）。 */
data class QueuedMessage(
    val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    /** 编辑中的条目不参与派发。 */
    val isEditing: Boolean = false,
    /**
     * 可选的旁观者（语音模式用）：本轮真正发完后给出助手回复文本；
     * 值 [null] 表示该条目被撤销 / 被移出队列（不会有回复）。
     */
    val reply: CompletableDeferred<String?>? = null,
)

/** 队列被暂停时，等待回复的语音调用以此区分「暂停」与「生成失败」。 */
class MessageQueuePausedException : IllegalStateException()

/** 队列状态：待发送条目 + 是否暂停派发。 */
data class MessageQueueState(
    val messages: List<QueuedMessage> = emptyList(),
    val paused: Boolean = false,
)

/**
 * 计算 [previous] 的本地附件在移除后是否已无人引用。
 *
 * 已落库的会话内容与仍在队列里的消息都算作引用；只有两侧都不再引用时才返回，
 * 避免「撤销排队消息」连带删掉其它消息正在使用的附件。
 */
internal fun unreferencedQueuedAttachmentUrls(
    previous: QueuedMessage,
    conversations: List<Conversation>,
    pendingMessages: List<QueuedMessage>,
): Set<String> {
    val retainedParts = conversations.flatMap { conversation ->
        conversation.messageNodes.flatMap { node -> node.messages.flatMap { it.parts } }
    } + pendingMessages.flatMap { it.parts }
    return previous.parts.localFileUrls() - retainedParts.localFileUrls()
}

/**
 * 每会话的待发送队列。
 *
 * 语义：生成过程中再按发送键，消息进入队列而不是打断当前生成；当前生成结束后由
 * [ChatService.dispatchNextQueuedMessage] 按 FIFO 自动发出下一条。这样「发送键在生成中
 * 依然可用」，无需用户等待或手动停止。
 *
 * 队列可暂停：生成异常结束或用户主动停止时暂停，避免在用户没有预期的情况下继续发出
 * 下一条；暂停期间只保留条目、不派发。条目支持撤销与编辑，编辑中的条目同样不派发。
 */
class MessageQueue {
    private val mutableState = MutableStateFlow(MessageQueueState())
    val state = mutableState.asStateFlow()

    val size: Int get() = mutableState.value.messages.size

    /** 入队；空白内容（无文本且无附件）直接忽略。 */
    @Synchronized
    fun enqueue(
        parts: List<UIMessagePart>,
        answer: Boolean = true,
        reply: CompletableDeferred<String?>? = null,
    ) {
        if (parts.isEmptyInputMessage()) {
            reply?.complete(null)
            return
        }
        mutableState.value = mutableState.value.copy(
            messages = mutableState.value.messages + QueuedMessage(
                parts = parts.toList(),
                answer = answer,
                reply = reply,
            ),
        )
    }

    /** 取队首并移除。暂停中或队首正在编辑时返回 null。 */
    @Synchronized
    fun takeNext(): QueuedMessage? {
        val current = mutableState.value
        if (current.paused) return null
        val next = current.messages.firstOrNull()?.takeUnless { it.isEditing } ?: return null
        mutableState.value = current.copy(messages = current.messages.drop(1))
        return next
    }

    /** 撤销一条排队消息，返回被移除的条目供调用方清理附件。 */
    @Synchronized
    fun remove(id: Uuid): QueuedMessage? {
        val removed = mutableState.value.messages.find { it.id == id } ?: return null
        mutableState.value = mutableState.value.copy(
            messages = mutableState.value.messages.filterNot { it.id == id },
        )
        // 已撤销的条目不可能再产生回复 —— 让等待者拿 null 而不是永久挂起
        removed.reply?.complete(null)
        return removed
    }

    /** 进入编辑态：编辑期间该条目不参与派发。返回原条目供预填输入框。 */
    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        val message = mutableState.value.messages.find { it.id == id && !it.isEditing } ?: return null
        mutableState.value = mutableState.value.copy(
            messages = mutableState.value.messages.map { if (it.id == id) it.copy(isEditing = true) else it },
        )
        return message
    }

    /**
     * 结束编辑。[parts] 为 null 表示放弃编辑（只释放占位，不动原内容与附件）；
     * 非空则替换内容，并返回替换前的条目供调用方清理旧附件。
     */
    @Synchronized
    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        val previous = mutableState.value.messages.find { it.id == id } ?: return null
        mutableState.value = mutableState.value.copy(
            messages = mutableState.value.messages.map {
                if (it.id == id) {
                    it.copy(parts = parts?.toList() ?: it.parts, isEditing = false)
                } else {
                    it
                }
            },
        )
        return previous.takeIf { parts != null }
    }

    /** 暂停派发（生成失败或用户主动停止时调用）。 */
    @Synchronized
    fun pause() {
        mutableState.value = mutableState.value.copy(paused = true)
        // 等待回复的语音调用需要立即得知「已暂停」，否则会一直等下去
        mutableState.value.messages.forEach { it.reply?.completeExceptionally(MessageQueuePausedException()) }
    }

    /** 因外部阻塞（如待审批工具）让等待中的语音调用立即失败，条目本身保留在队列里。 */
    fun failReplyWaiters(message: String) {
        mutableState.value.messages.forEach {
            it.reply?.completeExceptionally(IllegalStateException(message))
        }
    }

    /** 恢复派发（面板上的「继续发送」）。 */
    @Synchronized
    fun resume() {
        mutableState.value = mutableState.value.copy(paused = false)
    }

    /** 一次取走全部（step 之间注入用：把用户补充一次性交给本轮请求）。 */
    @Synchronized
    fun drainAll(): List<QueuedMessage> {
        val all = mutableState.value.messages
        if (all.isNotEmpty()) mutableState.value = mutableState.value.copy(messages = emptyList())
        return all
    }

    /** 清空（会话删除/用户主动放弃时）。 */
    @Synchronized
    fun clear() {
        mutableState.value = MessageQueueState()
    }
}
