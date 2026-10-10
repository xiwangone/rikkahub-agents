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
            .map { entities -> entities.map { AssistantMemory(it.id, it.content, it.tier) } }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content, it.tier) }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)

    suspend fun getGlobalMemories(): List<AssistantMemory> =
        getMemoriesOfAssistant(GLOBAL_MEMORY_ID)

    /** 记忆分层：仅常驻 core（注入用） */
    suspend fun getCoreMemoriesOfAssistant(assistantId: String): List<AssistantMemory> =
        memoryDAO.getCoreMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content, it.tier) }

    /** AI 按需检索：只在指定 scope 内匹配 conditional 记忆。 */
    suspend fun searchConditionalMemories(assistantId: String, keyword: String): List<AssistantMemory> =
        memoryDAO.searchConditionalMemories(assistantId, keyword)
            .map { AssistantMemory(it.id, it.content, it.tier) }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(
        assistantId: String,
        id: Int,
        content: String,
        tier: String,
    ): AssistantMemory = updateMemoryContent(assistantId, id, content, tier)

    /** 只改内容、保留原有分层；工具卡就地编辑用。 */
    suspend fun updateContentKeepingTier(
        assistantId: String,
        id: Int,
        content: String,
    ): AssistantMemory = updateMemoryContent(assistantId, id, content, null)

    /** tier=null 表示保留记录原有分层。所有读写均在 assistantId scope 下约束。 */
    private suspend fun updateMemoryContent(
        assistantId: String,
        id: Int,
        content: String,
        tier: String?,
    ): AssistantMemory {
        require(tier == null || tier == TIER_CORE || tier == TIER_CONDITIONAL) {
            "Unknown memory tier: $tier"
        }
        val old = memoryDAO.getMemoryById(id, assistantId)
            ?: error("Memory record #$id not found in the requested scope")
        val rows = memoryDAO.updateMemoryContent(id, assistantId, content, tier)
        if (rows != 1) error("Memory record #$id was deleted or moved before update")
        val updated = memoryDAO.getMemoryById(id, assistantId)
            ?: error("Memory record #$id is no longer available in the requested scope")
        return AssistantMemory(
            id = updated.id,
            content = updated.content,
            tier = updated.tier.ifBlank { old.tier },
        )
    }

    suspend fun addMemory(assistantId: String, content: String, tier: String = TIER_CORE): AssistantMemory {
        require(tier == TIER_CORE || tier == TIER_CONDITIONAL) { "Unknown memory tier: $tier" }
        val memory = memoryDAO.insertMemory(
            MemoryEntity(assistantId = assistantId, content = content, tier = tier),
        )
        return AssistantMemory(id = memory.toInt(), content = content, tier = tier)
    }

    suspend fun copyMemories(fromAssistantId: String, toAssistantId: String) {
        val memories = getMemoriesOfAssistant(fromAssistantId)
        if (memories.isEmpty()) return
        memoryDAO.insertMemories(
            memories.map { MemoryEntity(assistantId = toAssistantId, content = it.content, tier = it.tier) },
        )
    }

    suspend fun deleteMemory(assistantId: String, id: Int) {
        if (memoryDAO.deleteMemory(id, assistantId) != 1) {
            error("Memory record #$id not found in the requested scope")
        }
    }
}
