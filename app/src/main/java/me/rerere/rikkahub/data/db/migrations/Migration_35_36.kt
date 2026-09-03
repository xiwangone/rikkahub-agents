package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_35_36"

/**
 * v35 -> v36 (ssh_hosts custom options column).
 *
 * `SshHostEntity` gains `sshOptions` — free-form lines of "key value" passed through
 * to JSch setConfig, letting a saved host override ciphers / timeouts / algorithms
 * without code changes. Additive only. Follows the Migration_34_35 pattern.
 */
val Migration_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 35 to 36 (ssh_hosts sshOptions)")
        db.beginTransaction()
        try {
            db.execSQL(
                "ALTER TABLE `ssh_hosts` ADD COLUMN `sshOptions` TEXT DEFAULT NULL",
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 35 to 36 success")
        } finally {
            db.endTransaction()
        }
    }
}
