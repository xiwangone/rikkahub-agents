package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.ui.UIMessagePart

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

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(
        val steps: List<ThinkingStep>,
    ) : MessagePartBlock

    data class ContentBlock(
        val part: UIMessagePart,
        val index: Int,
    ) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning 和 Tool 会被分组到一个 ThinkingBlock 中
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                currentThinkingSteps.add(ThinkingStep.ToolStep(part))
            }

            is UIMessagePart.ServerTool -> {
                currentThinkingSteps.add(ThinkingStep.ServerToolStep(part))
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}

/**
 * 与 [groupMessageParts] 相同的分组，但连续 Reasoning 与 Tool/ServerTool 各自独立成块，
 * 不合并到同一个 ThinkingBlock。供需要分离展示的路径使用。
 */
fun List<UIMessagePart>.groupMessagePartsSeparated(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var pendingReasoning = mutableListOf<UIMessagePart.Reasoning>()
    var pendingTools = mutableListOf<ThinkingStep>()

    fun flushReasoning() {
        if (pendingReasoning.isNotEmpty()) {
            result.add(
                MessagePartBlock.ThinkingBlock(
                    pendingReasoning.map { ThinkingStep.ReasoningStep(it) },
                ),
            )
            pendingReasoning = mutableListOf()
        }
    }

    fun flushTools() {
        if (pendingTools.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(pendingTools.toList()))
            pendingTools = mutableListOf()
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                // 思考前若累积了工具,先落盘工具块,保证思考不与工具混合
                flushTools()
                pendingReasoning.add(part)
            }

            is UIMessagePart.Tool -> {
                flushReasoning()
                pendingTools.add(ThinkingStep.ToolStep(part))
            }

            is UIMessagePart.ServerTool -> {
                flushReasoning()
                pendingTools.add(ThinkingStep.ServerToolStep(part))
            }

            else -> {
                flushReasoning()
                flushTools()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushReasoning()
    flushTools()
    return result
}
