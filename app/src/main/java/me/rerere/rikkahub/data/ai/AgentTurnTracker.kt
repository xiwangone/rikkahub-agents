package me.rerere.rikkahub.data.ai

/**
 * Per-turn record of "did the agent take the user out of Yao?" — used by the
 * post-turn auto-return logic to decide whether to bring Yao back to the
 * foreground after the agent finishes. Reset at the start of every generation turn.
 *
 * Only navigation actions performed by the agent itself populate this. If the user
 * physically switched apps mid-turn, the post-turn handler compares the current
 * foreground against [lastDestination] and skips the auto-return for safety.
 */
object AgentTurnTracker {
    /**
     * Per-conversation state slot. Automation tools have no conversationId, so their
     * writes/reads land on [activeSlot] — the conversation currently generating. Two
     * concurrent generations therefore never overwrite each other's navigation facts.
     *
     * 已知边界（未改）：[activeSlot] 是单一 volatile，两会话**同时**生成时后激活者
     * 接管，先激活会话的自动化事件会落错槽。彻底修复需要把 conversationId 传进工具执行
     * 上下文（跨工具层改造）；当前以「每会话槽 + 显式 reset/release」收窄影响面。
     */
    private class TurnState {
        @Volatile var navigatedAway: Boolean = false
        @Volatile var destination: String? = null
        @Volatile var didAutomate: Boolean = false
    }

    private val states = java.util.concurrent.ConcurrentHashMap<String, TurnState>()
    @Volatile private var activeSlot: String = ""

    /** Switch the active slot before a generation turn starts (called with conversationId). */
    fun setActive(conversationId: String?) {
        activeSlot = conversationId ?: ""
    }

    private fun stateFor(conversationId: String?): TurnState =
        states.computeIfAbsent(conversationId ?: "") { TurnState() }

    /**
     * Per-package timestamp recording the last time the agent itself interacted with a
     * package (launch_app, open_url, termux_run_command). The notification listener checks
     * this before forwarding to Telegram so a user driving the agent does not get spammed
     * with status pings from the very work the agent just kicked off (Termux's "1 session"
     * counter is the canonical offender).
     */
    private val touchedPackages = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun reset(conversationId: String? = null) {
        val st = stateFor(conversationId)
        st.navigatedAway = false
        st.destination = null
        st.didAutomate = false
        // Don't clear touchedPackages — the suppression window outlives the turn so the
        // last few flaps after the turn ends also get filtered.
    }

    /**
     * 会话收尾后摘掉它的槽位（快照已取，状态不再需要）：states 表不随会话数缓慢泄漏。
     * 活跃 slot 若仍指向该会话也无妨——下次 onStart 会重新 setActive + reset，按需重建。
     */
    fun release(conversationId: String?) {
        states.remove(conversationId ?: "")
    }

    fun recordNavigatedAway(packageName: String?) {
        val st = stateFor(activeSlot)
        st.navigatedAway = true
        if (!packageName.isNullOrBlank()) st.destination = packageName
    }

    /**
     * Called by automation tools (tap, click_node, set_text, swipe, scroll, global_action)
     * when they perform an action against another app. Distinguishes "the agent opened X
     * and used it" (auto-return makes sense) from "the agent opened X for the user to use"
     * (auto-return would yank the user out of where they want to be).
     */
    fun recordAutomationAction() {
        stateFor(activeSlot).didAutomate = true
    }


    /** Snapshot-based decision inputs for a finished conversation (auto-return judgment). */
    fun snapshot(conversationId: String?): TurnSnapshot =
        stateFor(conversationId).let {
            TurnSnapshot(it.navigatedAway, it.destination, it.didAutomate)
        }

    data class TurnSnapshot(val navigatedAway: Boolean, val destination: String?, val didAutomate: Boolean)

    /** Marks [packageName] (and any pkg in [extras]) as freshly touched by the agent. */
    fun touchPackage(packageName: String, vararg extras: String) {
        val now = System.currentTimeMillis()
        touchedPackages[packageName] = now
        extras.forEach { touchedPackages[it] = now }
    }

    /**
     * True if the agent itself touched [packageName] within [withinMs]. Used by the
     * notification listener to drop forward-to-Telegram for packages the user did not
     * interact with — the agent did, and the resulting status notifications are not new
     * information for the user.
     */
    fun isFreshlyTouched(packageName: String, withinMs: Long = 60_000L): Boolean {
        val ts = touchedPackages[packageName] ?: return false
        return System.currentTimeMillis() - ts < withinMs
    }
}
