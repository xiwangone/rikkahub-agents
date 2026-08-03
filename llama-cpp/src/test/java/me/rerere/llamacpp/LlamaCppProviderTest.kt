package me.rerere.llamacpp

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaCppProviderTest {

    /**
     * Reflection, not `ProviderSetting.LlamaCppLocal(...)` or `.copy(...)`: its
     * `description`/`shortDescription` are `@Composable` lambdas, which the `ai` module's
     * Compose plugin compiles to a different JVM parameter shape than this (non-Compose)
     * module emits at a Kotlin call site. Kotlin always routes a call that defaults ANY
     * parameter - even all of them - through the constructor overload that carries every
     * parameter (the bitmask-and-marker one), so this mismatch fires regardless of how many
     * arguments the call site actually supplies; there is no plain-Kotlin-call way around
     * it. The class's public no-arg constructor never mentions those two parameters, so
     * invoking it directly by reflection - bypassing Kotlin's own overload resolution - has
     * no such mismatch. `providerSetting.enabled` is not read by
     * [LlamaCppProvider.streamText] anyway.
     */
    private fun testProviderSetting(): ProviderSetting.LlamaCppLocal =
        ProviderSetting.LlamaCppLocal::class.java.getDeclaredConstructor().newInstance()

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

    @Test
    fun `streams incremental chunks for a plain reply`() = runBlocking {
        val runtime = LlamaCppRuntime(ScriptedNative(listOf("Hel", "lo", " world")))
        runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)
        val provider = LlamaCppProvider(runtime)

        val chunks = provider.streamText(
            providerSetting = testProviderSetting(),
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
        val provider = LlamaCppProvider(runtime)

        val result = provider.generateText(
            providerSetting = testProviderSetting(),
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
        val provider = LlamaCppProvider(runtime)

        val error = runCatching {
            provider.streamText(
                providerSetting = testProviderSetting(),
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
        val provider = LlamaCppProvider(runtime)

        val longHistory = (1..200).map {
            UIMessage.user("turn number $it padded with extra text so each message has real weight")
        }
        val untrimmedBytes = ChatRequestMapper.toRequestJson(longHistory, emptyList()).toByteArray().size

        provider.streamText(
            providerSetting = testProviderSetting(),
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
}
