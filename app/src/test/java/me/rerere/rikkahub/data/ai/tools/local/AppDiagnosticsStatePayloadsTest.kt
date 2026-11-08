package me.rerere.rikkahub.data.ai.tools.local

import kotlin.uuid.Uuid
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Diagnostics must report the model the conversation actually runs on, not the assistant default.
 * Resolution order is the same one the generation path uses:
 * conversation override > assistant default > global default.
 */
class AppDiagnosticsStatePayloadsTest {
    private val conversationModel = Model(modelId = "conv-model", displayName = "Conversation Model")
    private val assistantModel = Model(modelId = "assistant-model", displayName = "Assistant Model")
    private val globalModel = Model(modelId = "global-model", displayName = "Global Model")

    private fun settingsWith(assistant: Assistant): Settings = Settings(
        providers = listOf(
            ProviderSetting.OpenAI(
                id = Uuid.random(),
                name = "Test Provider",
                models = listOf(conversationModel, assistantModel, globalModel),
            ),
        ),
        assistants = listOf(assistant),
        assistantId = assistant.id,
        chatModelId = globalModel.id,
    )

    @Test
    fun `conversation override wins when the assistant allows per conversation models`() {
        val assistant = Assistant(chatModelId = assistantModel.id, allowPerConversationModel = true)

        val scope = resolveModelScope(settingsWith(assistant), conversationModel.id, assistant.id)

        assertEquals(conversationModel.id, scope.modelId)
        assertEquals("conversation", scope.source)
    }

    @Test
    fun `conversation override is ignored when the assistant disallows it`() {
        val assistant = Assistant(chatModelId = assistantModel.id, allowPerConversationModel = false)

        val scope = resolveModelScope(settingsWith(assistant), conversationModel.id, assistant.id)

        assertEquals(assistantModel.id, scope.modelId)
        assertEquals("assistant", scope.source)
    }

    @Test
    fun `global default is used when neither level pins a model`() {
        val assistant = Assistant(chatModelId = null, allowPerConversationModel = true)

        val scope = resolveModelScope(settingsWith(assistant), null, assistant.id)

        assertEquals(globalModel.id, scope.modelId)
        assertEquals("global", scope.source)
    }

    @Test
    fun `scope keeps the assistant that owns the conversation`() {
        val assistant = Assistant(name = "Bound assistant", chatModelId = assistantModel.id)

        val scope = resolveModelScope(settingsWith(assistant), null, assistant.id)

        assertEquals(assistant.id, scope.assistant.id)
        assertEquals("Bound assistant", scope.assistant.name)
    }

    @Test
    fun `label resolves provider and display name`() {
        assertEquals("Test Provider / Conversation Model", modelLabel(settingsWith(Assistant()), conversationModel.id))
    }

    @Test
    fun `label falls back to the model id when the display name is blank`() {
        val nameless = Model(modelId = "raw-model-id", displayName = "")

        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(id = Uuid.random(), name = "P", models = listOf(nameless))),
        )

        assertEquals("P / raw-model-id", modelLabel(settings, nameless.id))
    }

    @Test
    fun `label returns null for an unknown uuid instead of inventing a name`() {
        assertNull(modelLabel(settingsWith(Assistant()), Uuid.random()))
    }

    @Test
    fun `label returns null for a null uuid`() {
        assertNull(modelLabel(settingsWith(Assistant()), null))
    }

    @Test
    fun `dangling conversation model uuid stays visible as unresolved`() {
        val assistant = Assistant(chatModelId = assistantModel.id, allowPerConversationModel = true)
        val dangling = Uuid.random()

        val scope = resolveModelScope(settingsWith(assistant), dangling, assistant.id)

        assertEquals(dangling, scope.modelId)
        assertEquals("unresolved", scope.source)
    }

    @Test
    fun `resolveTestModelTarget prefers CHAT model when modelId is omitted`() {
        val embModel = Model(id = Uuid.random(), modelId = "text-embedding-3", displayName = "Emb", type = ModelType.EMBEDDING)
        val chatModel = Model(id = Uuid.random(), modelId = "gpt-4o", displayName = "GPT-4o", type = ModelType.CHAT)
        val mixedProvider = ProviderSetting.OpenAI(name = "MixedOpenAI", models = listOf(embModel, chatModel))
        val settings = Settings(providers = listOf(mixedProvider))

        val (resolvedProvider, resolvedModel) = resolveTestModelTarget(settings, "MixedOpenAI", null)

        assertEquals("MixedOpenAI", resolvedProvider.name)
        assertEquals("gpt-4o", resolvedModel.modelId)
    }

    @Test
    fun `resolveTestModelTarget resolves caller assistant model when provider is omitted`() {
        val globalAssistant = Assistant(name = "DefaultBot", chatModelId = globalModel.id)
        val callerAssistant = Assistant(name = "CallerBot", chatModelId = assistantModel.id)
        val testProvider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "Test Provider",
            models = listOf(conversationModel, assistantModel, globalModel),
        )
        val settings = Settings(
            assistantId = globalAssistant.id,
            chatModelId = globalModel.id,
            assistants = listOf(globalAssistant, callerAssistant),
            providers = listOf(testProvider),
        )
        val ctx = ToolInvocationContext(callerAssistantId = callerAssistant.id.toString())

        val (resolvedProvider, resolvedModel) = resolveTestModelTarget(settings, null, null, ctx)

        assertEquals(testProvider.id, resolvedProvider.id)
        assertEquals(assistantModel.id, resolvedModel.id)
    }
}
