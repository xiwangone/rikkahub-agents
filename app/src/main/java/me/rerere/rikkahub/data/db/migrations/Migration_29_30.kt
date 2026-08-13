package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_29_30"

/**
 * v29 → v30 (T10 压缩历史归档).
 *
 * v30 adds the compressed-history archive table `compressed_archives`
 * ([me.rerere.rikkahub.data.db.entity.CompressedArchiveEntity]) plus two indexes.
 *
 * Additive only — no data migration. Hand-written (same pattern as
 * Migration_28_29) so the 29→30 path exists for users upgrading from the
 * released v29 build. The CREATE TABLE / CREATE INDEX statements must match the
 * schema Room generates from the entity exactly.
 */
val Migration_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 29 to 30 (creating compressed_archives table)")
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `compressed_archives` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `conversationId` TEXT NOT NULL,
                    `compressedAtMs` INTEGER NOT NULL,
                    `archiveJson` TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_compressed_archives_conversationId` ON `compressed_archives` (`conversationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_compressed_archives_conversationId_compressedAtMs` ON `compressed_archives` (`conversationId`, `compressedAtMs`)")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 29 to 30 success")
        } finally {
            db.endTransaction()
        }
    }
}
