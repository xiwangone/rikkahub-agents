package me.rerere.llamacpp

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class LlamaCppProviderTest {

    /** Streams a fixed reply so the provider's chunking is testable without native code. */
    private class ScriptedNative(private val pieces: List<String>) : LlamaCppNative {
        override fun loadModel(path: String) = 1L
        override fun freeModel(handle: Long) {}
        override fun modelInfo(handle: Long): String = """
            {"n_layers":26,"n_embd":2048,"n_head_kv":4,"n_embd_head_k":256,
             "n_embd_head_v":256,"n_vocab":262144,"n_ctx_train":32768,
             "sliding_window":0,"weights_bytes":2600000000}
        """.trimIndent()
        override fun createContext(
            modelHandle: Long, nCtx: Int, nBatch: Int, nUBatch: Int,
            cacheTypeK: String, cacheTypeV: String, nThreads: Int,
        ) = 2L
        override fun freeContext(handle: Long) {}
        override fun cancelGeneration(handle: Long) {}
        override fun applyTemplate(modelHandle: Long, requestJson: String): String = "{}"
        override fun generate(
            ctxHandle: Long, modelHandle: Long, appliedTemplateJson: String,
            maxTokens: Int, onPiece: (String) -> Boolean,
        ) {
            for (piece in pieces) {
                if (!onPiece(piece)) return
            }
        }
        // Mirrors llama.cpp: each parse restates the whole message so far.
        override fun parseChat(text: String, isPartial: Boolean, appliedTemplateJson: String): String =
            """{"role":"assistant","content":"$text","reasoning_content":"","tool_calls":[]}"""
    }

    /** Like [ScriptedNative], but counts [loadModel] calls so a test can prove a repeat
     *  [LlamaCppProvider.ensureLoaded] for the same path does not touch native code again. */
    private class CountingNative : LlamaCppNative {
        var loadModelCallCount = 0
            private set

        override fun loadModel(path: String): Long {
            loadModelCallCount++
            return 1L
        }
        override fun freeModel(handle: Long) {}
        override fun modelInfo(handle: Long): String = """
            {"n_layers":26,"n_embd":2048,"n_head_kv":4,"n_embd_head_k":256,
             "n_embd_head_v":256,"n_vocab":262144,"n_ctx_train":32768,
             "sliding_window":0,"weights_bytes":2600000000}
        """.trimIndent()
        override fun createContext(
            modelHandle: Long, nCtx: Int, nBatch: Int, nUBatch: Int,
            cacheTypeK: String, cacheTypeV: String, nThreads: Int,
        ) = 2L
        override fun freeContext(handle: Long) {}
        override fun cancelGeneration(handle: Long) {}
        override fun applyTemplate(modelHandle: Long, requestJson: String): String = "{}"
        override fun generate(
            ctxHandle: Long, modelHandle: Long, appliedTemplateJson: String,
            maxTokens: Int, onPiece: (String) -> Boolean,
        ) {
            onPiece("ok")
        }
        override fun parseChat(text: String, isPartial: Boolean, appliedTemplateJson: String): String =
            """{"role":"assistant","content":"$text","reasoning_content":"","tool_calls":[]}"""
    }

    @Test
    fun `streams incremental chunks for a plain reply`() = runBlocking {
        val runtime = LlamaCppRuntime(ScriptedNative(listOf("Hel", "lo", " world")))
        runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

        val chunks = LlamaCppProvider.streamFromLoadedModel(
            runtime = runtime,
            messages = listOf(UIMessage.user("hi")),
            params = TextGenerationParams(model = Model(modelId = "test.gguf"), maxTokens = 64),
        ).toList()

        val combined = chunks.joinToString("") { chunk ->
            chunk.choices.firstOrNull()?.delta?.toText().orEmpty()
        }
        assertTrue("expected the full reply, got '$combined'", combined.contains("Hello world"))

        runtime.unload()
    }

    @Test
    fun `generateText returns the full reply, not just the last streamed chunk`() = runBlocking {
        val runtime = LlamaCppRuntime(ScriptedNative(listOf("Hel", "lo", " world")))
        runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

        val result = LlamaCppProvider.generateFromLoadedModel(
            runtime = runtime,
            messages = listOf(UIMessage.user("hi")),
            params = TextGenerationParams(model = Model(modelId = "test.gguf"), maxTokens = 64),
        )

        val text = result.choices.firstOrNull()?.message?.toText().orEmpty()
        assertTrue("expected the full reply, got '$text'", text.contains("Hello world"))

        runtime.unload()
    }

    @Test
    fun `streaming without a loaded model fails loudly`() = runBlocking {
        val runtime = LlamaCppRuntime(ScriptedNative(emptyList()))

        val error = runCatching {
            LlamaCppProvider.streamFromLoadedModel(
                runtime = runtime,
                messages = listOf(UIMessage.user("hi")),
                params = TextGenerationParams(model = Model(modelId = "test.gguf")),
            ).toList()
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
    }

    /** Like [ScriptedNative], but records the request JSON handed to [applyTemplate] so a
     *  test can check what the provider actually sent, without depending on native code. */
    private class CapturingNative(private val nCtxTrain: Int) : LlamaCppNative {
        var capturedRequestJson: String? = null
            private set

        override fun loadModel(path: String) = 1L
        override fun freeModel(handle: Long) {}
        override fun modelInfo(handle: Long): String = """
            {"n_layers":26,"n_embd":2048,"n_head_kv":4,"n_embd_head_k":256,
             "n_embd_head_v":256,"n_vocab":262144,"n_ctx_train":$nCtxTrain,
             "sliding_window":0,"weights_bytes":2600000000}
        """.trimIndent()
        override fun createContext(
            modelHandle: Long, nCtx: Int, nBatch: Int, nUBatch: Int,
            cacheTypeK: String, cacheTypeV: String, nThreads: Int,
        ) = 2L
        override fun freeContext(handle: Long) {}
        override fun cancelGeneration(handle: Long) {}
        override fun applyTemplate(modelHandle: Long, requestJson: String): String {
            capturedRequestJson = requestJson
            return "{}"
        }
        override fun generate(
            ctxHandle: Long, modelHandle: Long, appliedTemplateJson: String,
            maxTokens: Int, onPiece: (String) -> Boolean,
        ) {
            onPiece("ok")
        }
        override fun parseChat(text: String, isPartial: Boolean, appliedTemplateJson: String): String =
            """{"role":"assistant","content":"$text","reasoning_content":"","tool_calls":[]}"""
    }

    @Test
    fun `streamText trims history to the runtime's input budget before templating`() = runBlocking {
        // n_ctx_train pins the plan to the smallest ladder rung (4096), which caps
        // inputBudgetBytes() at (4096 / 2) * 4 = 8192 bytes - too small for the 200-turn
        // history below, so trimming must actually happen for the request to fit.
        val native = CapturingNative(nCtxTrain = 4096)
        val runtime = LlamaCppRuntime(native)
        runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

        val longHistory = (1..200).map {
            UIMessage.user("turn number $it padded with extra text so each message has real weight")
        }
        val untrimmedBytes = ChatRequestMapper.toRequestJson(longHistory, emptyList()).toByteArray().size

        LlamaCppProvider.streamFromLoadedModel(
            runtime = runtime,
            messages = longHistory,
            params = TextGenerationParams(model = Model(modelId = "test.gguf"), maxTokens = 64),
        ).toList()

        val sentJson = native.capturedRequestJson
        assertTrue("applyTemplate must have been called", sentJson != null)
        val sentMessages = JSONObject(sentJson!!).getJSONArray("messages")

        assertTrue(
            "expected the request actually sent (${sentJson.length} bytes) to be smaller " +
                "than the untrimmed request ($untrimmedBytes bytes)",
            sentJson.length < untrimmedBytes,
        )
        assertTrue(
            "expected fewer messages to reach applyTemplate than were passed in",
            sentMessages.length() < longHistory.size,
        )
        assertEquals(
            "the newest turn must survive trimming",
            "turn number 200 padded with extra text so each message has real weight",
            sentMessages.getJSONObject(sentMessages.length() - 1).getString("content"),
        )

        runtime.unload()
    }

    // resolveModelPath -----------------------------------------------------------------

    @Test
    fun `resolveModelPath throws a named error when the model is not installed`() {
        val error = runCatching {
            LlamaCppProvider.resolveModelPath(emptyMap(), "missing.gguf")
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertTrue(
            "expected the error to name the model, got '${error?.message}'",
            error?.message?.contains("missing.gguf") == true,
        )
        assertFalse(
            "must not be the generic runtime error a raw applyTemplate call would throw",
            error?.message?.contains("no model is loaded") == true,
        )
    }

    @Test
    fun `resolveModelPath throws when the registered file no longer exists on disk`() {
        val goneePath = "/tmp/definitely-not-here-${System.nanoTime()}.gguf"

        val error = runCatching {
            LlamaCppProvider.resolveModelPath(mapOf("gone.gguf" to goneePath), "gone.gguf")
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertTrue(error?.message?.contains("gone.gguf") == true)
    }

    @Test
    fun `resolveModelPath returns the installed path when the file exists`() {
        val tmp = File.createTempFile("model", ".gguf")
        tmp.deleteOnExit()

        val resolved = LlamaCppProvider.resolveModelPath(mapOf("model.gguf" to tmp.absolutePath), "model.gguf")

        assertEquals(tmp.absolutePath, resolved)
    }

    // ensureLoaded -----------------------------------------------------------------------

    @Test
    fun `ensureLoaded does not reload the same path twice`() = runBlocking {
        val native = CountingNative()
        val runtime = LlamaCppRuntime(native)
        val loadedPath = AtomicReference<String?>(null)

        LlamaCppProvider.ensureLoaded(runtime, loadedPath, "/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)
        LlamaCppProvider.ensureLoaded(runtime, loadedPath, "/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

        assertEquals(
            "reloading the already-loaded path must not touch native code again",
            1,
            native.loadModelCallCount,
        )

        runtime.unload()
    }

    @Test
    fun `ensureLoaded reloads when the resolved path changes`() = runBlocking {
        val native = CountingNative()
        val runtime = LlamaCppRuntime(native)
        val loadedPath = AtomicReference<String?>(null)

        LlamaCppProvider.ensureLoaded(runtime, loadedPath, "/tmp/a.gguf", emptyList(), 0, 12_000_000_000L)
        LlamaCppProvider.ensureLoaded(runtime, loadedPath, "/tmp/b.gguf", emptyList(), 0, 12_000_000_000L)

        assertEquals(2, native.loadModelCallCount)

        runtime.unload()
    }

    /** Like [CountingNative], but reports an oversized weights_bytes for one specific path
     *  so switching to it makes [LlamaCppRuntime.load] throw a real [ModelTooLargeException]
     *  partway through - after it has already unloaded whatever was loaded before. */
    private class OneModelTooLargeNative(private val tooLargePath: String) : LlamaCppNative {
        var loadModelCallCount = 0
            private set
        private var lastLoadedPath: String? = null

        override fun loadModel(path: String): Long {
            loadModelCallCount++
            lastLoadedPath = path
            return 1L
        }
        override fun freeModel(handle: Long) {}
        override fun modelInfo(handle: Long): String {
            val weightsBytes = if (lastLoadedPath == tooLargePath) 999_000_000_000L else 2_600_000_000L
            return """
                {"n_layers":26,"n_embd":2048,"n_head_kv":4,"n_embd_head_k":256,
                 "n_embd_head_v":256,"n_vocab":262144,"n_ctx_train":32768,
                 "sliding_window":0,"weights_bytes":$weightsBytes}
            """.trimIndent()
        }
        override fun createContext(
            modelHandle: Long, nCtx: Int, nBatch: Int, nUBatch: Int,
            cacheTypeK: String, cacheTypeV: String, nThreads: Int,
        ) = 2L
        override fun freeContext(handle: Long) {}
        override fun cancelGeneration(handle: Long) {}
        override fun applyTemplate(modelHandle: Long, requestJson: String): String = "{}"
        override fun generate(
            ctxHandle: Long, modelHandle: Long, appliedTemplateJson: String,
            maxTokens: Int, onPiece: (String) -> Boolean,
        ) {
            onPiece("ok")
        }
        override fun parseChat(text: String, isPartial: Boolean, appliedTemplateJson: String): String =
            """{"role":"assistant","content":"$text","reasoning_content":"","tool_calls":[]}"""
    }

    @Test
    fun `ensureLoaded retries a still-installed model after a failed switch, instead of skipping it`() = runBlocking {
        val native = OneModelTooLargeNative(tooLargePath = "/tmp/big.gguf")
        val runtime = LlamaCppRuntime(native)
        val loadedPath = AtomicReference<String?>(null)

        LlamaCppProvider.ensureLoaded(runtime, loadedPath, "/tmp/small.gguf", emptyList(), 0, 12_000_000_000L)
        assertEquals(1, native.loadModelCallCount)

        // load() unloads the small model as its first step, then fails on the oversized one.
        val error = runCatching {
            LlamaCppProvider.ensureLoaded(runtime, loadedPath, "/tmp/big.gguf", emptyList(), 0, 12_000_000_000L)
        }.exceptionOrNull()
        assertTrue("expected ModelTooLargeException, got $error", error is ModelTooLargeException)

        // The runtime now has nothing loaded, so switching back to the still-installed small
        // model must trigger a real reload rather than being skipped as "already loaded".
        LlamaCppProvider.ensureLoaded(runtime, loadedPath, "/tmp/small.gguf", emptyList(), 0, 12_000_000_000L)
        assertEquals(
            "the previously-working model must be reloaded after a failed switch, not skipped",
            3,
            native.loadModelCallCount,
        )

        runtime.unload()
    }
}
