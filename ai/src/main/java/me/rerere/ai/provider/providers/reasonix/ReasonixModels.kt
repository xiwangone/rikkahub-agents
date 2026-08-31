package me.rerere.ai.provider.providers.reasonix

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Reasonix serve HTTP/SSE 数据模型。
 * 移植自 DeepSeek-Reasonix-android `Models.kt`（Gson → kotlinx.serialization）。
 * 对应服务端 `internal/eventwire` 的 JSON 契约。
 */

// ── SSE 事件类型 ──

@Serializable
enum class SseEventKind {
    @SerialName("turn_started") TURN_STARTED,
    @SerialName("reasoning") REASONING,
    @SerialName("text") TEXT,
    @SerialName("message") MESSAGE,
    @SerialName("tool_dispatch") TOOL_DISPATCH,
    @SerialName("tool_result") TOOL_RESULT,
    @SerialName("tool_progress") TOOL_PROGRESS,
    @SerialName("usage") USAGE,
    @SerialName("notice") NOTICE,
    @SerialName("phase") PHASE,
    @SerialName("approval_request") APPROVAL_REQUEST,
    @SerialName("ask_request") ASK_REQUEST,
    @SerialName("compaction_started") COMPACTION_STARTED,
    @SerialName("compaction_done") COMPACTION_DONE,
    @SerialName("turn_done") TURN_DONE,
    @SerialName("") UNKNOWN,
}

@Serializable
data class SseEvent(
    val kind: String = "",
    val text: String? = null,
    val reasoning: String? = null,
    val detail: String? = null,
    val code: String? = null,
    val err: String? = null,
    val level: String? = null,
    val tool: ToolPayload? = null,
    val usage: UsagePayload? = null,
    val approval: ApprovalPayload? = null,
    val ask: AskPayload? = null,
    val compaction: CompactionPayload? = null,
    val message: MessagePayload? = null,
    val outcome: String? = null,
)

// ── 工具相关 ──

@Serializable
data class ToolPayload(
    val id: String = "",
    val name: String = "",
    val args: String? = null,
    val arguments: String? = null,
    val output: String? = null,
    val err: String? = null,
    val truncated: Boolean = false,
    val readOnly: Boolean = false,
    val subject: String? = null,
    val durationMs: Long = 0,
)

// ── 用量统计 ──

@Serializable
data class UsagePayload(
    val totalTokens: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cacheHitTokens: Long = 0,
    val cacheMissTokens: Long = 0,
    val cost: Double? = null,
    val costUsd: Double? = null,
    val currency: String? = null,
)

// ── 审批 ──

@Serializable
data class ApprovalPayload(
    val id: String = "",
    val tool: String = "",
    val subject: String? = null,
)

// ── 提问卡片 ──

@Serializable
data class AskPayload(
    val id: String = "",
    val questions: List<AskQuestion> = emptyList(),
)

@Serializable
data class AskQuestion(
    val id: String = "",
    val prompt: String = "",
    val multi: Boolean = false,
    val options: List<AskOption> = emptyList(),
)

@Serializable
data class AskOption(
    val label: String = "",
    val description: String? = null,
)

// ── 压缩通知 ──

@Serializable
data class CompactionPayload(
    val trigger: String? = null,
    val summary: String? = null,
    val messages: Int = 0,
)

// ── 消息（历史记录用） ──

@Serializable
data class MessagePayload(
    val role: String = "",
    val content: String? = null,
    val reasoning: String? = null,
)

// ── 历史消息 ──

@Serializable
data class HistoryMessage(
    val role: String = "",
    val content: String? = null,
    val reasoning: String? = null,
    val toolCalls: List<ToolCallPayload>? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
)

@Serializable
data class ToolCallPayload(
    val id: String = "",
    val name: String = "",
    val arguments: String? = null,
)

// ── 会话 ──

@Serializable
data class SessionInfo(
    val name: String = "",
    val path: String = "",
    val current: Boolean = false,
    val title: String? = null,
    val turns: Int = 0,
)

// ── 状态 ──

@Serializable
data class StatusInfo(
    val label: String? = null,
    val plan: Boolean = false,
    val toolApprovalMode: String? = null,
    val autoApproveTools: Boolean? = null,
    val bypass: Boolean? = null,
    val used: Long = 0,
    val window: Long = 0,
    val cacheHit: Long = 0,
    val cacheMiss: Long = 0,
    val lastUsage: LastUsage? = null,
    val balance: BalanceInfo? = null,
)

@Serializable
data class LastUsage(
    val cost: Double? = null,
    val costUsd: Double? = null,
    val totalCost: Double? = null,
    val currency: String? = null,
)

@Serializable
data class BalanceInfo(
    val display: String? = null,
)

// ── 检查点 ──

@Serializable
data class CheckpointInfo(
    val turn: Int = 0,
    val prompt: String? = null,
    val files: Int = 0,
)

// ── /models 端点（运行时模型切换） ──

@Serializable
data class ReasonixModelsResponse(
    val current: String = "",
    val default: String = "",
    val label: String = "",
    val models: List<ReasonixModelInfo> = emptyList(),
)

@Serializable
data class ReasonixModelInfo(
    val ref: String = "",
    val provider: String = "",
    val model: String = "",
    val kind: String = "",
)
