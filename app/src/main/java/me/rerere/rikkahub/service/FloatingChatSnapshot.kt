package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation

/** 浮窗渲染用的对话快照。 */
internal data class FloatingChatSnapshot(
    val conversation: Conversation,
    val generating: Boolean,
    val processingStatus: String?,
    val queue: MessageQueueState,
    val replyMessage: UIMessage?,
    val startedAt: Long?,
    val errors: List<ChatError>,
)
