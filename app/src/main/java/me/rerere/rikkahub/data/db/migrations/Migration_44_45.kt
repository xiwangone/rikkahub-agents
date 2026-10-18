package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds bounded translation history without touching existing user tables or rows. */
val Migration_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `translation_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source_text` TEXT NOT NULL, `translated_text` TEXT NOT NULL, `target_language` TEXT NOT NULL, `model_id` TEXT NOT NULL, `created_at` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_history_created_at` ON `translation_history` (`created_at`)")
    }
}
