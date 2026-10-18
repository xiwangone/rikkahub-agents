package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity

@Dao
interface TranslationHistoryDao {
    @Insert
    suspend fun insert(entry: TranslationHistoryEntity): Long

    @Query("SELECT * FROM translation_history ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<TranslationHistoryEntity>>

    @Query("SELECT * FROM translation_history WHERE id = :id")
    suspend fun getById(id: Long): TranslationHistoryEntity?

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM translation_history WHERE id NOT IN (SELECT id FROM translation_history ORDER BY created_at DESC, id DESC LIMIT :limit)")
    suspend fun trimTo(limit: Int)
}
