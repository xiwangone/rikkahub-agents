package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryRepository.copyMemories] 回归测试（复制助手时可一并复制记忆）：
 * 复制助手时可勾选「同时复制记忆」，把源助手的记忆复制给新助手。
 */
class MemoryRepositoryCopyTest {
    private class FakeMemoryDAO : MemoryDAO {
        private val rows = mutableListOf<MemoryEntity>()
        private val flow = MutableStateFlow<List<MemoryEntity>>(emptyList())
        private var nextId = 1

        private fun refresh() {
            flow.value = rows.toList()
        }

        override fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> =
            flow.map { list -> list.filter { it.assistantId == assistantId } }

        override suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity> =
            rows.filter { it.assistantId == assistantId }

        override suspend fun getCoreMemoriesOfAssistant(assistantId: String): List<MemoryEntity> =
            rows.filter { it.assistantId == assistantId && it.tier == "core" }

        override fun getCoreMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> =
            flow.map { list -> list.filter { it.assistantId == assistantId && it.tier == "core" } }

        override fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = flow

        override suspend fun getAllMemories(): List<MemoryEntity> = rows.toList()

        override suspend fun getMemoryById(id: Int): MemoryEntity? = rows.find { it.id == id }

        override suspend fun searchConditionalMemories(keyword: String): List<MemoryEntity> =
            rows.filter { it.tier == "conditional" && it.content.contains(keyword) }

        override suspend fun insertMemory(memory: MemoryEntity): Long {
            val entity = memory.copy(id = nextId++)
            rows.add(entity)
            refresh()
            return entity.id.toLong()
        }

        override suspend fun insertMemories(memories: List<MemoryEntity>) {
            memories.forEach { insertMemory(it) }
        }

        override suspend fun updateMemory(memory: MemoryEntity) {
            val i = rows.indexOfFirst { it.id == memory.id }
            if (i >= 0) {
                rows[i] = memory
                refresh()
            }
        }

        override suspend fun deleteMemory(id: Int) {
            rows.removeAll { it.id == id }
            refresh()
        }

        override suspend fun deleteMemoriesOfAssistant(assistantId: String) {
            rows.removeAll { it.assistantId == assistantId }
            refresh()
        }
    }

    private val dao = FakeMemoryDAO()
    private val repo = MemoryRepository(dao)

    @Test
    fun `copyMemories copies content and tier with fresh ids`() = runBlocking {
        dao.insertMemory(MemoryEntity(assistantId = "a1", content = "likes tea", tier = "core"))
        dao.insertMemory(MemoryEntity(assistantId = "a1", content = "peanut allergy", tier = "conditional"))

        repo.copyMemories(fromAssistantId = "a1", toAssistantId = "a2")

        val copied = dao.getMemoriesOfAssistant("a2")
        assertEquals(2, copied.size)
        assertEquals(listOf("likes tea", "peanut allergy"), copied.map { it.content })
        assertEquals(listOf("core", "conditional"), copied.map { it.tier })
        // 新记录必须拿新 id，不能复用源记录 id
        val sourceIds = dao.getMemoriesOfAssistant("a1").map { it.id }.toSet()
        assertTrue(copied.none { it.id in sourceIds })
    }

    @Test
    fun `copyMemories on empty source inserts nothing`() = runBlocking {
        repo.copyMemories(fromAssistantId = "ghost", toAssistantId = "a2")

        assertTrue(dao.getAllMemories().isEmpty())
    }

    @Test
    fun `copyMemories leaves source and other assistants untouched`() = runBlocking {
        dao.insertMemory(MemoryEntity(assistantId = "a1", content = "m1"))
        dao.insertMemory(MemoryEntity(assistantId = "a9", content = "other"))

        repo.copyMemories(fromAssistantId = "a1", toAssistantId = "a2")

        assertEquals(listOf("m1"), dao.getMemoriesOfAssistant("a1").map { it.content })
        assertEquals(listOf("other"), dao.getMemoriesOfAssistant("a9").map { it.content })
        assertEquals(3, dao.getAllMemories().size)
    }
}
