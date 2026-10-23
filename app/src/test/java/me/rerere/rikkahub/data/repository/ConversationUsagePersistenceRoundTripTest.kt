package me.rerere.rikkahub.data.repository

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Synthetic-data JVM integration coverage for the same JSON and model conversion calls used by
 * conversation export and the repository's split message_node persistence path. No database,
 * backup file, Android framework, user content, or credentials are accessed.
 */
class ConversationUsagePersistenceRoundTripTest {
    @Test
    fun `usage counters survive entity JSON persistence conversation conversion and export`() {
        val sourceMessage = UIMessage.assistant("synthetic assistant reply").copy(
            usage = TokenUsage(promptTokens = 60, completionTokens = 16, totalTokens = 76),
            lastUsage = TokenUsage(promptTokens = 31, completionTokens = 9, totalTokens = 40),
            stepCount = 3,
        )
        val sourceNode = MessageNode(messages = listOf(sourceMessage))
        val sourceConversation = conversationWith(sourceNode)

        // This is the exact encoding expression used by ConversationRepository.saveMessageNodes.
        val entity = MessageNodeEntity(
            id = sourceNode.id.toString(),
            conversationId = sourceConversation.id.toString(),
            nodeIndex = 0,
            messages = JsonInstant.encodeToString(sourceNode.messages),
            selectIndex = sourceNode.selectIndex,
        )
        // This is the exact JSON decode used by ConversationRepository.loadMessageNodes; rebuild
        // its MessageNode and pass through the actual ConversationEntity conversion functions.
        val decodedNode = MessageNode(
            id = Uuid.parse(entity.id),
            messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages),
            selectIndex = entity.selectIndex,
        )
        val conversationEntity = conversationToConversationEntityImpl(sourceConversation)
        val restored = conversationEntityToConversationImpl(conversationEntity, listOf(decodedNode))
        val persistedMessage = restored.messageNodes.single().messages.single()

        assertUsage(persistedMessage)

        // exportToJson uses JsonInstant.encodeToString(Conversation.serializer(), conversation).
        val exportedJson = JsonInstant.encodeToString(Conversation.serializer(), restored)
        val imported = JsonInstant.decodeFromString<Conversation>(exportedJson)
        assertUsage(imported.messageNodes.single().messages.single())
    }

    @Test
    fun `legacy entity JSON without latest usage fields defaults and falls back to cumulative usage`() {
        val conversation = conversationWith(MessageNode(messages = emptyList()))
        val legacyEntity = MessageNodeEntity(
            id = Uuid.random().toString(),
            conversationId = conversation.id.toString(),
            nodeIndex = 0,
            // Historical UIMessage payload: deliberately no lastUsage or stepCount keys.
            messages = """[{"role":"assistant","parts":[],"usage":{"promptTokens":60,"completionTokens":16,"totalTokens":76}}]""",
            selectIndex = 0,
        )
        val decodedNode = MessageNode(
            id = Uuid.parse(legacyEntity.id),
            messages = JsonInstant.decodeFromString<List<UIMessage>>(legacyEntity.messages),
            selectIndex = legacyEntity.selectIndex,
        )
        val restored = conversationEntityToConversationImpl(
            conversationToConversationEntityImpl(conversation),
            listOf(decodedNode),
        )
        val legacyMessage = restored.messageNodes.single().messages.single()

        assertEquals(0, legacyMessage.stepCount)
        assertNull(legacyMessage.lastUsage)
        assertEquals(TokenUsage(promptTokens = 60, completionTokens = 16, totalTokens = 76), legacyMessage.usage)
        assertEquals(legacyMessage.usage, legacyMessage.lastRequestUsage())
        assertEquals(76, legacyMessage.contextTokens())

        // The same legacy row shape also remains valid in the Conversation export/import serializer.
        val exported = JsonInstant.encodeToString(Conversation.serializer(), restored)
        val imported = JsonInstant.decodeFromString<Conversation>(exported)
        val importedLegacyMessage = imported.messageNodes.single().messages.single()
        assertEquals(0, importedLegacyMessage.stepCount)
        assertNull(importedLegacyMessage.lastUsage)
        assertEquals(importedLegacyMessage.usage, importedLegacyMessage.lastRequestUsage())
        assertNotNull(importedLegacyMessage.usage)
    }

    private fun assertUsage(message: UIMessage) {
        assertEquals(TokenUsage(promptTokens = 60, completionTokens = 16, totalTokens = 76), message.usage)
        assertEquals(TokenUsage(promptTokens = 31, completionTokens = 9, totalTokens = 40), message.lastUsage)
        assertEquals(3, message.stepCount)
        assertEquals(40, message.contextTokens())
    }

    private fun conversationWith(node: MessageNode): Conversation = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = "synthetic persistence fixture",
        messageNodes = listOf(node),
    )
}
