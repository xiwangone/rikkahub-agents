package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity

@Dao
interface WorkspaceDAO {
    @Query("SELECT * FROM workspaces ORDER BY updated_at DESC")
    fun listFlow(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun getById(id: String): WorkspaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workspace: WorkspaceEntity)

    @Query("SELECT * FROM workspaces")
    suspend fun getAll(): List<WorkspaceEntity>

    /**
     * 同 [getAll] 的同步版，供 DocumentsProvider 查询路径调用：
     * ContentProvider 回调是同步 API，跑在 binder 线程上，不能挂起等待协程。
     */
    @Query("SELECT * FROM workspaces")
    fun getAllSync(): List<WorkspaceEntity>

    @Query("UPDATE workspaces SET shell_status = :shellStatus, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long): Int

    @Query("UPDATE workspaces SET shell_compatibility_mode = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean, updatedAt: Long): Int

    @Query("UPDATE workspaces SET tags = :tags, updated_at = :updatedAt WHERE id = :id")
    suspend fun setTags(id: String, tags: String, updatedAt: Long): Int

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
