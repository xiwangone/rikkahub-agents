package me.rerere.rikkahub.service

/**
 * Pending approval/ask_user must not be abandoned by an ordinary quick reply.
 *
 * 暂停态（失败归队后的“继续发送”）不在此列：它只拦「自动派发」，不能把用户手动发出的新消息
 * 也塞进队列 —— 否则队列一旦暂停，用户每发一条都只会看到“队列已暂停 N”，永远发不出去。
 */
internal fun shouldQueueChatMessage(
    isGenerating: Boolean,
    hasPendingTools: Boolean,
): Boolean = isGenerating || hasPendingTools
