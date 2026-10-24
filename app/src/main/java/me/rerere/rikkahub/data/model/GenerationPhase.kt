package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Shared by live notifications and the background overlay. Approval is not execution. */
sealed interface GenerationPhase {
    data class AwaitingApproval(
        val toolName: String,
        val input: String,
    ) : GenerationPhase

    data class AwaitingAnswer(
        val input: String,
    ) : GenerationPhase

    data class RunningTool(
        val toolName: String,
        val input: String,
    ) : GenerationPhase

    data class Thinking(
        val reasoning: String,
    ) : GenerationPhase

    data class Writing(
        val text: String,
    ) : GenerationPhase

    data object Waiting : GenerationPhase
}

/** Notification outcome after a generation flow closes. A missing current-turn reply is never inferred from history. */
enum class CompletionNotification {
    NONE,
    COMPLETED,
    NEEDS_APPROVAL,
    NEEDS_ANSWER,
}

/** Pure policy shared with JVM tests; [completedNormally] is false for cancellation and failures. */
fun completionNotification(
    completedNormally: Boolean,
    hasCurrentTurnReply: Boolean,
    phase: GenerationPhase,
): CompletionNotification {
    if (!completedNormally || !hasCurrentTurnReply) return CompletionNotification.NONE
    return when (phase) {
        is GenerationPhase.AwaitingApproval -> CompletionNotification.NEEDS_APPROVAL
        is GenerationPhase.AwaitingAnswer -> CompletionNotification.NEEDS_ANSWER
        else -> CompletionNotification.COMPLETED
    }
}

fun UIMessage.generationPhase(): GenerationPhase = parts.generationPhase()

fun List<UIMessagePart>.generationPhase(): GenerationPhase {
    val tool = filterIsInstance<UIMessagePart.Tool>().lastOrNull { !it.isExecuted }
    val reasoning = filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
    val text = filterIsInstance<UIMessagePart.Text>().lastOrNull()
    val name =
        tool
            ?.toolName
            ?.let { raw ->
                if (raw.startsWith("mcp__")) raw.removePrefix("mcp__").substringAfter("__") else raw
            }.orEmpty()
    return when {
        tool?.isPending == true && tool.toolName == "ask_user" -> GenerationPhase.AwaitingAnswer(tool.input)
        tool?.isPending == true -> GenerationPhase.AwaitingApproval(name, tool.input)
        tool != null -> GenerationPhase.RunningTool(name, tool.input)
        reasoning != null && reasoning.finishedAt == null -> GenerationPhase.Thinking(reasoning.reasoning)
        text != null -> GenerationPhase.Writing(text.text)
        else -> GenerationPhase.Waiting
    }
}
