package me.rerere.llamacpp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams generation from a locally loaded GGUF through [LlamaCppRuntime]. The prompt, the
 * grammar that constrains tool-call syntax, and the rules for parsing the reply all come from
 * the applied-template blob [LlamaCppRuntime.applyTemplate] returns; this provider treats that
 * blob as opaque, only ever handing it back to [LlamaCppRuntime.generate] and
 * [LlamaCppRuntime.parse], never parsing or rebuilding it.
 *
 * [LlamaCppRuntime.generate] blocks the calling thread for the whole generation, so it runs on
 * a child coroutine on [Dispatchers.IO] rather than inline in the flow body: cancelling a
 * coroutine only takes effect at a suspension point, and a blocking native call has none, so a
 * cancelled collector could not otherwise interrupt it. [awaitClose] runs concurrently with
 * that child and calls [LlamaCppRuntime.cancelGeneration] - the only way to interrupt a prefill,
 * per its doc - when the flow is cancelled before the generation finished on its own.
 */
class LlamaCppProvider(
    private val runtime: LlamaCppRuntime,
) : Provider<ProviderSetting.LlamaCppLocal> {

    override suspend fun listModels(providerSetting: ProviderSetting.LlamaCppLocal): List<Model> =
        providerSetting.models

    override suspend fun generateText(
        providerSetting: ProviderSetting.LlamaCppLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        // Every chunk streamText emits carries an incremental delta, not the full message
        // (see the class doc), so the pieces have to be summed here rather than just
        // keeping the last one - mirrors LiteRtProvider.generateText.
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<UIMessagePart.Tool>()
        streamText(providerSetting, messages, params).collect { chunk ->
            chunk.choices.firstOrNull()?.delta?.parts?.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> text.append(part.text)
                    is UIMessagePart.Reasoning -> reasoning.append(part.reasoning)
                    is UIMessagePart.Tool -> toolCalls += part
                    else -> Unit
                }
            }
        }
        val parts = buildList {
            if (reasoning.isNotEmpty()) add(UIMessagePart.Reasoning(reasoning = reasoning.toString()))
            if (text.isNotEmpty()) add(UIMessagePart.Text(text.toString()))
            addAll(toolCalls)
        }
        return MessageChunk(
            id = "llamacpp-${System.currentTimeMillis()}",
            model = params.model.modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
                    finishReason = "stop",
                )
            ),
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.LlamaCppLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val streamId = "llamacpp-${System.currentTimeMillis()}"
        val modelId = params.model.modelId
        // Trim history to the input half of the planned context before templating, so a
        // long conversation drops its oldest turns instead of overflowing the prompt.
        val trimmedMessages = ChatRequestMapper.trimToBudget(messages, runtime.inputBudgetBytes())
        val appliedTemplateJson = runtime.applyTemplate(
            ChatRequestMapper.toRequestJson(trimmedMessages, params.tools)
        )
        val tracker = ChatDeltaTracker()
        val accumulated = StringBuilder()
        val finished = AtomicBoolean(false)

        // A plain captured lambda, not an extension function: it closes over this
        // callbackFlow's ProducerScope directly, so calling it from inside the doubly-nested
        // onPiece callback below needs no implicit-receiver resolution of its own.
        val sendDelta: (ChatDelta) -> Boolean = { delta ->
            val parts = buildList {
                if (delta.reasoningDelta.isNotEmpty()) {
                    add(UIMessagePart.Reasoning(reasoning = delta.reasoningDelta))
                }
                if (delta.textDelta.isNotEmpty()) {
                    add(UIMessagePart.Text(delta.textDelta))
                }
                delta.completedToolCalls.forEach { call ->
                    add(UIMessagePart.Tool(toolCallId = call.id, toolName = call.name, input = call.arguments))
                }
            }
            if (parts.isEmpty()) {
                !isClosedForSend
            } else {
                trySend(
                    MessageChunk(
                        id = streamId,
                        model = modelId,
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
                                message = null,
                                finishReason = null,
                            )
                        ),
                    )
                ).isSuccess
            }
        }

        val worker = launch(Dispatchers.IO) {
            try {
                runtime.generate(appliedTemplateJson, params.maxTokens ?: DEFAULT_MAX_TOKENS) { piece ->
                    accumulated.append(piece)
                    val parsed = runtime.parse(accumulated.toString(), true, appliedTemplateJson)
                    sendDelta(tracker.consume(parsed, isPartial = true))
                }

                // The final parse is authoritative: it flushes any tool call whose arguments
                // were still settling when generation stopped (see ChatDeltaTracker.consume).
                val finalParsed = runtime.parse(accumulated.toString(), false, appliedTemplateJson)
                sendDelta(tracker.consume(finalParsed, isPartial = false))
            } finally {
                finished.set(true)
                close()
            }
        }

        awaitClose {
            if (!finished.get()) {
                runtime.cancelGeneration()
            }
            worker.cancel()
        }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = error("llama.cpp does not support image generation")

    private companion object {
        const val DEFAULT_MAX_TOKENS = 2048
    }
}
