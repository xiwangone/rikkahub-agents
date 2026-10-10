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

        override suspend fun getMemoryById(id: Int, assistantId: String): MemoryEntity? =
            rows.find { it.id == id && it.assistantId == assistantId }

        override suspend fun searchConditionalMemories(assistantId: String, keyword: String): List<MemoryEntity> =
            rows.filter { it.assistantId == assistantId && it.tier == "conditional" && it.content.contains(keyword) }

        override suspend fun insertMemory(memory: MemoryEntity): Long {
            val entity = memory.copy(id = nextId++)
            rows.add(entity)
            refresh()
            return entity.id.toLong()
        }

        override suspend fun insertMemories(memories: List<MemoryEntity>) {
            memories.forEach { insertMemory(it) }
        }

        override suspend fun updateMemoryContent(id: Int, assistantId: String, content: String, tier: String?): Int {
            val i = rows.indexOfFirst { it.id == id && it.assistantId == assistantId }
            if (i < 0) return 0
            val old = rows[i]
            rows[i] = old.copy(content = content, tier = tier ?: old.tier)
            refresh()
            return 1
        }

        override suspend fun deleteMemory(id: Int, assistantId: String): Int {
            val removed = rows.removeAll { it.id == id && it.assistantId == assistantId }
            refresh()
            return if (removed) 1 else 0
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

    @Test
    fun `conditional search returns only requested scope`() = runBlocking {
        dao.insertMemory(MemoryEntity(assistantId = "a1", content = "ECS account", tier = "conditional"))
        dao.insertMemory(MemoryEntity(assistantId = "a2", content = "ECS secret", tier = "conditional"))
        dao.insertMemory(MemoryEntity(assistantId = MemoryRepository.GLOBAL_MEMORY_ID, content = "ECS global", tier = "conditional"))

        assertEquals(listOf("ECS account"), repo.searchConditionalMemories("a1", "ECS").map { it.content })
        assertEquals(listOf("ECS global"), repo.searchConditionalMemories(MemoryRepository.GLOBAL_MEMORY_ID, "ECS").map { it.content })
    }

    @Test
    fun `content edit without a tier keeps the existing tier`() = runBlocking {
        val id = dao.insertMemory(
            MemoryEntity(assistantId = "a1", content = "old", tier = "conditional"),
        ).toInt()

        val updated = repo.updateContentKeepingTier("a1", id, "new")

        assertEquals("new", updated.content)
        assertEquals("conditional", updated.tier)
        assertEquals("conditional", dao.getMemoryById(id, "a1")?.tier)
    }

    @Test
    fun `update and delete cannot cross requested scope`() = runBlocking {
        val target = dao.insertMemory(MemoryEntity(assistantId = "a2", content = "private memory", tier = "conditional")).toInt()

        val updateFailed = runCatching { repo.updateContentKeepingTier("a1", target, "changed") }.isFailure
        val deleteFailed = runCatching { repo.deleteMemory("a1", target) }.isFailure

        assertTrue(updateFailed)
        assertTrue(deleteFailed)
        val untouched = dao.getMemoryById(target, "a2")
        assertEquals("private memory", untouched?.content)
        assertEquals("conditional", untouched?.tier)
    }
}
