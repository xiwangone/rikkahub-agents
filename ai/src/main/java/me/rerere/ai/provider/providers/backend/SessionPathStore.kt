package me.rerere.ai.provider.providers.backend

/**
 * 对话 → 服务端会话路径 的持久化存取。
 *
 * 接入路径下服务端按会话维护上下文；App 侧需要在**重启后**仍能续接同一对话，
 * 因此把路径按对话 id 落盘（内存映射会随进程消失，导致旧对话退化为新建会话）。
 *
 * 该接口定义在 ai 模块（provider 所在层），实现由 app 层用 DataStore 提供并注入，
 * 避免 ai 层反向依赖应用数据层。
 */
interface SessionPathStore {
    /** 读取某对话已记录的服务端会话路径；无记录返回 null。 */
    suspend fun get(conversationId: String): String?

    /** 记录/更新某对话的服务端会话路径（空白值忽略）。 */
    suspend fun put(conversationId: String, path: String)

    /** 会话被删除时清理记录，避免残留映射把已删对话接回来。 */
    suspend fun remove(conversationId: String)

    companion object {
        /** 无持久化时的空实现（例如测试或未注入场景）：退回纯内存行为。 */
        val NOOP: SessionPathStore =
            object : SessionPathStore {
                override suspend fun get(conversationId: String): String? = null

                override suspend fun put(conversationId: String, path: String) = Unit

                override suspend fun remove(conversationId: String) = Unit
            }
    }
}
