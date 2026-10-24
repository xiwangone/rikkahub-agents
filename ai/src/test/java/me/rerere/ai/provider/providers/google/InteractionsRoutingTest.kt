package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionsRoutingTest {
    @Test
    fun `legacy google settings keep interactions disabled`() {
        val setting = json.decodeFromString<ProviderSetting.Google>("{}")

        assertFalse(setting.useInteractionsApi)
    }

    @Test
    fun `enabled developer api uses stateless interactions endpoint`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fixtureClient(INTERACTION_RESPONSE) { requests += it }
        val setting = ProviderSetting.Google(
            apiKey = "fixture-key-not-a-credential",
            baseUrl = "https://gemini.test/v1beta/",
            useInteractionsApi = true,
        )

        val result = GoogleProvider(client).generateText(
            providerSetting = setting,
            messages = listOf(UIMessage.user("fixture prompt")),
            params = TextGenerationParams(model = Model(modelId = "gemini-test")),
        )

        val request = requests.single()
        assertEquals("/v1beta/interactions", request.url.encodedPath)
        assertEquals("POST", request.method)
        assertEquals("fixture-key-not-a-credential", request.header("x-goog-api-key"))
        val body = requestBody(request).jsonObject
        assertEquals(false, body["store"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(body.containsKey("previous_interaction_id"))
        assertTrue(body.containsKey("input"))
        assertEquals("fixture answer", (result.message.parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `streaming enabled developer api uses interactions endpoint`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fixtureClient(INTERACTION_SSE, "text/event-stream") { requests += it }
        val setting = ProviderSetting.Google(
            apiKey = "fixture-key-not-a-credential",
            baseUrl = "https://gemini.test/v1beta",
            useInteractionsApi = true,
        )

        val chunks = GoogleProvider(client).streamText(
            providerSetting = setting,
            messages = listOf(UIMessage.user("fixture prompt")),
            params = TextGenerationParams(model = Model(modelId = "gemini-test")),
        ).toList()

        assertEquals("/v1beta/interactions", requests.single().url.encodedPath)
        assertTrue(requestBody(requests.single()).jsonObject["stream"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(chunks.any { it is StreamChunk.TextDelta && it.text == "fixture stream" })
        assertEquals("completed", (chunks.last() as StreamChunk.Finish).finishReason)
        val handler = StreamChunkHandler()
        val assembled = chunks.fold(listOf(UIMessage.user("fixture prompt"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        assertEquals("fixture stream", (assembled.last().parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `disabled interactions setting retains generateContent request`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fixtureClient(GENERATE_CONTENT_RESPONSE) { requests += it }
        val setting = ProviderSetting.Google(
            apiKey = "fixture-key-not-a-credential",
            baseUrl = "https://gemini.test/v1beta",
        )

        GoogleProvider(client).generateText(
            providerSetting = setting,
            messages = listOf(UIMessage.user("fixture prompt")),
            params = TextGenerationParams(model = Model(modelId = "gemini-test")),
        )

        val request = requests.single()
        assertEquals("/v1beta/models/gemini-test:generateContent", request.url.encodedPath)
        assertEquals("fixture-key-not-a-credential", request.header("x-goog-api-key"))
        val body = requestBody(request).jsonObject
        assertTrue(body.containsKey("contents"))
        assertFalse(body.containsKey("input"))
    }

    @Test
    fun `vertex ignores interactions setting and retains vertex generateContent`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fixtureClient(GENERATE_CONTENT_RESPONSE) { requests += it }
        val setting = ProviderSetting.Google(
            apiKey = "fixture-key-not-a-credential",
            vertexAI = true,
            useInteractionsApi = true,
        )

        GoogleProvider(client).generateText(
            providerSetting = setting,
            messages = listOf(UIMessage.user("fixture prompt")),
            params = TextGenerationParams(model = Model(modelId = "gemini-test")),
        )

        val request = requests.single()
        assertEquals("aiplatform.googleapis.com", request.url.host)
        assertEquals("/v1/publishers/google/models/gemini-test:generateContent", request.url.encodedPath)
        assertEquals("fixture-key-not-a-credential", request.url.queryParameter("key"))
        val body = requestBody(request).jsonObject
        assertTrue(body.containsKey("contents"))
        assertFalse(body.containsKey("input"))
    }

    private fun fixtureClient(
        responseText: String,
        contentType: String = "application/json",
        onRequest: (Request) -> Unit,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            onRequest(request)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("fixture")
                .body(responseText.toResponseBody(contentType.toMediaType()))
                .build()
        }
        .build()

    private fun requestBody(request: Request) = Buffer().let { buffer ->
        request.body!!.writeTo(buffer)
        json.parseToJsonElement(buffer.readUtf8())
    }

    private companion object {
        val INTERACTION_RESPONSE = """
            {
              "id": "fixture-interaction",
              "status": "completed",
              "model": "gemini-test",
              "steps": [
                {"type": "model_output", "content": [{"type": "text", "text": "fixture answer"}]}
              ]
            }
        """.trimIndent()

        val GENERATE_CONTENT_RESPONSE = """
            {
              "candidates": [
                {
                  "content": {"role": "model", "parts": [{"text": "fixture answer"}]},
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()

        val INTERACTION_SSE = """
            data: {"event_type":"interaction.created","interaction":{"id":"fixture-stream","status":"in_progress","model":"gemini-test"}}

            data: {"event_type":"step.start","index":0,"step":{"type":"model_output"}}

            data: {"event_type":"step.delta","index":0,"delta":{"type":"text","text":"fixture stream"}}

            data: {"event_type":"step.stop","index":0}

            data: {"event_type":"interaction.completed","interaction":{"id":"fixture-stream","status":"completed","model":"gemini-test"}}
        """.trimIndent() + "\n\n"
    }
}
