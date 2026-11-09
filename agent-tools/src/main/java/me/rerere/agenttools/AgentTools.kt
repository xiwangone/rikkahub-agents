package me.rerere.agenttools

import me.rerere.ai.core.Tool

/**
 * 聚合入口：把本模块的全部能力一次暴露给宿主 App。
 *
 * 用法（在 :app 内）：
 * ```kotlin
 * val io = AppAgentWorkspaceIO(workspaceRepository, workspaceId)
 * val tools = createAgentTools(io, approvalOverrides)
 * ```
 */
fun createAgentTools(
    io: AgentWorkspaceIO,
    approvalOverrides: Map<String, Boolean>,
): List<Tool> =
    createOffloadTools(io, approvalOverrides) + createConfigTools(io, approvalOverrides)

// 统一审批默认值：默认免审批（false 表示执行该工具无需审批）。
val AgentToolsDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_offload" to false,
    "workspace_offload_list" to false,
    "config_get_all" to false,
    "config_set" to false,
    "config_revert" to false,
)

internal fun resolveApproval(overrides: Map<String, Boolean>, name: String) =
    overrides[name] ?: AgentToolsDefaultApprovals[name] ?: false