package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND tier = 'core'")
    suspend fun getCoreMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND tier = 'core'")
    fun getCoreMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id AND assistant_id = :assistantId")
    suspend fun getMemoryById(id: Int, assistantId: String): MemoryEntity?

    /** 记忆检索（按需注入用）：只能查当前助手配置的记忆 scope。 */
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND tier = 'conditional' AND content LIKE '%' || :keyword || '%' ORDER BY id DESC")
    suspend fun searchConditionalMemories(assistantId: String, keyword: String): List<MemoryEntity>

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Insert
    suspend fun insertMemories(memories: List<MemoryEntity>)

    /** 返回实际更新行数；scope 不匹配时为 0，避免仅凭全局 id 修改其它助手数据。 */
    @Query("UPDATE memoryentity SET content = :content, tier = COALESCE(:tier, tier) WHERE id = :id AND assistant_id = :assistantId")
    suspend fun updateMemoryContent(id: Int, assistantId: String, content: String, tier: String?): Int

    /** 返回实际删除行数；scope 不匹配时为 0。 */
    @Query("DELETE FROM memoryentity WHERE id = :id AND assistant_id = :assistantId")
    suspend fun deleteMemory(id: Int, assistantId: String): Int

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}
