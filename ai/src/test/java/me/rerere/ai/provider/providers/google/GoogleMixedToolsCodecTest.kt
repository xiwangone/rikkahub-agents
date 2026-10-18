package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production codec against captured Google GenerateContent wire payloads. */
class GoogleMixedToolsCodecTest {
    private val provider = GoogleProvider(OkHttpClient())

    private fun fixture(name: String): JsonObject =
        json.parseToJsonElement(
            checkNotNull(javaClass.getResourceAsStream("/fixtures/google-mixed-tools/$name")) {
                "Missing Google GenerateContent fixture: $name"
            }.bufferedReader().use { it.readText() }
        ).jsonObject

    private fun parseCandidateMessage(candidate: JsonObject): UIMessage {
        val content = candidate["content"]!!.jsonObject
        return provider.parseGoogleMessage(buildJsonObject {
            put("role", content["role"]!!)
            put("content", content)
        })
    }

    @Test
    fun `mixed server and client tool exchange preserves roles ids raw parts and signature`() {
        val response = fixture("response.json")
        val responseCandidate = response["candidates"]!!.jsonArray.single().jsonObject
        val assistant = parseCandidateMessage(responseCandidate)

        val serverCall = assistant.parts.filterIsInstance<UIMessagePart.ServerTool>().single()
        assertEquals("server-call-17", serverCall.toolCallId)
        assertEquals(ServerToolStatus.IN_PROGRESS, serverCall.status)
        val serverCallMetadata = serverCall.metadataAs<ServerToolMetadata>()!!
        assertEquals(ServerToolProtocol.GOOGLE_GENERATE_CONTENT, serverCallMetadata.protocol)
        assertEquals(1, serverCallMetadata.callIndex)
        assertEquals(responseCandidate["content"]!!.jsonObject["parts"]!!.jsonArray[1], serverCallMetadata.call)

        val clientCall = assistant.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("client-call-18", clientCall.toolCallId)
        assertEquals("sig-function-02", clientCall.metadataAs<GoogleThoughtMetadata>()!!.thoughtSignature)
        assertFalse("Google server calls must not enter local tool dispatch", assistant.parts.any {
            it is UIMessagePart.Tool && it.toolCallId == serverCall.toolCallId
        })

        val toolResponse = fixture("tool-response.json")
        val resultCandidate = toolResponse["candidates"]!!.jsonArray.single().jsonObject
        val serverResultMessage = parseCandidateMessage(resultCandidate)
        val serverResult = serverResultMessage.parts.filterIsInstance<UIMessagePart.ServerTool>().single()
        assertEquals("server-call-17", serverResult.toolCallId)
        assertEquals(ServerToolStatus.COMPLETED, serverResult.status)
        assertTrue(serverResult.partsAreRawGoogleServerResult())
        assertTrue("Completed server tool results are not local executable tools", serverResultMessage.parts.none {
            it is UIMessagePart.Tool
        })

        val mixedHistory = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = assistant.parts.map { part ->
                if (part is UIMessagePart.Tool) part.copy(output = listOf(UIMessagePart.Text("13C"))) else part
            } + serverResult,
        )
        val params = TextGenerationParams(
            model = Model(
                modelId = "gemini-2.5-pro",
                abilities = listOf(ModelAbility.TOOL),
                tools = setOf(BuiltInTools.Search),
            ),
            tools = listOf(Tool(
                name = "lookup_weather",
                description = "Look up the weather",
                execute = { emptyList() },
            )),
        )
        val request = provider.buildCompletionRequestBody(listOf(mixedHistory), params)
        val requestTools = request["tools"]!!.jsonArray
        assertTrue(requestTools.any { it.jsonObject.containsKey("functionDeclarations") })
        assertTrue(requestTools.any { it.jsonObject.containsKey("googleSearch") })
        assertEquals(true, request["toolConfig"]!!.jsonObject["includeServerSideToolInvocations"]!!.jsonPrimitive.content.toBoolean())

        val contents = request["contents"]!!.jsonArray
        assertEquals(listOf("model", "user"), contents.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val modelParts = contents[0].jsonObject["parts"]!!.jsonArray
        val userParts = contents[1].jsonObject["parts"]!!.jsonArray
        assertEquals(2, modelParts.size)
        assertEquals(2, userParts.size)
        assertEquals(serverCallMetadata.call, modelParts[0])
        assertEquals("client-call-18", modelParts[1].jsonObject["functionCall"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("sig-function-02", modelParts[1].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
        assertEquals("client-call-18", userParts[0].jsonObject["functionResponse"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("server-call-17", userParts[1].jsonObject["toolResponse"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(serverResult.metadataAs<ServerToolMetadata>()!!.result, userParts[1])
    }

    @Test
    fun `stream decoder emits separate server and local tool events with original ids`() {
        val payload = fixture("response.json")
        val decoder = GoogleStreamDecoder("fixture-response", "gemini-2.5-pro")
        val decoded = decoder.accept(SseEvent(data = payload.toString()))
        val serverStart = decoded.chunks.filterIsInstance<StreamChunk.ServerToolStart>().single()
        val clientStart = decoded.chunks.filterIsInstance<StreamChunk.ToolCallStart>().single()
        assertEquals("server-call-17", serverStart.id)
        assertEquals("client-call-18", clientStart.id)
        assertEquals("sig-function-02", clientStart.metadata?.get("thoughtSignature")?.jsonPrimitive?.contentOrNull)
        assertEquals(ServerToolProtocol.GOOGLE_GENERATE_CONTENT.name.lowercase(), serverStart.metadata?.get("server_tool_protocol")?.jsonPrimitive?.contentOrNull)
    }

    private fun UIMessagePart.ServerTool.partsAreRawGoogleServerResult(): Boolean {
        val metadata = metadataAs<ServerToolMetadata>() ?: return false
        return metadata.protocol == ServerToolProtocol.GOOGLE_GENERATE_CONTENT &&
            metadata.result?.containsKey("toolResponse") == true &&
            metadata.resultIndex == 0
    }
}
