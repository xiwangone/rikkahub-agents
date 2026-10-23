package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage

/** Separates per-message totals from the most recent model request for display. */
internal data class MessageUsagePresentation(
    val messageTotal: TokenUsage?,
    val latestRequest: TokenUsage?,
    /** Only show a count when the assistant actually made multiple model requests. */
    val multipleModelRequestCount: Int?,
)

internal fun UIMessage.messageUsagePresentation(): MessageUsagePresentation =
    MessageUsagePresentation(
        messageTotal = usage,
        latestRequest = lastRequestUsage(),
        multipleModelRequestCount = stepCount.takeIf { it > 1 },
    )
