package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** True only when the same message/call identity changes from empty output to a real output. */
internal fun hasCompletedToolOutput(
    previousMessages: List<UIMessage>,
    updatedMessages: List<UIMessage>,
): Boolean {
    val previousEmptyToolIds = previousMessages
        .asSequence()
        .flatMap { message ->
            message.parts.asSequence()
                .filterIsInstance<UIMessagePart.Tool>()
                .filter { it.output.isEmpty() }
                .map { message.id to it.toolCallId }
        }
        .toSet()

    return updatedMessages.any { message ->
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
