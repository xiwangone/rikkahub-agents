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
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock。
 *
 * 分组策略：连续 Reasoning 归入同一个 ThinkingBlock（思考独立成卡）；
 * 连续 Tool/ServerTool 归入另一个 ThinkingBlock（工具链折叠）；
 * 二者相邻时各自独立、互不合并。
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
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

