package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_31_32"

/**
 * v31 → v32 (记忆分层).
 *
 * v32 adds one column to `memoryentity`:
 * - `tier` — memory tier: "core" (always injected) / "conditional" (retrieved on demand
 *   via memory_search). Default "core" keeps existing memories fully injected.
 *
 * Additive only — no data migration. Hand-written (same pattern as Migration_30_31)
 * because the 31→32 auto-migration would need schema 31.json which was never exported
 * (27→31 was a jump step; 31 was only ever a terminal version).
 */
val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 31 to 32 (memoryentity add tier)")
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `tier` TEXT NOT NULL DEFAULT 'core'")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 31 to 32 success")
        } finally {
            db.endTransaction()
        }
    }
}
