package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.data.ai.FailureKind
import me.rerere.rikkahub.data.ai.classifyFailureKind
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * 聊天错误管理。
 *
 * 从 ChatService 提取：addError / dismissError / clearAllErrors。
 */
internal class ChatErrorHelper {
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        val kind = classifyFailureKind(error, error.message.orEmpty()).takeIf { it != FailureKind.UNKNOWN }
        _errors.update {
            it +
                ChatError(
                    title = title,
                    error = error,
                    conversationId = conversationId,
                    solution = solution,
                    kind = kind,
                )
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }
}
