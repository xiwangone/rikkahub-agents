package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.TokenUsage

/**
 * Provider-independent streaming events.
 *
 * Providers are responsible for translating their wire protocol into these events. Consumers can therefore process
 * text, reasoning, tool calls and images without knowing which provider produced them.
 */
@Serializable
sealed class StreamChunk {
    @Serializable
    @SerialName("text_start")
    data class TextStart(val id: String) : StreamChunk()

    @Serializable
    @SerialName("text_delta")
    data class TextDelta(val id: String, val text: String) : StreamChunk()

    @Serializable
    @SerialName("text_end")
    data class TextEnd(val id: String) : StreamChunk()

    @Serializable
    @SerialName("reasoning_start")
    data class ReasoningStart(
        val id: String,
        val metadata: JsonObject? = null,
        val reasoningType: ReasoningType = ReasoningType.REASONING_TEXT,
    ) : StreamChunk()

    @Serializable
    @SerialName("reasoning_delta")
    data class ReasoningDelta(
        val id: String,
        val text: String,
        val metadata: JsonObject? = null,
        val reasoningType: ReasoningType = ReasoningType.REASONING_TEXT,
    ) : StreamChunk()

    @Serializable
    @SerialName("reasoning_end")
    data class ReasoningEnd(
        val id: String,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("tool_call_start")
    data class ToolCallStart(
        val id: String,
        val toolName: String = "",
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("tool_call_delta")
    data class ToolCallDelta(
        val id: String,
        val toolNameDelta: String = "",
        val inputDelta: String = "",
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("tool_call_end")
    data class ToolCallEnd(val id: String) : StreamChunk()

    @Serializable
    @SerialName("server_tool_start")
    data class ServerToolStart(
        val id: String,
        val toolName: String,
        val input: JsonElement? = null,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("server_tool_input_delta")
    data class ServerToolInputDelta(
        val id: String,
        val inputDelta: String,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("server_tool_input_end")
    data class ServerToolInputEnd(val id: String) : StreamChunk()

    @Serializable
    @SerialName("server_tool_end")
    data class ServerToolEnd(
        val id: String,
        val input: JsonElement? = null,
        val output: JsonElement? = null,
        val status: ServerToolStatus,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("image_start")
    data class ImageStart(
        val id: String,
        val mimeType: String = "image/png",
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("image_delta")
    data class ImageDelta(
        val id: String,
        val data: String,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    /** A complete renderable image snapshot that replaces previously received data for the same id. */
    @Serializable
    @SerialName("image_snapshot")
    data class ImageSnapshot(
        val id: String,
        val data: String,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("image_end")
    data class ImageEnd(val id: String) : StreamChunk()

    @Serializable
    @SerialName("annotations")
    data class Annotations(val annotations: List<UIMessageAnnotation>) : StreamChunk()

    @Serializable
    @SerialName("usage")
    data class Usage(val usage: TokenUsage) : StreamChunk()

    @Serializable
    @SerialName("finish")
    data class Finish(
        val finishReason: String? = null,
        val responseId: String? = null,
        val model: String? = null,
    ) : StreamChunk()

    /** 一个 turn 开始（多 turn 自动任务的轮次边界）。 */
    @Serializable
    @SerialName("turn_started")
    data class TurnStarted(val metadata: JsonObject? = null) : StreamChunk()

    /** 阶段指示（reasoning/planning/coding…），驱动对话顶栏/附属区阶段标签。 */
    @Serializable
    @SerialName("phase")
    data class Phase(val label: String) : StreamChunk()

    /** 服务端系统级通知/消息（如「开始工作」「任务完成」）。 */
    @Serializable
    @SerialName("notice")
    data class Notice(val text: String, val level: String? = null) : StreamChunk()

    /** 上下文压缩开始/结束。 */
    @Serializable
    @SerialName("compaction_started")
    data class CompactionStarted(val trigger: String? = null) : StreamChunk()

    @Serializable
    @SerialName("compaction_done")
    data class CompactionDone(val trigger: String? = null) : StreamChunk()

    /** 长工具的分步进度增量（与 ServerToolInputDelta 区分：这是工具执行中的人类可读进度）。 */
    @Serializable
    @SerialName("tool_progress")
    data class ToolProgress(val id: String, val message: String) : StreamChunk()

    /** 服务端向用户发起的提问（AskCard）。 */
    @Serializable
    @SerialName("ask_request")
    data class AskRequest(
        val id: String,
        val questions: List<AskQuestion> = emptyList(),
    ) : StreamChunk()

    /** 服务端请求工具执行审批。 */
    @Serializable
    @SerialName("approval_request")
    data class ApprovalRequest(
        val id: String,
        val tool: String = "",
        val subject: String? = null,
    ) : StreamChunk()
}

/** 提问卡片中的一个问题条目（provider-independent）。 */
@Serializable
data class AskQuestion(
    val id: String = "",
    val prompt: String = "",
    val multi: Boolean = false,
    val options: List<AskOption> = emptyList(),
)

/** 提问选项。 */
@Serializable
data class AskOption(
    val label: String = "",
    val description: String? = null,
)
