package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** True only when the same message/call identity changes from empty output to a real output. */
internal fun hasCompletedToolOutput(
    previousMessages: List<UIMessage>,
    updatedMessages: List<UIMessage>,
): Boolean {
    if (previousMessages.isEmpty() || updatedMessages.isEmpty()) return false
    // 先只在发生引用变化的更新消息里找「非空输出的工具」；纯文本/思考流式分块可 O(1) 短路，
    // 不必在长会话里每 40ms 扫描全部历史消息并构建 Set。
    val changedMessagesWithCompletedTool =
        updatedMessages.filterIndexed { index, message ->
            previousMessages.getOrNull(index) !== message &&
                message.parts.any { part -> part is UIMessagePart.Tool && part.output.isNotEmpty() }
        }
    if (changedMessagesWithCompletedTool.isEmpty()) return false

    val previousEmptyToolIds = previousMessages
        .asSequence()
        .flatMap { message ->
            message.parts.asSequence()
                .filterIsInstance<UIMessagePart.Tool>()
                .filter { it.output.isEmpty() }
                .map { message.id to it.toolCallId }
        }
        .toSet()
    if (previousEmptyToolIds.isEmpty()) return false

    return changedMessagesWithCompletedTool.any { message ->
        message.parts.any { part ->
            part is UIMessagePart.Tool && part.output.isNotEmpty() &&
                (message.id to part.toolCallId) in previousEmptyToolIds
        }
    }
}

/** Keep normal deltas throttled, but never hide a just-completed tool result behind the window. */
internal fun shouldCommitStreamUiUpdate(
    nowMs: Long,
    lastCommitAtMs: Long,
    intervalMs: Long,
    completedToolOutput: Boolean,
): Boolean = completedToolOutput || nowMs - lastCommitAtMs >= intervalMs
