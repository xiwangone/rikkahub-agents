package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_30_31"

/**
 * v30 → v31 (SSH 服务器样板与密钥引用).
 *
 * v31 adds two nullable columns to `ssh_hosts`:
 * - `vaultCredentialRef` — reference to a Vault credential holding the private key
 *   (connection resolves the key from Vault instead of plaintext Room storage)
 * - `templateRef` — name of the server template this host was created from (optional)
 *
 * Additive only — no data migration. Hand-written (same pattern as Migration_28_29 /
 * Migration_29_30) so the 30→31 path exists for users upgrading from the released v30.
 */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 30 to 31 (ssh_hosts add columns)")
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE `ssh_hosts` ADD COLUMN `vaultCredentialRef` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `ssh_hosts` ADD COLUMN `templateRef` TEXT DEFAULT NULL")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 30 to 31 success")
        } finally {
            db.endTransaction()
        }
    }
}
