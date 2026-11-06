package me.rerere.rikkahub.ui.pages.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.uuid.Uuid

class DebugVM(
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    val settings = settingsStore.settingsFlow

    private val _conversationCount = MutableStateFlow<Int?>(null)
    val conversationCount: StateFlow<Int?> = _conversationCount.asStateFlow()
    private val _assistantConversationCounts = MutableStateFlow<Map<Uuid, Int>>(emptyMap())
    val assistantConversationCounts: StateFlow<Map<Uuid, Int>> = _assistantConversationCounts.asStateFlow()

    init {
        refreshConversationCount()
        refreshAssistantConversationCounts()
    }

    fun refreshConversationCount() {
        viewModelScope.launch {
            _conversationCount.value = conversationRepository.countConversations()
        }
    }

    fun refreshAssistantConversationCounts() {
        viewModelScope.launch {
            _assistantConversationCounts.value = conversationRepository.countConversationsByAssistant()
        }
    }

    /** Adds only placeholder identities for IDs referenced by conversations but absent from Settings. */
    suspend fun recoverMissingAssistants(nameProvider: (Int) -> String = { "Assistant $it" }): Int {
        val counts = conversationRepository.countConversationsByAssistant()
        _assistantConversationCounts.value = counts
        var recovered = 0
        settingsStore.update { current ->
            val existingIds = current.assistants.mapTo(mutableSetOf()) { it.id }
            val missing = counts.keys.filter { it !in existingIds }.sortedBy { it.toString() }
            recovered = missing.size
            // Intentionally do not infer the original model, prompt, or tools from conversation IDs.
            current.copy(assistants = current.assistants + missing.mapIndexed { index, id ->
                Assistant(id = id, name = nameProvider(index + 1))
            })
        }
        return recovered
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    /**
     * 创建一个超大的对话用于测试 CursorWindow 限制
     * @param sizeMB 目标大小（MB）
     */
    fun createOversizedConversation(sizeMB: Int = 3) {
        viewModelScope.launch {
            val targetSize = sizeMB * 1024 * 1024
            val messageNodes = mutableListOf<MessageNode>()
            var currentSize = 0

            // 生成大量消息直到达到目标大小
            var index = 0
            while (currentSize < targetSize) {
                // 生成一个包含大量文本的消息（约 100KB 每条）
                val largeText =
                    buildString {
                        repeat(100) {
                            append("Long synthetic payload for testing CursorWindow size limits. ")
                            append("Row too big to fit into CursorWindow typically occurs above 2MB per row. ")
                            append("Lorem ipsum dolor sit amet, consectetur adipiscing elit. ")
                            append("Index: $index, Block: $it. ")
                        }
                    }

                val userMessage =
                    UIMessage(
                        id = Uuid.random(),
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text(largeText)),
                        createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                    )
                val assistantMessage =
                    UIMessage(
                        id = Uuid.random(),
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("Reply: $largeText")),
                        createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                    )

                messageNodes.add(MessageNode.of(userMessage))
                messageNodes.add(MessageNode.of(assistantMessage))

                currentSize += largeText.length * 2 * 2 // 大约估算
                index++
            }

            val conversation =
                Conversation(
                    id = Uuid.random(),
                    assistantId = DEFAULT_ASSISTANT_ID,
                    title = "${sizeMB}MB Oversized Conversation Test",
                    messageNodes = messageNodes,
                )

            conversationRepository.insertConversation(conversation)
        }
    }

    fun createConversationWithMessages(messageCount: Int = 1024) {
        viewModelScope.launch {
            val messageNodes = ArrayList<MessageNode>(messageCount)
            val timeZone = TimeZone.currentSystemDefault()
            repeat(messageCount) { index ->
                val role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT
                val message =
                    UIMessage(
                        id = Uuid.random(),
                        role = role,
                        parts = listOf(UIMessagePart.Text(randomMessageText(index, role))),
                        createdAt = Clock.System.now().toLocalDateTime(timeZone),
                    )
                messageNodes.add(MessageNode.of(message))
            }

            val conversation =
                Conversation(
                    id = Uuid.random(),
                    assistantId = DEFAULT_ASSISTANT_ID,
                    title = "$messageCount Messages Test",
                    messageNodes = messageNodes,
                )

            conversationRepository.insertConversation(conversation)
        }
    }

    private fun randomMessageText(
        index: Int,
        role: MessageRole,
    ): String {
        val fragments =
            listOf(
                "fast",
                "random",
                "message",
                "sample",
                "used",
                "test",
                "list",
                "render",
                "scroll",
                "perf",
                "chat",
                "dialog",
                "content",
                "struct",
                "verify",
                "page",
                "order",
                "stable",
                "system",
            )
        val wordCount = Random.nextInt(6, 14)
        val prefix = if (role == MessageRole.USER) "User" else "Assistant"
        val body = List(wordCount) { fragments.random() }.joinToString(" ")
        return "$prefix#${index + 1}: $body"
    }
}
