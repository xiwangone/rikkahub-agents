package me.rerere.ai.ui

/**
 * 审批状态的「单调合并」。
 *
 * 生成期间，同一个会话会被多份快照反复覆盖：流式合并窗口、step 边界补齐、生成结束收尾、
 * provider 内核路径。这些快照往往是在审批状态迁移**之前**采集的，其 tool part 仍然带着
 * [ToolApprovalState.Auto]。若允许这种过期快照整份覆盖，就会出现连锁故障：
 *
 * 1. `Pending` 已置位（`GenerationLoop` 已标记并等待用户）；
 * 2. 收尾/回灌把过期快照写回 → `Pending` 退回 `Auto`；
 * 3. UI 侧 `isPending = !isExecuted && approvalState is Pending` 变为 false → 审批按钮不渲染；
 * 4. 用户发下一条消息时，`Auto` 又恰好满足 `finishPendingTools` 的改写条件 →
 *    该工具被冤判为 `Denied("Generation cancelled by user")`。
 *
 * 因此规定一条不变量：**审批状态只能前进，不能被旧快照回退**。
 * 合并时以新快照（`this`）为准，仅当旧快照（`previous`）的状态更进展时，回贴旧状态。
 */

/**
 * 审批状态的进展序。数值只用于比较「谁更进展」，不对外暴露语义。
 *
 * `Auto` < `Pending` < `Approved` < {`Denied`, `Answered`}（后两者是终态）。
 */
private fun ToolApprovalState.progressRank(): Int = when (this) {
    is ToolApprovalState.Auto -> 0
    is ToolApprovalState.Pending -> 1
    is ToolApprovalState.Approved -> 2
    is ToolApprovalState.Denied -> 3
    is ToolApprovalState.Answered -> 3
}

/**
 * 以本 part（新快照）为准，但保留 [previous] 中更进展的审批状态。
 *
 * 其余字段（output / executionStartedAt / metadata 等）一律取新快照。
 * 无变化时返回自身（保持引用相等，避免无谓重组与落盘）。
 */
fun UIMessagePart.Tool.mergeApprovalProgress(previous: UIMessagePart.Tool): UIMessagePart.Tool {
    if (previous.toolCallId != toolCallId) return this
    if (previous.approvalState.progressRank() <= approvalState.progressRank()) return this
    return copy(approvalState = previous.approvalState)
}

/**
 * 按 toolCallId 回贴 [previous] 中更进展的审批状态；message id 不同则原样返回。
 */
fun UIMessage.mergeApprovalProgress(previous: UIMessage): UIMessage {
    if (previous.id != id) return this
    val previousTools =
        previous.parts
            .filterIsInstance<UIMessagePart.Tool>()
            .associateBy { it.toolCallId }
    if (previousTools.isEmpty()) return this
    var changed = false
    val merged =
        parts.map { part ->
            if (part !is UIMessagePart.Tool) return@map part
            val prev = previousTools[part.toolCallId] ?: return@map part
            val next = part.mergeApprovalProgress(prev)
            if (next !== part) changed = true
            next
        }
    return if (changed) copy(parts = merged) else this
}

/**
 * 按 message id 回贴 [previous] 中更进展的审批状态。无变化时返回自身。
 */
fun List<UIMessage>.mergeApprovalProgress(previous: List<UIMessage>): List<UIMessage> {
    if (previous.isEmpty()) return this
    val previousById = previous.associateBy { it.id }
    var changed = false
    val merged =
        map { message ->
            val prev = previousById[message.id] ?: return@map message
            val next = message.mergeApprovalProgress(prev)
            if (next !== message) changed = true
            next
        }
    return if (changed) merged else this
}
