
package me.rerere.locallm.litert

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.locallm.AcceleratorProbe
import me.rerere.locallm.BuildConfig
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Wraps Google's LiteRT-LM runtime (`com.google.ai.edge.litertlm:litertlm-android`, version
 * pinned in the version catalog) for on-device inference of `.litertlm` model files.
 *
 * # Why this rewrite (vs. the simpler v22A original)
 *
 * The original implementation made three SDK-misuse mistakes Gallery's
 * `LlmChatModelHelper` does NOT make. Each was silently broken on production devices:
 *
 *   1. **Skipped `engine.initialize()`.** `Engine(config)` returns a partially-constructed
 *      handle; only `initialize()` actually loads tokenizer, KV cache, and weights into the
 *      backend.
 *   2. **Did not pass `EngineConfig.maxNumTokens`.** The SDK falls back to an internal default
 *      (~16) when `maxNumTokens` is null, so users saw "I" then nothing.
 *   3. **Inlined the system prompt as `User: …\nAssistant: …` plain text.** The LiteRT-LM
 *      runtime ships per-model chat templates; without `systemInstruction` going through the
 *      template engine, Qwen2.5 (and any chat-tuned model) emits gibberish.
 *
 * # KV-cache reuse across turns (the perf rewrite)
 *
 * The original recreated the [Conversation] on every [ensureLoaded] call, so every turn
 * re-prefilled the ENTIRE conversation history from a cold KV cache — turn N paid for
 * turns 1..N every time. Gallery instead keeps ONE Conversation alive and sends only the
 * new user message each turn, so the KV cache stays warm and each turn prefills only its
 * own new tokens.
 *
 * This runtime now does the same. The Conversation is kept across turns when the
 * (model, accelerator, sampler, system instruction) tuple is unchanged. [streamTurns]
 * compares the caller's full turn list against [LoadedModel.processed] (the signatures the
 * live Conversation has already consumed): a clean single-turn append reuses the warm KV
 * cache; anything else (a new chat, an edited/regenerated turn, a tool round-trip, a config
 * change) falls back to recreating the Conversation and re-sending the full history. The
 * cold path is always correct — the warm path is purely an optimisation, and a signature
 * mismatch can only cost a cold reload, never produce wrong output.
 *
 * # Concurrency
 *
 * A single [mutex] serialises all access. The mutex is held for the full duration of each
 * inference, so [LoadedModel.processed] is only ever touched by one coroutine at a time.
 */
class LiteRtRuntime(private val context: Context) {

    private val mutex = Mutex()
    private var loaded: LoadedModel? = null

    /**
     * In-session fallback accelerator. If the preferred/probed accelerator failed and we
     * successfully fell back to CPU, remember that result for the rest of this session so
     * subsequent loads skip the GPU-init attempt. Resets on app restart (acceptable for v1).
     */
    @Volatile private var sessionFallbackAccelerator: String? = null

    // ---- Durable accelerator-crash guard ----
    //
    // A GPU delegate that faults takes the whole process down with it. No exception ever
    // reaches Kotlin, so neither [sessionFallbackAccelerator] nor the CPU retry in
    // [ensureLoaded] gets a chance to run, and the next launch walks straight back into
    // the same crash. The only thing that outlives a SIGSEGV is a file: arm a marker
    // before handing a non-CPU backend to the native engine, disarm it once the model has
    // actually produced a token, and force CPU while a marker is still standing.
    //
    // Keyed on the SDK version so a runtime upgrade that fixes a device re-enables its GPU
    // instead of leaving it blacklisted forever.

    private val crashMarkers by lazy {
        context.getSharedPreferences("litert_accel_crash", Context.MODE_PRIVATE)
    }

    /** Key of the marker currently on disk, so repeat turns skip a redundant write. */
    @Volatile private var armedMarkerKey: String? = null

    private fun crashMarkerKey(modelPath: String, accel: String): String =
        "${BuildConfig.LITERTLM_SDK_VERSION}|$accel|${File(modelPath).name}"

    private fun armCrashMarker(modelPath: String, accel: String) {
        if (accel == "CPU") return
        val key = crashMarkerKey(modelPath, accel)
        if (armedMarkerKey == key) return
        // commit(), not apply(): a native fault can easily beat an async write to disk.
        crashMarkers.edit().putBoolean(key, true).commit()
        armedMarkerKey = key
    }

    private fun disarmCrashMarker() {
        val key = armedMarkerKey ?: return
        armedMarkerKey = null
        crashMarkers.edit().remove(key).commit()
    }

    private fun crashedPreviously(modelPath: String, accel: String): Boolean =
        accel != "CPU" && crashMarkers.getBoolean(crashMarkerKey(modelPath, accel), false)

    /** Last-known telemetry from [streamTurns]. Read by [LiteRtProvider] after each stream
     *  completes so the rolling perf samples can be persisted. Cleared on engine teardown. */
    @Volatile
    var lastTelemetry: StreamTelemetry? = null
        private set

    /**
     * Per-stream timing + token counts. `prefillTps` = input tokens / time from
     * `sendMessageAsync` to first `onMessage`; `decodeTps` = output tokens / time from
     * first onMessage to onDone. Token counts are character-based estimates (cumulative
     * String.length divided by ~4 chars/token) because the SDK does not surface a per-call
     * tokenizer counter — accurate within ~10% for English text, less for CJK.
     */
    data class StreamTelemetry(
        val prefillMs: Long,
        val decodeMs: Long,
        val inputCharCount: Int,
        val outputCharCount: Int,
        val specDecodingEngaged: Boolean,
    ) {
        val prefillTps: Double = if (prefillMs > 0)
            (inputCharCount.toDouble() / CHARS_PER_TOKEN) * 1000.0 / prefillMs else 0.0
        val decodeTps: Double = if (decodeMs > 0)
            (outputCharCount.toDouble() / CHARS_PER_TOKEN) * 1000.0 / decodeMs else 0.0

        companion object {
            /** Conservative estimate. Underestimates speed for English (true rate ~3.5
             *  chars/token) and overestimates for CJK (~1.5 chars/token), but consistent
             *  enough for relative speed comparisons across runs on the same model. */
            const val CHARS_PER_TOKEN: Int = 4
        }
    }

    // ---- Idle teardown ----
    //
    // The Engine + Conversation pin ~2-4 GB of native heap for the loaded model.
    // Keeping them warm is great for response latency on consecutive turns but expensive
    // for an app the user has wandered away from. After [idleTeardownDelayMs] of no
    // [streamTurns] activity, close the engine. Next request re-loads cold (10-60 s on
    // CPU). Configurable per-runtime via [setIdleTeardownDelayMs]; pass 0 to disable.

    @Volatile
    private var idleTeardownDelayMs: Long = TimeUnit.MINUTES.toMillis(15)

    @Volatile
    private var idleTeardownJob: kotlinx.coroutines.Job? = null

    private val idleScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    /** Set the idle window. 0 disables the watchdog (engine stays warm until manually
     *  closed). Negative values are clamped to 0. Honored on the next idle event. */
    fun setIdleTeardownDelayMs(ms: Long) {
        idleTeardownDelayMs = ms.coerceAtLeast(0L)
        // If a teardown was scheduled with the old delay, re-arm with the new one.
        armIdleTeardown()
    }

    /** Cancel any pending teardown and schedule a fresh one at the current delay. Called
     *  from the streamTurns finally so a new turn always resets the clock. */
    private fun armIdleTeardown() {
        idleTeardownJob?.cancel()
        val delay = idleTeardownDelayMs
        if (delay <= 0L || loaded == null) return
        idleTeardownJob = idleScope.launch {
            kotlinx.coroutines.delay(delay)
            mutex.withLock {
                val current = loaded ?: return@withLock
                val sinceLastUseMs = android.os.SystemClock.elapsedRealtime() - current.lastUseAtMs
                if (sinceLastUseMs >= delay) {
                    android.util.Log.i(
                        "LiteRtRuntime",
                        "armIdleTeardown: idle ${sinceLastUseMs}ms >= ${delay}ms; closing engine",
                    )
                    try { current.conversation.close() } catch (_: Throwable) {}
                    try { current.engine.close() } catch (_: Throwable) {}
                    loaded = null
                    lastTelemetry = null
                }
            }
        }
    }

    /**
     * Snapshot of every config bit that requires Engine teardown. The Engine is the
     * expensive resource (cold-load 10-60s); changing any of these forces a full reload.
     */
    private data class EngineKey(
        val modelPath: String,
        val accelerator: String,
        val maxNumTokens: Int,
        val supportImage: Boolean,
        val supportAudio: Boolean,
        val speculativeDecoding: Boolean,
        /** Per-model vision-backend choice. Mirrors Gallery's `ConfigKeys.VISION_ACCELERATOR`
         *  field. "gpu" / "cpu" / "npu". Default "gpu" matches Gallery's default; null
         *  is reserved for non-multimodal models where supportImage is also false. */
        val visionAccelerator: String,
    )

    /**
     * Snapshot of every config bit that requires only Conversation recreation (cheap) — NOT
     * an Engine reload. When this is unchanged the warm Conversation (and its KV cache) is
     * kept across [ensureLoaded] calls.
     */
    private data class ConversationKey(
        val systemInstructionText: String?,
        val constrainedDecoding: Boolean,
        val topK: Int,
        val topP: Double,
        val temperature: Double,
    )

    /** Everything needed to (re)build a [Conversation] for the cold path without re-deriving
     *  it from scattered call parameters. */
    private data class ConversationSpec(
        val systemInstruction: Contents?,
        val tools: List<ToolProvider>,
        val constrainedDecoding: Boolean,
        val topK: Int,
        val topP: Double,
        val temperature: Double,
    )

    private class LoadedModel(
        val engineKey: EngineKey,
        var conversationKey: ConversationKey,
        var conversationSpec: ConversationSpec,
        val engine: Engine,
        var conversation: Conversation,
        /** Captured from `Capabilities.hasSpeculativeDecodingSupport()` at engine build
         *  time. Persisted so we can surface it on warm-reuse paths too. */
        val fileSupportsSpeculativeDecoding: Boolean,
        /** True iff [engineKey.speculativeDecoding] was true at engine.initialize() time.
         *  Surfaced by [LoadOutcome.speculativeDecodingEngaged]. */
        val speculativeDecodingEngaged: Boolean,
        /** Signatures of the turns the live Conversation's KV cache currently holds, in
         *  order — the caller-supplied history turns plus one synthetic assistant turn for
         *  the response generated last. Cleared whenever the Conversation is recreated. */
        val processed: MutableList<String> = mutableListOf(),
        /** Monotonic timestamp of the last [streamTurns] invocation. The idle-teardown
         *  watchdog ([armIdleTeardown]) uses this to decide whether to close the engine. */
        @Volatile var lastUseAtMs: Long = android.os.SystemClock.elapsedRealtime(),
    )

    /**
     * Inspect a raw engine throwable and re-wrap it as [LiteRtModelCorruptException] if the
     * error message indicates a structural file problem that won't be fixed by switching
     * accelerators or retrying. Returns the original throwable unchanged for transient /
     * hardware errors so the GPU→CPU fallback still works.
     */
    private fun classifyEngineError(modelPath: String, t: Throwable): Throwable {
        // Walk the cause chain — the SDK's native exception is typically the innermost
        // cause; surrounding wrappers add their own (less specific) messages.
        val msg = generateSequence<Throwable>(t) { it.cause }
            .map { it.message.orEmpty() }
            .joinToString("\n")
        if (isVisionExecutorError(msg)) {
            return LiteRtVisionUnavailableException(modelPath, t)
        }
        val isCorrupt =
            msg.contains("Invalid magic number", ignoreCase = true) ||
            msg.contains("Failed to decompress", ignoreCase = true) ||
            msg.contains("No KV cache inputs found", ignoreCase = true) ||
            msg.contains("FAILED_PRECONDITION", ignoreCase = true) ||
            (msg.contains("INVALID_ARGUMENT", ignoreCase = true) &&
                (msg.contains("tokenizer", ignoreCase = true) ||
                 msg.contains("Section not found", ignoreCase = true) ||
                 msg.contains("Uncompressed size", ignoreCase = true)))
        return if (isCorrupt) LiteRtModelCorruptException(modelPath, t) else t
    }

    /**
     * Map our internal accelerator label → the SDK's [Backend] sealed-class instance.
     *
     * NPU/TPU both map to `Backend.NPU` with the app's native library dir, matching
     * Gallery's `LlmChatModelHelper.kt`: both labels share the QNN delegate loader path.
     */
    private fun acceleratorToBackend(accel: String): Backend = when (accel) {
        "QNN", "NPU", "TPU" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        "GPU" -> Backend.GPU()
        else -> Backend.CPU()
    }

    /**
     * Configure the engine + conversation for the next [streamTurns] call.
     *
     * Engine reuse: when every [EngineKey] field matches, the (expensive) Engine is kept.
     * Conversation reuse: when the [ConversationKey] ALSO matches, the warm Conversation —
     * and its KV cache — is kept untouched, so the next [streamTurns] can warm-continue.
     * When only the ConversationKey changed (system instruction / sampler), the Engine is
     * kept but the Conversation is recreated and [LoadedModel.processed] is cleared.
     *
     * When the preferred/probed accelerator fails (e.g. GPU OpenCL/OpenGL stack absent),
     * automatically retries with CPU. The fallback is remembered in-session.
     *
     * Returns the resolved accelerator label that was actually used.
     */
    private suspend fun fallbackLoadAfterFirstFailure(
        modelPath: String,
        accel: String,
        desiredEngineKey: EngineKey,
        desiredConversationSpec: ConversationSpec,
        firstError: Throwable,
    ): LoadResult {
        val classified = classifyEngineError(modelPath, firstError)
        if (classified is LiteRtVisionUnavailableException && desiredEngineKey.supportImage) {
            android.util.Log.w(
                "LiteRtRuntime",
                "Vision executor failed on $accel (${firstError.message}); retrying without vision backend (text-only)",
            )
            val textOnlyKey = desiredEngineKey.copy(supportImage = false)
            return runCatching {
                tryLoadWithBackend(textOnlyKey, accel, desiredConversationSpec)
            }.getOrElse { retryError ->
                throw classifyEngineError(
                    modelPath,
                    RuntimeException(
                        "LiteRT engine could not load this model even with vision disabled. Underlying: ${retryError.message}",
                        retryError,
                    ),
                )
            }
        }
        if (accel == "CPU") {
            throw classifyEngineError(
                modelPath,
                RuntimeException(
                    "LiteRT engine could not load this model on this device's GPU OR CPU. " +
                        "This usually means the model file is packaged for a different runtime version. " +
                        "Try a different model from the Gallery allowlist (tap Install default, or " +
                        "paste a litert-community/ HuggingFace URL). " +
                        "Underlying: ${firstError.message}",
                    firstError,
                ),
            )
        }
        if (classified is LiteRtModelCorruptException) throw classified

        android.util.Log.w(
            "LiteRtRuntime",
            "Engine init failed on $accel (${firstError.message}); retrying on CPU",
        )
        return runCatching {
            tryLoadWithBackend(desiredEngineKey.copy(accelerator = "CPU"), "CPU", desiredConversationSpec)
        }.getOrElse { cpuError ->
            fallbackLoadCpuTextOnlyOrThrow(modelPath, desiredEngineKey, desiredConversationSpec, cpuError)
        }
    }

    private suspend fun fallbackLoadCpuTextOnlyOrThrow(
        modelPath: String,
        desiredEngineKey: EngineKey,
        desiredConversationSpec: ConversationSpec,
        cpuError: Throwable,
    ): LoadResult {
        val cpuClassified = classifyEngineError(modelPath, cpuError)
        if (cpuClassified is LiteRtVisionUnavailableException && desiredEngineKey.supportImage) {
            android.util.Log.w(
                "LiteRtRuntime",
                "Vision executor failed on CPU too; retrying without vision backend",
            )
            val textOnlyCpuKey = desiredEngineKey.copy(accelerator = "CPU", supportImage = false)
            return runCatching {
                tryLoadWithBackend(textOnlyCpuKey, "CPU", desiredConversationSpec)
            }.getOrElse { lastError ->
                throw classifyEngineError(
                    modelPath,
                    RuntimeException(
                        "LiteRT engine could not load this model on this device even with vision disabled. Underlying: ${lastError.message}",
                        lastError,
                    ),
                )
            }
        }
        throw classifyEngineError(
            modelPath,
            RuntimeException(
                "LiteRT engine could not load this model on this device's GPU OR CPU. " +
                    "This usually means the model file is packaged for a different runtime version. " +
                    "Try a different model from the Gallery allowlist (tap Install default, or " +
                    "paste a litert-community/ HuggingFace URL). " +
                    "Underlying: ${cpuError.message}",
                cpuError,
            ),
        )
    }

    @OptIn(ExperimentalApi::class) // ExperimentalFlags.* + Capabilities.hasSpeculativeDecodingSupport
    suspend fun ensureLoaded(
        modelPath: String,
        preferredAccel: String? = null,
        forceCpu: Boolean = false,
        maxNumTokens: Int = 4096,
        supportImage: Boolean = false,
        supportAudio: Boolean = false,
        speculativeDecoding: Boolean = false,
        /** "gpu" | "cpu" | "npu". Defaults to "gpu" matching Gallery's
         *  DEFAULT_VISION_ACCELERATOR. Ignored when supportImage = false. */
        visionAccelerator: String = "gpu",
        systemInstructionText: String? = null,
        tools: List<ToolProvider> = emptyList(),
        constrainedDecoding: Boolean = false,
        topK: Int = 64,
        topP: Double = 0.95,
        temperature: Double = 1.0,
    ): LoadOutcome = mutex.withLock {
        // Use in-session fallback if a prior GPU→CPU retry already succeeded this session.
        // forceCpu wins over a non-null preferredAccel.
        val requestedAccel = if (forceCpu) "CPU"
        else sessionFallbackAccelerator
            ?: preferredAccel
            ?: AcceleratorProbe.probeLiteRt(context)
        // A prior run on this accelerator took the process down mid-inference. There is no
        // safe way to retry it, so stay on CPU until the SDK version changes.
        val accel = if (crashedPreviously(modelPath, requestedAccel)) {
            android.util.Log.w(
                "LiteRtRuntime",
                "ensureLoaded: $requestedAccel crashed the process on a previous run for " +
                    "${File(modelPath).name} (SDK ${BuildConfig.LITERTLM_SDK_VERSION}); using CPU",
            )
            sessionFallbackAccelerator = "CPU"
            "CPU"
        } else {
            requestedAccel
        }

        // Probe the file for speculative-decoding support BEFORE building the engine.
        val supportsSpeculativeDecoding = try {
            Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }
        } catch (_: Throwable) {
            false
        }
        val effectiveSpeculativeDecoding = speculativeDecoding && supportsSpeculativeDecoding

        val desiredEngineKey = EngineKey(
            modelPath = modelPath,
            accelerator = accel,
            maxNumTokens = maxNumTokens,
            supportImage = supportImage,
            supportAudio = supportAudio,
            speculativeDecoding = effectiveSpeculativeDecoding,
            visionAccelerator = visionAccelerator,
        )
        val systemInstruction: Contents? =
            if (!systemInstructionText.isNullOrBlank()) Contents.of(systemInstructionText) else null
        val desiredConversationKey = ConversationKey(
            systemInstructionText = systemInstructionText?.takeIf { it.isNotBlank() },
            constrainedDecoding = constrainedDecoding,
            topK = topK,
            topP = topP,
            temperature = temperature,
        )
        val desiredConversationSpec = ConversationSpec(
            systemInstruction = systemInstruction,
            tools = tools,
            constrainedDecoding = constrainedDecoding,
            topK = topK,
            topP = topP,
            temperature = temperature,
        )

        val current = loaded
        if (current != null && current.engineKey == desiredEngineKey) {
            if (current.conversationKey == desiredConversationKey) {
                // Full reuse — Engine AND Conversation kept. The KV cache (and therefore
                // [processed]) is left intact so the next streamTurns can warm-continue.
                return@withLock LoadOutcome(
                    accelerator = accel,
                    visionEnabled = current.engineKey.supportImage,
                    visionFellBackToTextOnly = false,
                    fileSupportsSpeculativeDecoding = current.fileSupportsSpeculativeDecoding,
                    speculativeDecodingEngaged = current.speculativeDecodingEngaged,
                )
            }
            // Engine kept, Conversation config changed — recreate just the Conversation.
            // The KV cache is gone, so [processed] must be cleared.
            try { current.conversation.close() } catch (_: Throwable) {}
            current.conversation = createConversationWithFlags(
                engine = current.engine,
                backend = acceleratorToBackend(current.engineKey.accelerator),
                spec = desiredConversationSpec,
            )
            current.conversationKey = desiredConversationKey
            current.conversationSpec = desiredConversationSpec
            current.processed.clear()
            return@withLock LoadOutcome(
                accelerator = accel,
                visionEnabled = current.engineKey.supportImage,
                visionFellBackToTextOnly = false,
                fileSupportsSpeculativeDecoding = current.fileSupportsSpeculativeDecoding,
                speculativeDecodingEngaged = current.speculativeDecodingEngaged,
            )
        }

        // Engine swap path: tear down any prior Engine + Conversation.
        try { current?.conversation?.close() } catch (_: Throwable) {}
        try { current?.engine?.close() } catch (_: Throwable) {}
        loaded = null

        // Try the preferred accelerator first; fall back to CPU if it isn't already CPU.
        // Arm the durable marker first: engine init is one of the two places a broken GPU
        // delegate can fault, and a fault here leaves nothing for the runCatching to see.
        armCrashMarker(modelPath, accel)
        val firstAttempt = runCatching {
            tryLoadWithBackend(desiredEngineKey, accel, desiredConversationSpec)
        }
        val loadResult = firstAttempt.getOrElse { firstError ->
            fallbackLoadAfterFirstFailure(modelPath, accel, desiredEngineKey, desiredConversationSpec, firstError)
        }

        // If we fell back to CPU from a non-CPU accelerator, cache that decision in-session.
        if (loadResult.accelerator != accel) {
            sessionFallbackAccelerator = loadResult.accelerator
        }

        loaded = LoadedModel(
            engineKey = desiredEngineKey.copy(
                accelerator = loadResult.accelerator,
                supportImage = loadResult.supportImage,
            ),
            conversationKey = desiredConversationKey,
            conversationSpec = desiredConversationSpec,
            engine = loadResult.engine,
            conversation = loadResult.conversation,
            fileSupportsSpeculativeDecoding = supportsSpeculativeDecoding,
            speculativeDecodingEngaged = effectiveSpeculativeDecoding,
        )
        android.util.Log.i(
            "LiteRtRuntime",
            "ensureLoaded: accel=${loadResult.accelerator} visionEnabled=${loadResult.supportImage} " +
                "fileSupportsSpecDecoding=$supportsSpeculativeDecoding " +
                "specDecodingEngaged=$effectiveSpeculativeDecoding",
        )
        LoadOutcome(
            accelerator = loadResult.accelerator,
            visionEnabled = loadResult.supportImage,
            visionFellBackToTextOnly = desiredEngineKey.supportImage && !loadResult.supportImage,
            fileSupportsSpeculativeDecoding = supportsSpeculativeDecoding,
            speculativeDecodingEngaged = effectiveSpeculativeDecoding,
        )
    }

    private class LoadResult(
        val engine: Engine,
        val conversation: Conversation,
        val accelerator: String,
        /** The resolved supportImage value (may differ from the requested value when the
         *  runtime had to fall back to text-only after the vision executor failed). */
        val supportImage: Boolean,
    )

    /**
     * Result returned by [ensureLoaded]. Tells the caller (a) which accelerator the engine
     * ended up on, (b) whether the vision encoder is live or had to be dropped to
     * text-only mode to get the model loaded, and (c) whether speculative decoding was
     * available on the file and engaged for this load. The provider persists
     * [visionFellBackToTextOnly] so future loads skip the doomed GPU vision attempt; the
     * Doctor uses the speculative-decoding fields to surface a regression (file capability
     * vanishing after an SDK bump) visibly.
     */
    data class LoadOutcome(
        val accelerator: String,
        val visionEnabled: Boolean,
        val visionFellBackToTextOnly: Boolean,
        /** True iff `Capabilities.hasSpeculativeDecodingSupport()` returned true for the
         *  model file. */
        val fileSupportsSpeculativeDecoding: Boolean,
        /** True iff the caller requested speculative decoding AND the file supports it
         *  AND the SDK's experimental flag was set during engine init. */
        val speculativeDecodingEngaged: Boolean,
    )

    /**
     * Build + initialize an Engine, then open a Conversation. Throws if either step fails.
     *
     * Mirrors Gallery's `LlmChatModelHelper.initialize()`:
     *   - EngineConfig with explicit `maxNumTokens`
     *   - `cacheDir` only when modelPath sits in `/data/local/tmp`
     *   - the `enableSpeculativeDecoding` flag dance around construct + initialize
     */
    @OptIn(ExperimentalApi::class)
    private suspend fun tryLoadWithBackend(
        engineKey: EngineKey,
        accel: String,
        conversationSpec: ConversationSpec,
    ): LoadResult {
        val backend = acceleratorToBackend(accel)
        // Vision backend MUST be GPU for Gemma 3n; audio backend MUST be CPU (Gallery's
        // mandate). Leave each null when the caller didn't request that modality so the
        // engine doesn't allocate the corresponding executor memory.
        // Vision backend mirrors Gallery's `LlmChatModelHelper`: configurable per model
        // via the visionAccelerator label. GPU is default (Gemma 3n / 4 train with GPU
        // vision); CPU and NPU are valid alternates the SDK accepts.
        val visionBackend: Backend? = if (engineKey.supportImage) {
            when (engineKey.visionAccelerator) {
                "cpu" -> Backend.CPU()
                "npu", "tpu" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
                else -> Backend.GPU()
            }
        } else null
        val audioBackend: Backend? = if (engineKey.supportAudio) Backend.CPU() else null

        // cacheDir: matches Google AI Edge Gallery's LlmChatModelHelper exactly. Only set
        // when the model path is the ADB push debug location (/data/local/tmp). For
        // installed models (filesDir/local-models/...) the SDK does its own cache
        // management internally; passing a non-null cacheDir for those paths diverges
        // from Gallery's reference build with unknown side effects on vision-backend init.
        val engineConfig = EngineConfig(
            modelPath = engineKey.modelPath,
            backend = backend,
            visionBackend = visionBackend,
            audioBackend = audioBackend,
            maxNumTokens = engineKey.maxNumTokens,
            cacheDir = if (engineKey.modelPath.startsWith("/data/local/tmp"))
                context.getExternalFilesDir(null)?.absolutePath
            else null,
        )

        val engine = withContext(Dispatchers.IO) {
            // The flag dance: set BEFORE constructing the Engine, reset AFTER initialize().
            ExperimentalFlags.enableSpeculativeDecoding = engineKey.speculativeDecoding
            val e = try {
                Engine(engineConfig).also { built ->
                    runCatching { built.initialize() }.onFailure { t ->
                        runCatching { built.close() }
                        throw t
                    }
                }
            } finally {
                ExperimentalFlags.enableSpeculativeDecoding = false
            }
            e
        }

        val conv = createConversationWithFlags(
            engine = engine,
            backend = backend,
            spec = conversationSpec,
        )
        return LoadResult(engine, conv, accel, engineKey.supportImage)
    }

    /**
     * Build a Conversation with the constrained-decoding flag dance.
     *
     * Sampler choice mirrors Gallery:
     *   - NPU backend → samplerConfig MUST be null.
     *   - GPU/CPU backend → explicit SamplerConfig with the caller's topK/topP/temperature.
     */
    @OptIn(ExperimentalApi::class)
    private fun createConversationWithFlags(
        engine: Engine,
        backend: Backend,
        spec: ConversationSpec,
    ): Conversation {
        ExperimentalFlags.enableConversationConstrainedDecoding = spec.constrainedDecoding
        return try {
            engine.createConversation(
                ConversationConfig(
                    samplerConfig = if (backend is Backend.NPU) {
                        null
                    } else {
                        SamplerConfig(
                            topK = spec.topK,
                            topP = spec.topP,
                            temperature = spec.temperature,
                        )
                    },
                    systemInstruction = spec.systemInstruction,
                    tools = spec.tools,
                    // Surface tool calls instead of dispatching them. The SDK would happily
                    // run them itself, but that path skips the host's approval prompt, the
                    // auto-approve allowlist, the HARDLINE floor, and the per-turn
                    // wall-clock budget. [LiteRtProvider] republishes the calls as ordinary
                    // tool parts so the normal execution path applies.
                    automaticToolCalling = false,
                )
            )
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
    }

    /** Close + rebuild the live Conversation in place, clearing its KV cache. Caller must
     *  hold [mutex] and must clear [LoadedModel.processed] itself. */
    private fun recreateConversationLocked(instance: LoadedModel) {
        try { instance.conversation.close() } catch (_: Throwable) {}
        instance.conversation = createConversationWithFlags(
            engine = instance.engine,
            backend = acceleratorToBackend(instance.engineKey.accelerator),
            spec = instance.conversationSpec,
        )
    }

    /**
     * Stream one assistant response.
     *
     * [history] is the FULL turn list for the conversation (already trimmed to the model's
     * context budget by the caller). The runtime decides — via [planTurns] — whether the
     * live Conversation's KV cache already holds a prefix of [history]:
     *
     *  - **Warm:** the cache holds everything except exactly one newly-appended turn → send
     *    only that turn's [Turn.rawText]; the SDK's chat template applies role wrapping and
     *    the prior turns are reused from the cache (Gallery's behaviour).
     *  - **Cold:** anything else (first turn, config change, edited/regenerated history,
     *    a tool round-trip, media inputs) → recreate the Conversation and send [coldBlob],
     *    the caller's full marker-formatted render of the history.
     *
     * Each emitted String is the **cumulative** response so far, NOT a delta — same contract
     * as Gallery's `partialResult`. Downstream consumers compute deltas themselves.
     *
     * Caller MUST have called [ensureLoaded] first. The [mutex] is held for the whole
     * inference so two concurrent callers queue up rather than racing the Conversation.
     */
    fun streamTurns(
        history: List<Turn>,
        coldBlob: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onThinking: ((String) -> Unit)? = null,
    ): Flow<StreamEvent> = callbackFlow {
        // GPU-boost the inference. Three levers, in order of effectiveness:
        //   1. PerformanceHintManager (API 33+). Opens a hint session for this thread
        //      with a 50ms target — the OS scheduler treats the process as doing
        //      sustained compute and refuses to downclock GPU/CPU like it would for a
        //      productivity app. This is exactly what Android game engines use.
        //   2. THREAD_PRIORITY_URGENT_DISPLAY on the calling thread. LiteRT's native
        //      worker threads inherit nice values from their creator; boosting our
        //      thread propagates a higher scheduling priority into the compute work.
        //   3. (Manifest) game_mode_config.xml declares we accept PERFORMANCE mode,
        //      so OEM game-mode frameworks (Adreno GPP, Mali frame rate control,
        //      Tensor Game Mode) also bump GPU clocks + relax DCVS throttling.
        // All cleaned up in the finally so an idle app doesn't keep the boost.
        val callerTid = Process.myTid()
        val originalPriority = runCatching {
            Process.getThreadPriority(callerTid)
        }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
        val hintSession = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val phm = context.getSystemService(PerformanceHintManager::class.java)
                phm?.createHintSession(intArrayOf(callerTid), 50_000_000L)  // 50 ms target
            }.getOrNull()
        } else null
        runCatching {
            Process.setThreadPriority(callerTid, Process.THREAD_PRIORITY_URGENT_DISPLAY)
        }

        try { mutex.withLock {
            val instance = loaded
                ?: throw IllegalStateException("Call ensureLoaded(...) before streamTurns()")

            val hasMedia = images.isNotEmpty() || audioClips.isNotEmpty()
            val historySignatures = history.map { it.signature }
            val plan = planTurns(instance.processed, historySignatures, hasMedia)

            val inputText: String = when (plan) {
                is TurnPlan.Warm -> history[plan.sendFromIndex].rawText
                TurnPlan.Cold -> {
                    // ensureLoaded may have kept a warm Conversation that this turn cannot
                    // reuse (a /new, an edit, a regeneration, a tool round-trip, or media).
                    // Recreate it to clear the KV cache, then send the full history.
                    recreateConversationLocked(instance)
                    instance.processed.clear()
                    coldBlob
                }
            }

            val conv = instance.conversation
            // The other place a broken GPU delegate faults: it can survive engine init and
            // only die once real work is submitted. Re-arm for every turn; the first token
            // below disarms.
            armCrashMarker(instance.engineKey.modelPath, instance.engineKey.accelerator)
            // Build Contents in Gallery's order: images and audio first, text last.
            val contentList = mutableListOf<Content>()
            for (image in images) contentList.add(Content.ImageBytes(image.toPngByteArray()))
            for (clip in audioClips) contentList.add(Content.AudioBytes(clip))
            if (inputText.trim().isNotEmpty()) contentList.add(Content.Text(inputText))

            // Telemetry. We measure character counts as a proxy for token counts (the SDK
            // does not surface a per-call tokenizer counter). The cold-path text input
            // includes the full history + system prompt; the warm path is just the new
            // turn's raw text. Either way `inputText.length` is the prefill character
            // budget for this call.
            val telemetryInputChars = inputText.length
            val callStartedNs = System.nanoTime()
            // Set once the SDK reports the generation finished (either way). Read from the
            // flow's coroutine in awaitClose to tell a natural end from a cancellation.
            val generationFinished = java.util.concurrent.atomic.AtomicBoolean(false)
            var firstMessageNs: Long = 0L
            var lastCumulative = ""
            // How many of the message's tool calls have already been forwarded. The SDK
            // re-delivers the full list on each callback, so forward only the new tail.
            var emittedToolCalls = 0
            conv.sendMessageAsync(
                Contents.of(contentList),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (firstMessageNs == 0L) {
                            firstMessageNs = System.nanoTime()
                            // The accelerator got through init and prefill and produced a
                            // token, so it is not the one that kills the process.
                            disarmCrashMarker()
                        }
                        message.channels["thought"]?.let { thinking ->
                            if (thinking.isNotEmpty()) onThinking?.invoke(thinking)
                        }
                        val text = message.toString()
                        if (text.isNotEmpty()) {
                            lastCumulative = text
                            trySend(StreamEvent.Delta(text))
                        }
                        val calls = message.toolCalls
                        if (calls.size > emittedToolCalls) {
                            val fresh = calls.drop(emittedToolCalls).map { call ->
                                RuntimeToolCall(
                                    name = call.name,
                                    argumentsJson = argumentsToJson(call.arguments),
                                )
                            }
                            emittedToolCalls = calls.size
                            trySend(StreamEvent.ToolCalls(fresh))
                        }
                    }
                    override fun onDone() {
                        // The Conversation's KV cache now holds [history…] + the assistant
                        // turn just generated. Record that so the NEXT call can warm-continue
                        // if it is a clean single-turn append. Mutating [processed] here is
                        // safe: the mutex is held for this whole streamTurns invocation.
                        instance.processed.clear()
                        instance.processed.addAll(historySignatures)
                        instance.processed.add(turnSignature(ROLE_ASSISTANT, lastCumulative))
                        // Stamp telemetry for the provider to read + persist.
                        val endNs = System.nanoTime()
                        val prefillMs = if (firstMessageNs > 0L)
                            (firstMessageNs - callStartedNs) / 1_000_000L else 0L
                        val decodeMs = if (firstMessageNs > 0L)
                            (endNs - firstMessageNs) / 1_000_000L else 0L
                        lastTelemetry = StreamTelemetry(
                            prefillMs = prefillMs,
                            decodeMs = decodeMs,
                            inputCharCount = telemetryInputChars,
                            outputCharCount = lastCumulative.length,
                            specDecodingEngaged = instance.speculativeDecodingEngaged,
                        )
                        instance.lastUseAtMs = android.os.SystemClock.elapsedRealtime()
                        generationFinished.set(true)
                        close()
                    }
                    override fun onError(throwable: Throwable) {
                        // The KV-cache state is now unknown — force the next turn cold.
                        instance.processed.clear()
                        instance.lastUseAtMs = android.os.SystemClock.elapsedRealtime()
                        generationFinished.set(true)
                        if (throwable is CancellationException) close() else close(throwable)
                    }
                },
                emptyMap(),
            )
            awaitClose {
                // Reached either because the SDK finished (the callbacks close the channel
                // above) or because the collector went away first: the user hit stop, or
                // the surface collecting this flow was torn down.
                //
                // In the second case the native generation is STILL RUNNING: it keeps the
                // GPU busy writing into a channel nobody reads, and because the mutex is
                // released as soon as this block returns, the next turn can call
                // sendMessageAsync on a Conversation that has not finished the previous
                // one. Cancel it so stopping actually stops.
                if (!generationFinished.get()) {
                    android.util.Log.i(
                        "LiteRtRuntime",
                        "stream cancelled before completion; cancelling native generation",
                    )
                    runCatching { conv.cancelProcess() }
                        .onFailure {
                            android.util.Log.w("LiteRtRuntime", "cancelProcess failed", it)
                        }
                    // The KV cache now holds a partial turn, so the next turn must go cold.
                    instance.processed.clear()
                }
            }
        } } finally {
            runCatching { hintSession?.close() }
            runCatching { Process.setThreadPriority(callerTid, originalPriority) }
            // Arm (or re-arm) the idle teardown. A new turn cancels the prior schedule and
            // starts a fresh window from now; no turn keeps the existing one ticking.
            armIdleTeardown()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Cancel the currently-running generation, if any. Safe to call when nothing is
     * generating. Does NOT tear down the Engine or Conversation; the runtime stays warm.
     */
    fun stop() {
        try { loaded?.conversation?.cancelProcess() } catch (_: Throwable) {}
    }

    /**
     * Tear down the currently loaded engine + conversation, if any.
     */
    suspend fun closeIfLoaded() {
        idleTeardownJob?.cancel()
        idleTeardownJob = null
        mutex.withLock {
            try { loaded?.conversation?.close() } catch (_: Throwable) {}
            try { loaded?.engine?.close() } catch (_: Throwable) {}
            loaded = null
            lastTelemetry = null
        }
    }

    companion object {
        internal fun planTurns(
            processed: List<String>,
            historySignatures: List<String>,
            hasMedia: Boolean,
        ): TurnPlan = planTurnsImpl(processed, historySignatures, hasMedia)

        internal fun argumentsToJson(arguments: Map<String, Any?>): String =
            argumentsToJsonImpl(arguments)

        internal fun isVisionExecutorError(joinedMessage: String): Boolean =
            isVisionExecutorErrorImpl(joinedMessage)
    }
}
