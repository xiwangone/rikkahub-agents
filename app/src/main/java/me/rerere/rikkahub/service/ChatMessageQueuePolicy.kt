package me.rerere.rikkahub.service

/** Pending approval/ask_user must not be abandoned by an ordinary quick reply. */
internal fun shouldQueueChatMessage(
    isGenerating: Boolean,
    hasPendingTools: Boolean,
    queuePaused: Boolean,
): Boolean = isGenerating || hasPendingTools || queuePaused
