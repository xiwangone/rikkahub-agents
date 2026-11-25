package me.rerere.ai.util

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.Collections
import java.util.IdentityHashMap

internal const val MAX_HISTORY_IMAGES = 3
internal const val OMITTED_HISTORY_IMAGE_PLACEHOLDER = "[earlier image omitted to save memory]"

/**
 * Collects the most recent [maxImages] [UIMessagePart.Image] instances across [messages]
 * (including images inside [UIMessagePart.Tool.output]), using object identity so long
 * multi-step sessions only base64-encode the latest frames while older ones degrade cleanly.
 */
internal fun retainedHistoryImages(
    messages: List<UIMessage>,
    maxImages: Int = MAX_HISTORY_IMAGES,
): Set<UIMessagePart.Image> {
    val retained: MutableSet<UIMessagePart.Image> = Collections.newSetFromMap(IdentityHashMap())
    if (maxImages <= 0) return retained
    for (msgIndex in messages.lastIndex downTo 0) {
        collectMessageImages(messages[msgIndex], retained, maxImages)
        if (retained.size >= maxImages) return retained
    }
    return retained
}

/** Scans one message newest-first, collecting its images (including tool outputs). */
private fun collectMessageImages(
    message: UIMessage,
    retained: MutableSet<UIMessagePart.Image>,
    maxImages: Int,
) {
    for (partIndex in message.parts.lastIndex downTo 0) {
        when (val part = message.parts[partIndex]) {
            is UIMessagePart.Image -> {
                retained.add(part)
                if (retained.size >= maxImages) return
            }
            is UIMessagePart.Tool -> collectToolOutputImages(part.output, retained, maxImages)
            else -> {}
        }
        if (retained.size >= maxImages) return
    }
}

private fun collectToolOutputImages(
    output: List<UIMessagePart>,
    retained: MutableSet<UIMessagePart.Image>,
    maxImages: Int,
) {
    for (outIndex in output.lastIndex downTo 0) {
        val outPart = output[outIndex]
        if (outPart is UIMessagePart.Image) {
            retained.add(outPart)
            if (retained.size >= maxImages) return
        }
    }
}
