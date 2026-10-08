package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        const val TIER_CORE = "core"
        const val TIER_CONDITIONAL = "conditional"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content, it.tier) }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content, it.tier) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content, it.tier) }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map { AssistantMemory(it.id, it.content, it.tier) }
    }

    /** 记忆分层：仅常驻 core（注入用） */
    suspend fun getCoreMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getCoreMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content, it.tier) }
    }

    /** 记忆检索（AI 按需触发）：匹配 conditional 记忆（全局 + 助手），用于 memory_search 注入 */
    suspend fun searchConditionalMemories(keyword: String): List<AssistantMemory> {
        return memoryDAO.searchConditionalMemories(keyword)
            .map { AssistantMemory(it.id, it.content, it.tier) }
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String, tier: String = TIER_CORE): AssistantMemory =
        updateMemoryContent(id, content, tier)

    /**
     * 只改内容、**保留原有分层**（工具卡上就地编辑用）。
     * ⚠ 不能走 [updateContent] 的默认 tier —— 那是 TIER_CORE，会把 conditional 记忆误升为 core。
     */
    suspend fun updateContentKeepingTier(id: Int, content: String): AssistantMemory =
        updateMemoryContent(id, content, null)

    /** tier 传 null = 保留记录原有分层。 */
    private suspend fun updateMemoryContent(id: Int, content: String, tier: String?): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val newMemory =
            old.copy(
                content = content,
                tier = tier ?: old.tier,
            )
        memoryDAO.updateMemory(newMemory)
        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
            tier = newMemory.tier,
        )
    }

    suspend fun addMemory(assistantId: String, content: String, tier: String = TIER_CORE): AssistantMemory {
        val memory = AssistantMemory(
            id = 0,
            content = content,
            tier = tier,
        )
        val newMemory = memory.copy(
            id = memoryDAO.insertMemory(
                MemoryEntity(
                    assistantId = assistantId,
                    content = memory.content,
                    tier = memory.tier,
                )
            ).toInt()
        )
        return newMemory
    }

    suspend fun copyMemories(fromAssistantId: String, toAssistantId: String) {
        val memories = getMemoriesOfAssistant(fromAssistantId)
        if (memories.isEmpty()) return
        memoryDAO.insertMemories(
            memories.map {
                MemoryEntity(assistantId = toAssistantId, content = it.content, tier = it.tier)
            }
        )
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
    }
}
