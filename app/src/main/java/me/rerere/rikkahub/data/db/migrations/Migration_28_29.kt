package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_28_29"

/**
 * v28 → v29 (2.45.5 → 2.45.6).
 *
 * v28 shipped in Release 2.45.5 (Vault MVP: `vault_credentials`), so real devices
 * are already on schema 28. v29 adds the usage audit table `vault_audit_log`
 * ([me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity]) plus its two indexes.
 *
 * Additive only — no data migration. Hand-written instead of an AutoMigration so
 * the path 28→29 exists for users upgrading from the released 2.45.5 build; the
 * schema in AppDatabase keeps the 27→29 AutoMigration for users still on v27
 * (both paths are valid and Room picks the one matching the device's current
 * version). The CREATE TABLE / CREATE INDEX statements must match the schema
 * Room generates from [me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity]
 * exactly, or `runMigrationsAndValidate` fails the test.
 */
val Migration_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 28 to 29 (creating vault_audit_log table)")
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `vault_audit_log` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `credentialName` TEXT NOT NULL,
                    `caller` TEXT NOT NULL,
                    `action` TEXT NOT NULL,
                    `tsMs` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_audit_ts` ON `vault_audit_log` (`tsMs`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_audit_name` ON `vault_audit_log` (`credentialName`)")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 28 to 29 success")
        } finally {
            db.endTransaction()
        }
    }
}
