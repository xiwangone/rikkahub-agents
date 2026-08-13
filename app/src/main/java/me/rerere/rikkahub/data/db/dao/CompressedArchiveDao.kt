package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.CompressedArchiveEntity

/** T10: 压缩历史归档查询（数据面先落地；UI 回看后续可选）。 */
@Dao
interface CompressedArchiveDao {
    @Insert suspend fun insert(archive: CompressedArchiveEntity)

    @Query("SELECT * FROM compressed_archives WHERE conversationId = :conversationId ORDER BY compressedAtMs DESC")
    suspend fun listByConversation(conversationId: String): List<CompressedArchiveEntity>
}
