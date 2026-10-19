@file:Suppress("TooGenericExceptionCaught", "NestedBlockDepth", "CyclomaticComplexMethod") // 备份快照：文件/数据库操作的分支与失败边界平铺，拆开会割裂与 schema 的对照
package me.rerere.rikkahub.data.sync

import android.database.sqlite.SQLiteDatabase as AndroidSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Shared, standalone SQLite snapshot policy for local, S3 and WebDAV archives. */
internal object DatabaseBackup {
    const val ARCHIVE_DATABASE = "rikka_hub.db"
    /** Reserved archive root for future media payloads; intentionally independent of Room schemas. */
    const val MEDIA_ARCHIVE_ROOT = "media"
    private const val MAX_DATABASE_BYTES = 2L * 1024 * 1024 * 1024

    /**
     * Writes a consistent standalone database. VACUUM INTO is preferred because SQLite includes
     * committed WAL frames in its snapshot. Older SQLite builds use rollback journal mode; every
     * journal transition/checkpoint is checked and any failure aborts instead of copying anyway.
     * Must be called outside a Room transaction.
     */
    fun createSnapshot(database: SupportSQLiteDatabase, destination: File) {
        check(!database.inTransaction()) { "Cannot snapshot inside a Room transaction" }
        require(!destination.exists()) { "Snapshot destination already exists" }
        val parent = requireNotNull(destination.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create snapshot directory" }
        try {
            // SQL values are bound, never interpolated into SQL.
            database.execSQL("VACUUM main INTO ?", arrayOf(destination.absolutePath))
            validate(destination)
            return
        } catch (vacuumFailure: Exception) {
            destination.delete()
            // Only retry errors that indicate VACUUM INTO is unavailable. I/O and disk-full
            // failures must not fall through to a potentially weaker copy strategy.
            val message = vacuumFailure.message.orEmpty().lowercase()
            if (!(message.contains("syntax") || message.contains("not supported") ||
                        message.contains("near \"into\""))) {
                throw vacuumFailure
            }
        }

        var originalJournalMode: String? = null
        var transactionStarted = false
        var copyFailure: Throwable? = null
        try {
            originalJournalMode = pragmaString(database, "PRAGMA journal_mode")
            check(!originalJournalMode.isNullOrBlank()) { "Could not read SQLite journal mode" }
            if (originalJournalMode.equals("wal", ignoreCase = true)) checkpoint(database)
            if (!originalJournalMode.equals("delete", ignoreCase = true)) {
                check(pragmaString(database, "PRAGMA journal_mode=DELETE")
                    ?.equals("delete", ignoreCase = true) == true) {
                    "SQLite refused rollback-journal mode; snapshot aborted"
                }
            }
            // The verified checkpoint precedes the journal transition; no live Room transaction
            // is active and beginTransactionNonExclusive locks out writers during the file copy.
            database.beginTransactionNonExclusive()
            transactionStarted = true
            FileInputStream(database.path).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output); output.fd.sync() }
            }
            database.setTransactionSuccessful()
        } catch (t: Throwable) {
            copyFailure = t
        } finally {
            if (transactionStarted) {
                try { database.endTransaction() } catch (t: Throwable) {
                    if (copyFailure == null) copyFailure = t else copyFailure!!.addSuppressed(t)
                }
            }
            if (originalJournalMode != null && !originalJournalMode.equals("delete", ignoreCase = true)) {
                try {
                    check(pragmaString(database, "PRAGMA journal_mode=WAL")
                        ?.equals("wal", ignoreCase = true) == true) {
                        "Could not restore SQLite WAL mode"
                    }
                } catch (t: Throwable) {
                    if (copyFailure == null) copyFailure = t else copyFailure!!.addSuppressed(t)
                }
            }
        }
        copyFailure?.let { destination.delete(); throw it }
        try { validate(destination) } catch (t: Throwable) { destination.delete(); throw t }
    }

    private fun pragmaString(db: SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun checkpoint(db: SupportSQLiteDatabase) {
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0 && cursor.getInt(1) == 0) {
                "SQLite WAL checkpoint failed; refusing an inconsistent snapshot"
            }
        }
    }

    /** Validates only a staged file, never the live database. */
    fun validate(file: File) {
        require(file.isFile && file.length() in 1..MAX_DATABASE_BYTES) { "Missing, empty or oversized backup database" }
        val db = AndroidSQLiteDatabase.openDatabase(file.absolutePath, null, AndroidSQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) {
                    "Backup database failed SQLite quick_check"
                }
            }
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                check(cursor.moveToFirst()) { "Backup database has no schema version" }
                val version = cursor.getInt(0)
                check(version in 1..me.rerere.rikkahub.data.db.AppDatabase.VERSION) { "Unsupported backup database version $version" }
            }
            val tables = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            // Old Room snapshots migrate forward, but must at least contain Room metadata
            // and the two core application tables. This rejects valid-but-unrelated SQLite files.
            check("room_master_table" in tables && "ConversationEntity" in tables && "message_node" in tables) {
                "Backup database is missing required Room tables"
            }
        } finally { db.close() }
    }
}
