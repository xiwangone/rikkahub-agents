package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import kotlin.uuid.Uuid

/** 一条排队等待发送的用户消息（在真正发出前不进对话历史）。 */
data class QueuedMessage(
    val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
)

/**
 * 每会话的待发送队列。
 *
 * 语义：生成过程中再按发送键，消息进入队列而不是打断当前生成；当前生成结束后由
 * [ChatService.dispatchNextQueuedMessage] 按 FIFO 自动发出下一条。这样「发送键在生成中
 * 依然可用」，无需用户等待或手动停止。
 *
 * 第一版刻意保持精简：只做排队与顺序发出，不做队列条目的编辑/删除/暂停与附件清理
 * （编辑态会让队列语义复杂化，附件清理需要全库引用扫描，均留待后续按需增加）。
 */
class MessageQueue {
    private val mutableState = MutableStateFlow<List<QueuedMessage>>(emptyList())
    val state: StateFlow<List<QueuedMessage>> = mutableState.asStateFlow()

    val size: Int get() = mutableState.value.size

    /** 入队；空白内容（无文本且无附件）直接忽略。 */
    @Synchronized
    fun enqueue(
        parts: List<UIMessagePart>,
        answer: Boolean = true,
    ) {
        if (parts.isEmptyInputMessage()) return
        mutableState.value = mutableState.value + QueuedMessage(parts = parts.toList(), answer = answer)
    }

    /** 取队首并移除。空队列返回 null。 */
    @Synchronized
    fun takeNext(): QueuedMessage? {
        val current = mutableState.value
        val next = current.firstOrNull() ?: return null
        mutableState.value = current.drop(1)
        return next
    }

    /** 一次取走全部（step 之间注入用：把用户补充一次性交给本轮请求）。 */
    @Synchronized
    fun drainAll(): List<QueuedMessage> {
        val all = mutableState.value
        if (all.isNotEmpty()) mutableState.value = emptyList()
        return all
    }

    /** 清空（会话删除/用户主动放弃时）。 */
    @Synchronized
    fun clear() {
        mutableState.value = emptyList()
    }
}
