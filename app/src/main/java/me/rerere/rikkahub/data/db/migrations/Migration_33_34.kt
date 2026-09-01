package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_33_34"

/**
 * v33 鈫?v34 (瀵嗛挜搴?SSH 鍏挜瀛楁).
 *
 * `VaultCredentialEntity` gains a `publicKey` column (plaintext, SSH public key
 * for key-pair entries; empty for regular credentials). Additive only 鈥?no data
 * rewrite. Follows the Migration_32_33 hand-written pattern.
 */
val Migration_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 33 to 34 (vault_credentials.publicKey)")
        db.beginTransaction()
        try {
            db.execSQL(
                "ALTER TABLE `vault_credentials` ADD COLUMN `publicKey` TEXT NOT NULL DEFAULT ''",
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 33 to 34 success")
        } finally {
            db.endTransaction()
        }
    }
}
