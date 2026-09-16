package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.log.AppLog

private const val TAG = "Migration_34_35"

/**
 * v34 -> v35 (ssh_hosts fallback/jump columns).
 *
 * `SshHostEntity` gains `fallbackHostsJson` (JSON array of alternate saved-host names,
 * tried in order when the primary host is unreachable) and `jumpHost` (saved-host name
 * used as a jump/bastion, reserved for ProxyJump semantics). Additive only — no data
 * rewrite. Follows the Migration_33_34 hand-written pattern.
 */
val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        AppLog.i(TAG, "migrate: start migrate from 34 to 35 (ssh_hosts fallback/jump)")
        db.beginTransaction()
        try {
            db.execSQL(
                "ALTER TABLE `ssh_hosts` ADD COLUMN `fallbackHostsJson` TEXT DEFAULT NULL",
            )
            db.execSQL(
                "ALTER TABLE `ssh_hosts` ADD COLUMN `jumpHost` TEXT DEFAULT NULL",
            )
            db.setTransactionSuccessful()
            AppLog.i(TAG, "migrate: migrate from 34 to 35 success")
        } finally {
            db.endTransaction()
        }
    }
}
