package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_32_33"

/**
 * v32 → v33 (子代理自选模型 + 上下文压缩归档).
 *
 * Two additive changes that shipped after schema 32.json was last exported
 * (identity dcecb560):
 * - `ConversationEntity` gains `chat_model_id` (sub-agent dispatch picking its
 *   own model); default "" keeps existing rows on the assistant default.
 * - new table `conversation_compaction` (compaction archive per conversation,
 *   FK cascade on conversation delete).
 *
 * Room's compiled schema for v32 also declares three indices that pre-33
 * devices never had (they were added to the @Entity annotations alongside the
 * new column/table); they are created here so the post-migration validation
 * matches. Additive only — no data rewrite. Hand-written following the
 * Migration_31_32 pattern.
 */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 32 to 33 (chat_model_id + conversation_compaction)")
        db.beginTransaction()
        try {
            db.execSQL(
                "ALTER TABLE `ConversationEntity` ADD COLUMN `chat_model_id` TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `conversation_compaction` (" +
                    "`conversation_id` TEXT NOT NULL, " +
                    "`summary` TEXT NOT NULL, " +
                    "`tail_start_node_id` TEXT, " +
                    "`source_end_node_id` TEXT NOT NULL, " +
                    "`summary_model_id` TEXT NOT NULL, " +
                    "`is_auto` INTEGER NOT NULL, " +
                    "`source_token_estimate` INTEGER NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`conversation_id`), " +
                    "FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_assistant_id_is_pinned_update_at` " +
                    "ON `ConversationEntity` (`assistant_id`, `is_pinned`, `update_at`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_is_pinned_update_at` " +
                    "ON `ConversationEntity` (`is_pinned`, `update_at`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_MemoryEntity_assistant_id` " +
                    "ON `MemoryEntity` (`assistant_id`)",
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 32 to 33 success")
        } finally {
            db.endTransaction()
        }
    }
}
