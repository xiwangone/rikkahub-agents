package me.rerere.rikkahub.service

/** Pure decision state for the optional background generation overlay. */
internal data class FloatingOverlayPolicyState(
    val enabled: Boolean = false,
    val foreground: Boolean = true,
    val hasOverlayPermission: Boolean = false,
    val runningConversationIds: Set<String> = emptySet(),
    val targetConversationId: String? = null,
    val expanded: Boolean = false,
    val dismissedUntilNextGeneration: Boolean = false,
) {
    val shouldShowWindow: Boolean
        get() = enabled && !foreground && hasOverlayPermission && targetConversationId != null

    fun updateEnabled(value: Boolean): FloatingOverlayPolicyState =
        if (!value) copy(enabled = false, targetConversationId = null, expanded = false, dismissedUntilNextGeneration = false)
        else if (enabled) this else copy(enabled = true, targetConversationId = null, expanded = false, dismissedUntilNextGeneration = false)

    fun updateForeground(value: Boolean): FloatingOverlayPolicyState =
        if (value == foreground) this
        else if (value) copy(foreground = true, targetConversationId = null, expanded = false, dismissedUntilNextGeneration = false)
        else copy(foreground = false, targetConversationId = null, expanded = false, dismissedUntilNextGeneration = false)

    fun updatePermission(value: Boolean): FloatingOverlayPolicyState = copy(hasOverlayPermission = value)

    fun updateRunning(ids: Set<String>): FloatingOverlayPolicyState {
        val started = ids - runningConversationIds
        if (!enabled || foreground) return copy(runningConversationIds = ids, targetConversationId = null)
        val target = when {
            started.isNotEmpty() -> started.sorted().last()
            targetConversationId != null && (ids.isNotEmpty() || !dismissedUntilNextGeneration) -> targetConversationId
            targetConversationId == null && !dismissedUntilNextGeneration && ids.isNotEmpty() -> ids.sorted().last()
            else -> targetConversationId
        }
        return copy(runningConversationIds = ids, targetConversationId = target, dismissedUntilNextGeneration = if (started.isNotEmpty()) false else dismissedUntilNextGeneration)
    }

    fun selectConversation(id: String): FloatingOverlayPolicyState =
        if (id !in runningConversationIds) this else copy(targetConversationId = id)

    fun expand(): FloatingOverlayPolicyState = if (targetConversationId == null) this else copy(expanded = true)
    fun collapse(): FloatingOverlayPolicyState = copy(expanded = false)
    fun dismiss(): FloatingOverlayPolicyState = copy(targetConversationId = null, expanded = false, dismissedUntilNextGeneration = true)
}
