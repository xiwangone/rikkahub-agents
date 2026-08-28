package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.ui.UIMessagePart

/**
 * 思考步骤类型，用于分组 Reasoning、客户端 Tool 和 ServerTool
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
 * 分组策略(2026-08-25 调整):**Reasoning 单独成块**,不再与 Tool/ServerTool 混合。
 * - 连续的 Reasoning 归入同一个 ThinkingBlock(思考卡独立);
 * - 连续的 Tool/ServerTool 归入同一个 ThinkingBlock(工具链折叠);
 * - Reasoning 与 Tool/ServerTool 相邻时,各自独立、互不合并。
 * 这样思考不会"混进"工具折叠卡,二者在界面上分开显示。
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

            is UIMessagePart.ServerTool -> {
                currentThinkingSteps.add(ThinkingStep.ServerToolStep(part))
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
