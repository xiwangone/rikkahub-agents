
package me.rerere.rikkahub.service

import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatService"


    // ---- 翻译消息 ----

fun ChatService.translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale,
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText =
                    message.parts
                        .filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n\n") { it.text }
                        .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = str(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationLoop
                    .translateText(
                        settings = settings,
                        sourceText = messageText,
                        targetLanguage = targetLanguage,
                    ) { translatedText ->
                        // Update translation field in real-time
                        updateTranslationField(conversationId, message.id, translatedText)
                    }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CancellationException) {
                // 取消不是失败：否则用户中断会弹「翻译失败」。
                throw e
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = str(R.string.error_title_translate_message))
            }
        }
    }

internal fun ChatService.updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes =
            currentConversation.messageNodes.map { node ->
                if (node.messages.any { it.id == messageId }) {
                    val updatedMessages =
                        node.messages.map { msg ->
                            if (msg.id == messageId) {
                                msg.copy(translation = translationText)
                            } else {
                                msg
                            }
                        }
                    node.copy(messages = updatedMessages)
                } else {
                    node
                }
            }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

suspend fun ChatService.editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>,
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant =
            settings.getAssistantById(currentConversation.assistantId)
                ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes =
            currentConversation.messageNodes.map { node ->
                if (!node.messages.any { it.id == messageId }) {
                    return@map node
                }
                edited = true

                if (node.messages.first { it.id == messageId }.isContextCheckpoint) {
                    // 摘要原地改写：新建分支会丢掉检查点标记
                    return@map node.copy(
                        messages = node.messages.map { message ->
                            if (message.id == messageId) message.copy(parts = processedParts) else message
                        }
                    )
                }

                node.copy(
                    messages =
                        node.messages +
                            UIMessage(
                                role = node.role,
                                parts = processedParts,
                            ),
                    selectIndex = node.messages.size,
                )
            }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

suspend fun ChatService.forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid,
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex =
            currentConversation.messageNodes.indexOfFirst { node ->
                node.messages.any { it.id == messageId }
            }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes =
            currentConversation.messageNodes
                .subList(0, targetNodeIndex + 1)
                .map { node ->
                    node.copy(
                        id = Uuid.random(),
                        messages =
                            node.messages.map { message ->
                                message.copy(
                                    parts =
                                        message.parts.map { part ->
                                            copyWithForkedFileUrl(part)
                                        },
                                )
                            },
                    )
                }

        val existingTitles = conversationRepo
            .getConversationsOfAssistant(currentConversation.assistantId)
            .first()
            .mapTo(mutableSetOf()) { it.title }
        val forkConversation = createForkConversation(currentConversation, copiedNodes, existingTitles)

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

suspend fun ChatService.selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode =
            currentConversation.messageNodes.firstOrNull { it.id == nodeId }
                ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes =
            currentConversation.messageNodes.map { node ->
                if (node.id == nodeId) {
                    node.copy(selectIndex = selectIndex)
                } else {
                    node
                }
            }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

suspend fun ChatService.deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

suspend fun ChatService.deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

internal fun ChatService.buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex =
            conversation.messageNodes.indexOfFirst { node ->
                node.messages.any { it.id == messageId }
            }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes =
            conversation.messageNodes.mapIndexedNotNull { index, node ->
                if (index != targetNodeIndex) {
                    return@mapIndexedNotNull node
                }

                val nextMessages = node.messages.filterNot { it.id == messageId }
                if (nextMessages.isEmpty()) {
                    return@mapIndexedNotNull null
                }

                val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
                node.copy(
                    messages = nextMessages,
                    selectIndex = nextSelectIndex,
                )
            }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun ChatService.copyWithForkedFileUrl(part: UIMessagePart): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (part) {
            is UIMessagePart.Image -> part.copy(url = copyLocalFileIfNeeded(part.url))
            is UIMessagePart.Document -> part.copy(url = copyLocalFileIfNeeded(part.url))
            is UIMessagePart.Video -> part.copy(url = copyLocalFileIfNeeded(part.url))
            is UIMessagePart.Audio -> part.copy(url = copyLocalFileIfNeeded(part.url))
            else -> part
        }
    }

fun ChatService.clearTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes =
            currentConversation.messageNodes.map { node ->
                if (node.messages.any { it.id == messageId }) {
                    val updatedMessages =
                        node.messages.map { msg ->
                            if (msg.id == messageId) {
                                msg.copy(translation = null)
                            } else {
                                msg
                            }
                        }
                    node.copy(messages = updatedMessages)
                } else {
                    node
                }
            }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

