package me.rerere.llamacpp

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LlamaCppTemplateTest {

    private val fixture = File("/data/local/tmp/llamacpp-test.gguf")

    @Test
    fun rendersAPromptFromTheModelsOwnTemplate() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        val handle = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            val request = """
                {"messages":[{"role":"user","content":"Say hello"}]}
            """.trimIndent()
            val result = JSONObject(LlamaCppJni.applyTemplate(handle, request))
            val prompt = result.getString("prompt")
            assertTrue("prompt must contain the user text", prompt.contains("Say hello"))
            assertTrue("prompt must be templated, not raw", prompt.length > "Say hello".length)
            // A plain chat request with no tools must not be constrained to tool-call syntax,
            // or the model could never answer in prose.
            assertTrue("a tool-less request must not carry a grammar", result.getString("grammar").isEmpty())
        } finally {
            LlamaCppJni.nativeFreeModel(handle)
        }
    }

    @Test
    fun declaringToolsProducesAConstrainingGrammar() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        val handle = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            val request = """
                {"messages":[{"role":"user","content":"What time is it?"}],
                 "tools":[{"type":"function","function":{"name":"get_time",
                   "description":"Get the current time",
                   "parameters":{"type":"object","properties":{},"required":[]}}}]}
            """.trimIndent()
            val result = JSONObject(LlamaCppJni.applyTemplate(handle, request))
            assertTrue(
                "a tool-capable template must emit a grammar so tool syntax is constrained",
                result.getString("grammar").isNotBlank(),
            )
            assertTrue("the tool name should reach the prompt", result.getString("prompt").contains("get_time"))
            // A lazy grammar only starts constraining once a trigger matches. An empty trigger
            // list would mean it never activates and tool calling silently degrades to
            // unconstrained free text, while this assertion alone would still pass.
            assertTrue("a tool grammar for this fixture is lazy", result.getBoolean("grammar_lazy"))
            val triggers = result.getJSONArray("grammar_triggers")
            assertTrue("a lazy grammar needs at least one trigger to ever activate", triggers.length() > 0)
            assertEquals("word", triggers.getJSONObject(0).getString("type"))
        } finally {
            LlamaCppJni.nativeFreeModel(handle)
        }
    }

    @Test
    fun aToolResultFromChatRequestMapperReachesThePrompt() {
        // Joins Task 7's mapper to this task's renderer. Neither side's own tests exercise the
        // shape ChatRequestMapper emits for a turn after a tool call: an assistant message
        // carrying tool_calls, followed by a role:"tool" message. This is the most important
        // path this runtime has, since it is exactly where a shape mismatch would surface as a
        // hard exception on the first turn after any tool call.
        assumeTrue("fixture GGUF not present", fixture.exists())
        val handle = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            val history = listOf(
                UIMessage.user("What's the weather in Paris?"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "1",
                            toolName = "get_weather",
                            input = "{}",
                            output = listOf(UIMessagePart.Text("sunny and 22C")),
                        ),
                    ),
                ),
                UIMessage.user("Thanks, what should I wear?"),
            )
            val request = ChatRequestMapper.toRequestJson(history, emptyList())
            val result = JSONObject(LlamaCppJni.applyTemplate(handle, request))
            assertTrue(
                "the tool result text must reach the rendered prompt",
                result.getString("prompt").contains("sunny and 22C"),
            )
        } finally {
            LlamaCppJni.nativeFreeModel(handle)
        }
    }

    @Test(expected = RuntimeException::class)
    fun applyTemplateOnAZeroHandleThrows() {
        LlamaCppJni.applyTemplate(0L, "{}")
    }

    @Test(expected = RuntimeException::class)
    fun malformedRequestJsonThrows() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        val handle = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            LlamaCppJni.applyTemplate(handle, "not json")
        } finally {
            LlamaCppJni.nativeFreeModel(handle)
        }
    }

    @Test(expected = RuntimeException::class)
    fun requestMissingMessagesThrows() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        val handle = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            LlamaCppJni.applyTemplate(handle, "{}")
        } finally {
            LlamaCppJni.nativeFreeModel(handle)
        }
    }
}
