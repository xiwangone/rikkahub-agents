package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

class TransformerContext(
    val context: Context,
    val model: Model,
    val assistant: Assistant,
    val settings: Settings,
    val conversationModeInjectionIds: Set<Uuid> = emptySet(),
    val conversationLorebookIds: Set<Uuid> = emptySet(),
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    val workspaceCwd: String? = null,
)

interface MessageTransformer {
    /**
     * 消息转换器，用于对消息进行转换
     *
     * 对于输入消息，消息会转换被提供给API模块
     *
     * 对于输出消息，会对消息输出chunk进行转换
     */
    suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }

    /**
     * 是否可以对**单条消息**独立做变换。
     *
     * 流式输出期间只有最后一条助手消息在变化，历史消息保持不变；声明为 true 后，
     * 输出路径可以复用历史消息的上次结果，把单块成本从 O(消息数) 降到 O(1)。
     *
     * 声明为 true 必须同时满足：
     *  1. 单条消息的变换结果只取决于该消息自身，不依赖同批次的其他消息；
     *  2. 变换不改变消息条数。
     *
     * 默认 false：任何不满足上述前提的变换器都会让输出路径**整体退回全量处理**，
     * 因此新增变换器时保持默认值即可，无需额外防护。
     */
    val supportsIncremental: Boolean get() = false
}

interface InputMessageTransformer : MessageTransformer

interface OutputMessageTransformer : MessageTransformer {
    /**
     * 一个视觉的转换，例如转换think tag为reasoning parts
     * 但是不实际转换消息，因为流式输出需要处理消息delta chunk
     * 不能还没结束生成就transform，因此提供一个visualTransform
     */
    suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }

    /**
     * 消息生成完成后调用
     */
    suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

suspend fun List<UIMessage>.transforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
    processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    workspaceCwd: String? = null,
): List<UIMessage> {
    val ctx = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
        processingStatus = processingStatus,
        workspaceCwd = workspaceCwd,
    )
    return transformers.fold(this) { acc, transformer ->
        transformer.transform(ctx, acc)
    }
}

suspend fun List<UIMessage>.visualTransforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings)
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            transformer.visualTransform(ctx, acc)
        } else {
            acc
        }
    }
}

/**
 * 增量输出变换的缓存：只保存「历史消息段」的变换结果。
 *
 * 生命周期与一个 step 的流式输出一致（step 边界清空），避免跨 step 上下文变化后脏读。
 * 只缓存历史段，末条消息每次都重算——它正是流式期间唯一在变的那条。
 */
class OutputTransformCache {
    private var headIds: List<Uuid>? = null
    private var headResult: List<UIMessage>? = null

    fun clear() {
        headIds = null
        headResult = null
    }

    internal fun cachedHead(ids: List<Uuid>): List<UIMessage>? =
        if (headIds == ids) headResult else null

    internal fun storeHead(
        ids: List<Uuid>,
        result: List<UIMessage>,
    ) {
        headIds = ids
        headResult = result
    }
}

/**
 * 输出变换的增量版本：历史消息段复用上次结果，只对末条消息重新变换。
 *
 * 前提由 [MessageTransformer.supportsIncremental] 声明；任一变送器未声明支持增量
 * （或变换改变了消息条数）时，整体退回 [visualTransforms] 的全量处理，保证语义一致。
 */
suspend fun List<UIMessage>.visualTransformsIncremental(
    transformers: List<MessageTransformer>,
    cache: OutputTransformCache,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    val outputs = transformers.filterIsInstance<OutputMessageTransformer>()
    // 局部 suspend 函数：普通 lambda 无法调用 suspend 的 visualTransforms
    suspend fun fullFallback(): List<UIMessage> =
        visualTransforms(transformers, context, model, assistant, settings)

    if (outputs.isEmpty() || outputs.any { !it.supportsIncremental } || size <= 1) {
        return fullFallback()
    }

    val head = dropLast(1)
    val headIds = head.map { it.id }
    val transformedHead =
        cache.cachedHead(headIds)
            ?: run {
                val fresh = head.visualTransforms(transformers, context, model, assistant, settings)
                if (fresh.size != head.size) return fullFallback()
                cache.storeHead(headIds, fresh)
                fresh
            }
    if (transformedHead.size != head.size) return fullFallback()

    val transformedTail = listOf(last()).visualTransforms(transformers, context, model, assistant, settings)
    if (transformedTail.size != 1) return fullFallback()

    return transformedHead + transformedTail
}

suspend fun List<UIMessage>.onGenerationFinish(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings)
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            transformer.onGenerationFinish(ctx, acc)
        } else {
            acc
        }
    }
}
