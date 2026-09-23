package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.vault.AuditContext
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.VaultProviderKeyRefs
import org.koin.java.KoinJavaComponent.getKoin

/**
 * provider 取用凭据引用（`$$名字`）的审计落盘 —— 审计「归属维度」的第二注入点。
 *
 * 为什么单独一个文件/对象：provider 取 key 走的是**同步热路径**（`ProviderKeyRefs.expand`），
 * 那里只能把命中的凭证名入队（[VaultProviderKeyRefs] 装配的钩子）；真正的审计写在这里 ——
 * 只有生成链路同时具备「归属（会话/模型/助手）」和 suspend 写库能力。
 *
 * 归属语义与工具侧完全一致（[AuditContext]），只有 `source` 不同（`provider`），
 * 便于在审计列表里区分「AI 主动取用」与「发请求时按配置取用」。
 */
internal object ProviderKeyAudit {

    /**
     * 把本次请求期间命中的引用落进审计（去重、单请求有上限，见 `VaultProviderKeyRefs`）。
     *
     * @param conversationId 会话 id（翻译等无会话的用途为 null）
     * @return 实际写入条数
     */
    suspend fun flush(conversationId: String?, modelId: String?, assistantId: String?): Int {
        val names = VaultProviderKeyRefs.drainExpanded()
        if (names.isEmpty()) return 0
        val repository = runCatching { getKoin().get<CredentialVaultRepository>() }.getOrNull() ?: return 0
        var written = 0
        withContext(
            AuditContext(
                conversationId = conversationId,
                modelId = modelId,
                assistantId = assistantId,
                source = "provider",
            )
        ) {
            names.forEach { name ->
                runCatching { repository.logAccess(name, "provider", "resolve") }
                    .onSuccess { written++ }
            }
        }
        return written
    }

    /** 清零点：请求开始前丢弃上一次的遗留命中，保证归属不串档。 */
    fun reset() = VaultProviderKeyRefs.resetExpanded()
}
