package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.data.ai.FailureKind
import me.rerere.rikkahub.data.ai.ProviderUnavailableException
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
        // 本地供应商解析失败归为「模型不存在」——在调用方判定，避免抬高 classifyFailureKind 的圈复杂度。
        val kind =
            if (error is ProviderUnavailableException) {
                FailureKind.MODEL_NOT_FOUND
            } else {
                classifyFailureKind(error, error.message.orEmpty()).takeIf { it != FailureKind.UNKNOWN }
            }
        // 模型已不可用（供应商被删/改名）→ 自动带上「重新选择模型」入口，
        // 不依赖每个上报点都记得传 solution（发送 / 重生成 / 定时任务 都可能命中）。
        val effectiveSolution =
            solution
                ?: when (error) {
                    is ProviderUnavailableException -> ChatErrorSolution.SelectModel
                    else -> null
                }
        _errors.update {
            it +
                ChatError(
                    title = title,
                    error = error,
                    conversationId = conversationId,
                    solution = effectiveSolution,
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
