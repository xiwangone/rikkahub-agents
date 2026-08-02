package me.rerere.llamacpp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
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
            val result = JSONObject(LlamaCppJni.nativeApplyTemplate(handle, request))
            val prompt = result.getString("prompt")
            assertTrue("prompt must contain the user text", prompt.contains("Say hello"))
            assertTrue("prompt must be templated, not raw", prompt.length > "Say hello".length)
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
            val result = JSONObject(LlamaCppJni.nativeApplyTemplate(handle, request))
            assertTrue(
                "a tool-capable template must emit a grammar so tool syntax is constrained",
                result.getString("grammar").isNotBlank(),
            )
            assertTrue("the tool name should reach the prompt", result.getString("prompt").contains("get_time"))
        } finally {
            LlamaCppJni.nativeFreeModel(handle)
        }
    }
}
