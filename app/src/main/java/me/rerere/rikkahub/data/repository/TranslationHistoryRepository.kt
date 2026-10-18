package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.TranslationHistoryDao
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity

class TranslationHistoryRepository(
    private val dao: TranslationHistoryDao,
    private val database: AppDatabase,
) {
    companion object {
        const val MAX_ENTRIES = 100
    }

    fun observeHistory(): Flow<List<TranslationHistoryEntity>> = dao.observeAll()

    suspend fun add(
        sourceText: String,
        translatedText: String,
        targetLanguage: String,
        modelId: String,
        createdAt: Long = System.currentTimeMillis(),
    ): Long {
        return database.withTransaction {
            dao.insert(
                TranslationHistoryEntity(
                sourceText = sourceText,
                translatedText = translatedText,
                targetLanguage = targetLanguage,
                modelId = modelId,
                createdAt = createdAt,
                ),
            ).also { dao.trimTo(MAX_ENTRIES) }
        }
    }

    suspend fun get(id: Long): TranslationHistoryEntity? = dao.getById(id)

    suspend fun delete(id: Long) = dao.delete(id)
}
