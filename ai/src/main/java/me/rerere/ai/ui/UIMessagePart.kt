package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
sealed class ToolApprovalState {
    @Serializable
    @SerialName("auto")
    data object Auto : ToolApprovalState()

    @Serializable
    @SerialName("pending")
    data object Pending : ToolApprovalState()

    @Serializable
    @SerialName("approved")
    data object Approved : ToolApprovalState()

    @Serializable
    @SerialName("denied")
    data class Denied(val reason: String = "") : ToolApprovalState()

    @Serializable
    @SerialName("answered")
    data class Answered(val answer: String) : ToolApprovalState()
}

fun ToolApprovalState.canResumeToolExecution(): Boolean {
    return when (this) {
        ToolApprovalState.Approved -> true
        is ToolApprovalState.Denied -> true
        is ToolApprovalState.Answered -> true
        ToolApprovalState.Auto,
        ToolApprovalState.Pending,
            -> false
    }
}

@Serializable
sealed class UIMessagePart {
    abstract val metadata: JsonObject?

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("video")
    data class Video(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("document")
    data class Document(
        val url: String,
        val fileName: String,
        val mime: String = "text/*",
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val reasoning: String,
        val createdAt: Instant = Clock.System.now(),
        val finishedAt: Instant? = Clock.System.now(),
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Deprecated("Deprecated")
    @Serializable
    @SerialName("search")
    data object Search : UIMessagePart() {
        override var metadata: JsonObject? = null
    }

    @Deprecated("Use UIMessagePart.Tool instead")
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        fun merge(other: ToolCall): ToolCall {
            return ToolCall(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                arguments = arguments + other.arguments,
                approvalState = approvalState,
                metadata = if (other.metadata != null) other.metadata else metadata,
            )
        }
    }

    @Deprecated("Use UIMessagePart.Tool instead")
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: JsonElement,
        val arguments: JsonElement,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("tool")
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val input: String,
        val output: List<UIMessagePart> = emptyList(),
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        /**
         * Unix-millisecond timestamp set by [GenerationHandler] right before it actually
         * starts running the tool's `execute` body. Persisted before execution begins so
         * that on a process kill mid-execute, the post-restart replay can detect that a
         * previous attempt started but didn't complete (output is empty + this is set)
         * and refuse to silently re-run the tool — re-running could double-charge a
         * remote, double-send a message, or duplicate any other side effect. Null means
         * "never started" (Approved-but-not-yet-tried).
         */
        val executionStartedAt: Long? = null,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        /** Whether the tool has been executed (has output) */
        val isExecuted: Boolean get() = output.isNotEmpty()

        /** Whether the tool is pending user approval */
        val isPending: Boolean get() = approvalState is ToolApprovalState.Pending

        /** Whether generation can resume and handle this tool immediately */
        val canResumeExecution: Boolean get() = !isExecuted && approvalState.canResumeToolExecution()

        /**
         * True iff a previous execution attempt was interrupted: approvalState is Approved,
         * output is empty, and executionStartedAt is set. The resume path uses this to
         * synthesise a "we don't know whether the side effect happened" Denied envelope
         * instead of re-running.
         */
        val isInterruptedAttempt: Boolean
            get() = approvalState is ToolApprovalState.Approved &&
                output.isEmpty() && executionStartedAt != null

        /** Parse input string as JsonElement */
        fun inputAsJson(): JsonElement = runCatching {
            json.parseToJsonElement(input.ifBlank { "{}" })
        }.getOrElse { JsonObject(emptyMap()) }

        fun merge(other: Tool): Tool {
            return Tool(
                toolCallId = toolCallId,
                toolName = if (other.toolName.isBlank()) toolName else other.toolName,
                input = input + other.input,
                output = output + other.output,
                approvalState = approvalState,
                executionStartedAt = executionStartedAt ?: other.executionStartedAt,
                metadata = if (other.metadata != null) other.metadata else metadata,
            )
        }
    }
}
