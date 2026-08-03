package me.rerere.llamacpp

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import me.rerere.locallm.LocalRuntime
import me.rerere.locallm.LocalRuntimePreferences
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams generation from a locally loaded GGUF through [LlamaCppRuntime]. The prompt, the
 * grammar that constrains tool-call syntax, and the rules for parsing the reply all come from
 * the applied-template blob [LlamaCppRuntime.applyTemplate] returns; this provider treats that
 * blob as opaque, only ever handing it back to [LlamaCppRuntime.generate] and
 * [LlamaCppRuntime.parse], never parsing or rebuilding it.
 *
 * [streamText] resolves [TextGenerationParams.model]'s `modelId` - a bare file name, never a
 * path (see [LocalRuntimePreferences.installedModels]) - to the GGUF's absolute path, loads it
 * via [LlamaCppRuntime.load], and only then templates and generates. [loadedPath] tracks which
 * path is currently loaded so a repeat call for the same model is a no-op: reloading a
 * multi-GB model on every turn would make every message pay the load cost again.
 *
 * [LlamaCppRuntime.generate] blocks the calling thread for the whole generation, so it runs on
 * a child coroutine on [Dispatchers.IO] rather than inline in the flow body: cancelling a
 * coroutine only takes effect at a suspension point, and a blocking native call has none, so a
 * cancelled collector could not otherwise interrupt it. [awaitClose] runs concurrently with
 * that child and calls [LlamaCppRuntime.cancelGeneration] - the only way to interrupt a prefill,
 * per its doc - when the flow is cancelled before the generation finished on its own.
 */
class LlamaCppProvider(
    private val context: Context,
    private val runtime: LlamaCppRuntime,
    private val prefs: LocalRuntimePreferences,
) : Provider<ProviderSetting.LlamaCppLocal> {

    /** Path [runtime] currently has loaded, or null before the first call. Read and set by
     *  [ensureLoaded] so a repeat [streamText] call for the same model skips the reload. */
    private val loadedPath = AtomicReference<String?>(null)

    /** Serializes each [generateText]/[streamText] call's load-then-generate sequence into one
     *  atomic step, held from [ensureModelReady]'s already-loaded check through the generation
     *  that consumes the loaded model. [runtime] holds at most one model at a time, so releasing
     *  the lock right after loading (as a mutex guarding only [ensureModelReady] would) leaves a
     *  window where a second concurrent call - e.g. a post-turn generateTitle and
     *  generateSuggestion firing right after each other, configurable to different model ids -
     *  swaps [runtime] to its own model before the first call's generation reads it, feeding a
     *  mismatched template/tokenizer/grammar into native code. Mirrors LiteRtRuntime.streamTurns,
     *  which holds its own mutex for the whole inference so concurrent callers queue up rather
     *  than racing the loaded model. */
    private val loadMutex = Mutex()

    override suspend fun listModels(providerSetting: ProviderSetting.LlamaCppLocal): List<Model> =
        providerSetting.models

    override suspend fun generateText(
        providerSetting: ProviderSetting.LlamaCppLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = loadMutex.withLock {
        ensureModelReady(messages, params)
        generateFromLoadedModel(runtime, messages, params)
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.LlamaCppLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = flow {
        // loadMutex is held across the whole load-then-generate sequence, not just the load -
        // see the mutex's doc comment for why the generation itself must stay inside the lock.
        loadMutex.withLock {
            ensureModelReady(messages, params)
            emitAll(streamFromLoadedModel(runtime, messages, params))
        }
    }

    /** Resolves [TextGenerationParams.model]'s modelId to a path and loads it into [runtime]
     *  (a no-op when it is already loaded - see [ensureLoaded]). Shared by [generateText] and
     *  [streamText] so the two agree on exactly one load path; both already hold [loadMutex]
     *  before calling this. */
    private suspend fun ensureModelReady(messages: List<UIMessage>, params: TextGenerationParams) {
        val installed = prefs.installedModels(LocalRuntime.LlamaCpp)
        val modelPath = resolveModelPath(installed, params.model.modelId)
        // The load blocks the calling thread on a multi-gigabyte mmap, metadata parse and
        // context allocation, so it runs on Dispatchers.IO rather than whatever dispatcher the
        // caller used - mirrors LiteRtRuntime's engine init (LiteRtRuntime.kt:758). Without this,
        // a Main-confined caller like ProviderConnectionTester's rememberCoroutineScope would
        // load the model on the UI thread and ANR.
        withContext(Dispatchers.IO) {
            ensureLoaded(
                runtime = runtime,
                loadedPath = loadedPath,
                path = modelPath,
                tools = ChatRequestMapper.toolDeclarations(params.tools),
                systemPromptBytes = systemPromptBytesOf(messages),
                availableRamBytes = readAvailableRamBytes(),
            )
        }
    }

    /** Bytes of SYSTEM-role text across [messages] - the figure [LlamaCppRuntime.load] feeds
     *  `ContextPlanner` so the system prompt is accounted for before tools are fit into the
     *  context. */
    private fun systemPromptBytesOf(messages: List<UIMessage>): Int =
        messages
            .filter { it.role == MessageRole.SYSTEM }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .sumOf { it.text.toByteArray(Charsets.UTF_8).size }

    /** Free RAM to size the context against - the same `ActivityManager.MemoryInfo.availMem`
     *  source `MemoryGuard.canLoad` reads, so the planner and the load-time refusal agree on
     *  what "available" means. */
    private fun readAvailableRamBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getMemoryInfo(info)
        return info.availMem
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = error("llama.cpp does not support image generation")

    companion object {
        private const val DEFAULT_MAX_TOKENS = 2048

        /**
         * Resolves [modelId] - a bare file name, never a path - against [installed], the
         * `filename -> absolutePath` index [LocalRuntimePreferences.installedModels] returns.
         * Throws a user-readable, model-named error rather than letting a missing or stale
         * entry fail opaquely later on - mirrors `LiteRtProvider.streamText`'s guard.
         */
        internal fun resolveModelPath(installed: Map<String, String>, modelId: String): String {
            val path = installed[modelId]
                ?: throw IllegalStateException("Model $modelId not installed")
            if (!File(path).exists()) {
                throw IllegalStateException(
                    "Model file for \"$modelId\" is no longer present on disk ($path). " +
                        "Delete the model entry in Settings → Providers → Local · llama.cpp " +
                        "and re-download it."
                )
            }
            return path
        }

        /**
         * Loads [path] into [runtime] unless it is already the loaded model. Reloading a
         * multi-GB model on every turn is the obvious wrong implementation, so [loadedPath]
         * is checked first and only set to [path] after a real load succeeds.
         *
         * [LlamaCppRuntime.load] unconditionally unloads whatever was loaded before it
         * attempts [path], so the moment it is called the runtime no longer has
         * [loadedPath]'s old value loaded even if the new load then fails (e.g. an
         * oversized model throwing [ModelTooLargeException], or a corrupt file failing
         * natively). [loadedPath] is cleared to null right before that call so a failed
         * load leaves it reflecting "nothing loaded" rather than a model the runtime has
         * already discarded - otherwise a later call for that still-installed model would
         * see it as already loaded, skip the reload, and hit "no model is loaded" on the
         * very generation call this method exists to prevent.
         */
        internal suspend fun ensureLoaded(
            runtime: LlamaCppRuntime,
            loadedPath: AtomicReference<String?>,
            path: String,
            tools: List<ToolDeclaration>,
            systemPromptBytes: Int,
            availableRamBytes: Long,
        ) {
            if (loadedPath.get() == path) return
            loadedPath.set(null)
            runtime.load(path, tools, systemPromptBytes, availableRamBytes)
            loadedPath.set(path)
        }

        /**
         * [generateText]'s logic, given a [runtime] that already has the right model loaded
         * (see [ensureLoaded]). Every chunk [streamFromLoadedModel] emits carries an
         * incremental delta, not the full message, so the pieces are summed here rather than
         * just keeping the last one - mirrors LiteRtProvider.generateText. Kept as a function
         * of an explicit [runtime] parameter for the same testability reason as
         * [streamFromLoadedModel].
         */
        internal suspend fun generateFromLoadedModel(
            runtime: LlamaCppRuntime,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk {
            val text = StringBuilder()
            val reasoning = StringBuilder()
            val toolCalls = mutableListOf<UIMessagePart.Tool>()
            streamFromLoadedModel(runtime, messages, params).collect { chunk ->
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

        /**
         * The streaming/chunking logic, given a [runtime] that already has the right model
         * loaded (see [ensureLoaded]). Kept as a function of an explicit [runtime] parameter
         * rather than an instance method reading a provider's own field so it stays
         * unit-testable without a live [Context]/[LocalRuntimePreferences] - see
         * `LlamaCppProviderTest`.
         */
        internal fun streamFromLoadedModel(
            runtime: LlamaCppRuntime,
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
    }
}
