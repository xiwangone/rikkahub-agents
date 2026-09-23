package me.rerere.rikkahub.data.vault

import kotlin.coroutines.CoroutineContext

/**
 * 审计归属上下文：生成链路把「谁在调用」经协程上下文透传给审计写入点
 * （立项方案 A —— 零接口变更：`Tool.execute` 与 `logAccess` 签名都不动）。
 *
 * 只记 id 不记内容；无会话的后台任务（备份 / S3 / Web 桥 / 定时）只填 [source] 标来源，不硬凑会话。
 */
data class AuditContext(
    /** 归属会话 id */
    val conversationId: String? = null,
    /** 归属模型配置 id */
    val modelId: String? = null,
    /** 归属助手 id */
    val assistantId: String? = null,
    /** 来源标记：ai-tool / provider / backup / web-bridge 等 */
    val source: String? = null,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<AuditContext>

    override val key: CoroutineContext.Key<*> get() = Key
}
