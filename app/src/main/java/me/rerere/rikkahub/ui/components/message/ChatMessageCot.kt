package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

internal const val CHART_DISPLAY_TOOL_NAME = "chart_display"

/**
 * 工具输出尾部的审批来源标记行前缀（生成端见 GenerationLoop.appendApprovalProvenance）。
 * 解析工具输出负载前需剔除该行：标记与负载同属一个文本块。
 */
private const val APPROVAL_MARKER_PREFIX = "[approval:"

/**
 * 剔除工具输出尾部的审批来源标记行，返回可解析的负载文本。
 *
 * 解析工具 output 前必须经过本函数：标记与负载同处一个文本块，直接整段解析会失败
 * （chart_display 因此不渲染，表现为工具卡里只有 JSON）。
 */
internal fun stripApprovalProvenance(outputText: String): String =
    outputText.lineSequence()
        .filterNot { it.trim().startsWith(APPROVAL_MARKER_PREFIX) }
        .joinToString("\n")

/**
 * 思考步骤类型，用于分组 Reasoning 和 Tool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep

    data class ServerToolStep(
        val tool: UIMessagePart.ServerTool,
    ) : ThinkingStep
}

/** A stable, unique Compose key within one timeline, even for repeated or blank tool IDs. */
internal fun timelineStepComposeKey(index: Int, step: ThinkingStep): String =
    when (step) {
        is ThinkingStep.ReasoningStep -> "reasoning:${step.reasoning.createdAt}:$index"
        is ThinkingStep.ToolStep -> "tool:${step.tool.toolCallId}:$index"
        is ThinkingStep.ServerToolStep -> "server-tool:${step.tool.toolCallId}:$index"
    }

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(
        val steps: List<ThinkingStep>,
        /** Original message-part position, stable as streamed parts are appended. */
        val startIndex: Int,
    ) : MessagePartBlock

    data class ContentBlock(
        val part: UIMessagePart,
        val index: Int,
    ) : MessagePartBlock

    /** 成功执行的 chart_display 工具调用, 在正文中以图表卡片展示 */
    data class ChartBlock(
        val tool: UIMessagePart.Tool,
        val index: Int,
    ) : MessagePartBlock
}

/**
 * 将连续的 Reasoning、Tool 和 ServerTool 合并成一个时间线块。普通正文和成功图表
 * 会结束当前块；成功 chart_display 仍在原位置渲染为独立图表卡片。
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var pendingSteps = mutableListOf<ThinkingStep>()
    var pendingStartIndex: Int? = null

    fun appendStep(step: ThinkingStep, partIndex: Int) {
        if (pendingSteps.isEmpty()) pendingStartIndex = partIndex
        pendingSteps.add(step)
    }

    fun flushSteps() {
        if (pendingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(pendingSteps.toList(), pendingStartIndex!!))
            pendingSteps = mutableListOf()
            pendingStartIndex = null
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> appendStep(ThinkingStep.ReasoningStep(part), index)

            is UIMessagePart.Tool -> {
                if (part.isSuccessfulChartDisplay()) {
                    flushSteps()
                    result.add(MessagePartBlock.ChartBlock(part, index))
                } else {
                    appendStep(ThinkingStep.ToolStep(part), index)
                }
            }

            is UIMessagePart.ServerTool -> appendStep(ThinkingStep.ServerToolStep(part), index)

            else -> {
                flushSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushSteps()
    return result
}

private fun UIMessagePart.Tool.isSuccessfulChartDisplay(): Boolean {
    if (toolName != CHART_DISPLAY_TOOL_NAME || !isExecuted) return false
    val outputText = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    // 工具输出尾部会追加审批来源标记行（如 [approval: auto]），与负载同处一个文本块；
    // 不剔除则整段 JSON 解析失败 → 图表不渲染。
    val payload = stripApprovalProvenance(outputText)
    val result = runCatching { JsonInstant.parseToJsonElement(payload) }.getOrNull() as? JsonObject
    return (result?.get("success") as? JsonPrimitive)?.booleanOrNull == true
}
