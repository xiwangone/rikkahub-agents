package me.rerere.rikkahub.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import me.rerere.rikkahub.data.log.AppLog

/**
 * Reconciles a database file that was just restored from a backup so Room can open it.
 *
 * This app added several tables (scheduled jobs, workflows, ssh hosts, telegram chats,
 * the agent-run ledger) on top of an earlier build, and renumbered its Room schema so
 * the same version number no longer means the same thing in the two apps. A backup exported
 * from an earlier build (2.4.x) therefore breaks a fresh restore in two ways:
 *  - it is missing the app-specific tables, so Room fails its integrity check or hits "no such
 *    table: scheduled_jobs" at first query (issue #8); and
 *  - it is stamped with an older user_version (24 for 2.4.x), which is LOWER than this app's
 *    schema-equivalent version ([LEGACY_BASELINE_VERSION] = 27), so Room replays this app's
 *    24->25 / 25->26 / 26->27 auto-migrations and re-ADDs columns the file already carries,
 *    crashing with "duplicate column name: custom_system_prompt" (issues #10, #11).
 * Either way the app crashes on the very first launch after the import.
 *
 * This step runs once, right after the restore writes `rikka_hub.db`, on the raw file before
 * Room touches it:
 *  - It creates any of the app-specific tables that are missing, empty, with the exact v27
 *    schema (copied verbatim from app/schemas/.../27.json). Every statement is IF NOT EXISTS,
 *    so on a genuine backup of this app (where the tables already exist) it is a no-op.
 *  - A 2.4.x file (stamped below v27, carrying every [LEGACY_BASELINE_SENTINELS] column and
 *    none of the [POST_BASELINE_SENTINELS] columns) has shared tables byte-for-byte equal to
 *    this app's v27. It is re-stamped as a genuine v27 file ([LEGACY_BASELINE_VERSION] +
 *    [LEGACY_BASELINE_IDENTITY_HASH]); Room then runs this app's ordinary 27 -> current
 *    migrations, which add every later column, table and index exactly as on a real upgrade.
 *    It is deliberately NOT stamped straight to the current version: the current schema has
 *    many columns/tables a 2.4.x file lacks, and stamping past the migrations that add them
 *    would fail Room's validation (that shortcut only worked while 27 was the current version).
 *  - A file already stamped at [EXPECTED_VERSION] gets its identity row pointed at this
 *    app's current hash (harmless if it already is).
 *
 * Any other file (a genuine older backup of this app, or a pre-2.4 file without the baseline
 * columns) is left to Room's normal migrations. Backups newer than the app are left untouched
 * (Room reports the downgrade).
 *
 * Best-effort: any failure here is logged and returned as a failed [Result] instead of
 * thrown, so a restore never half-breaks — but the caller must treat a failed result as
 * a failed restore, never as success. The worst case is the same pre-existing crash on
 * next open, never data loss: there is no destructive-migration fallback configured, so
 * the restored rows always survive on disk (and the startup probe can swap the
 * pre-restore copy back in).
 */
object ImportedDatabaseReconciler {

    // 类名 ImportedDatabaseReconciler 24 字符, 超过 23 字符会被 logcat 截断, Database 缩写为 Db。
    private const val TAG = "ImportedDbReconciler"
    private const val DB_NAME = "rikka_hub"

    /**
     * Room's current schema version and identity hash for [AppDatabase]. The version follows
     * [AppDatabase.VERSION] directly; the hash is copied verbatim from
     * app/schemas/me.rerere.rikkahub.data.db.AppDatabase/<VERSION>.json (it also appears in the
     * generated AppDatabase_Impl RoomOpenDelegate). When the schema version is bumped, update the
     * hash — ImportedDatabaseReconcilerConstantsTest fails until it matches the exported schema.
     */
    internal const val EXPECTED_VERSION = AppDatabase.VERSION
    internal const val EXPECTED_IDENTITY_HASH = "c2222aef4fc160047a5db0cb2f35ac0e"

    /**
     * This app's version whose shared tables equal a 2.4.x file's, with its identity hash
     * (verbatim from app/schemas/.../27.json). Fixed history: never bump these with the schema.
     */
    internal const val LEGACY_BASELINE_VERSION = 27
    internal const val LEGACY_BASELINE_IDENTITY_HASH = "47dc97ce825856b039f96c2769103dd1"

    /**
     * Columns a file stamped below [LEGACY_BASELINE_VERSION] must carry to be treated as a
     * 2.4.x file. They are exactly the columns this app's 24->25 / 25->26 / 26->27
     * auto-migrations add (custom_system_prompt at 25, workspace_cwd at 26, folder_id at 27),
     * so a file carrying all three would collide on every one of those replays. A genuine file
     * of this app below v27 carries only a prefix, so it still migrates normally.
     */
    internal val LEGACY_BASELINE_SENTINELS: List<Pair<String, String>> =
        listOf(
            "ConversationEntity" to "custom_system_prompt",
            "ConversationEntity" to "workspace_cwd",
            "ConversationEntity" to "folder_id",
        )

    /**
     * Columns this app's migrations add AFTER v27 to tables that already exist at v27 (diff of
     * 27.json vs 48.json; ssh_hosts included because it is created here in its v27 shape).
     * A file that already carries any of them is not a plain 2.4.x file: re-stamping it to v27
     * would make Room's 27 -> current migrations re-ADD that column and crash the same way, so
     * such a file is left alone (Room then reports the mismatch and the startup probe can roll
     * back to the pre-restore copy). Add to this list when a later version adds a column to a
     * table that exists at v27.
     */
    internal val POST_BASELINE_SENTINELS: List<Pair<String, String>> =
        listOf(
            "ConversationEntity" to "chat_model_id",
            "ConversationEntity" to "is_sub_agent_run",
            "ConversationEntity" to "max_tool_steps_override",
            "ConversationEntity" to "tool_scope_override",
            "ConversationEntity" to "workspace_id_override",
            "MemoryEntity" to "tier",
            "workspaces" to "shell_compatibility_mode",
            "workspaces" to "tags",
            "workspaces" to "type",
            "workspaces" to "ssh_config",
            "ssh_hosts" to "vaultCredentialRef",
            "ssh_hosts" to "templateRef",
            "ssh_hosts" to "fallbackHostsJson",
            "ssh_hosts" to "jumpHost",
            "ssh_hosts" to "sshOptions",
            "ssh_hosts" to "platform",
            "ssh_hosts" to "allowHostKeyRotation",
        )

    /**
     * App-specific tables absent from a 2.4.x backup, with their exact v27 create + index
     * statements (the shape [LEGACY_BASELINE_VERSION] declares, so Room's 27 -> current
     * migrations find what they expect). Every statement is IF NOT EXISTS so running it against
     * a genuine backup of this app (where the tables already exist, possibly in a newer shape)
     * is a no-op.
     */
    internal val APP_SPECIFIC_DDL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `scheduled_jobs` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `prompt` TEXT, `assistantId` TEXT NOT NULL, `scheduleType` TEXT NOT NULL, `atUnixMs` INTEGER, `intervalSeconds` INTEGER, `enabled` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `lastRunAtMs` INTEGER, `nextRunAtMs` INTEGER, `mode` TEXT NOT NULL DEFAULT 'llm', `actionsJson` TEXT, `cronExpression` TEXT, `timezone` TEXT, `startAtUnixMs` INTEGER, `endAtUnixMs` INTEGER, `maxRuns` INTEGER, `runsSoFar` INTEGER NOT NULL DEFAULT 0, `catchup` TEXT NOT NULL DEFAULT 'fire_once', `description` TEXT, `tags` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `scheduled_job_runs` (`id` TEXT NOT NULL, `jobId` TEXT NOT NULL, `mode` TEXT NOT NULL, `scheduledAtMs` INTEGER NOT NULL, `startedAtMs` INTEGER NOT NULL, `finishedAtMs` INTEGER, `outcome` TEXT NOT NULL, `conversationId` TEXT, `errorMessage` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `ssh_hosts` (`name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `user` TEXT NOT NULL, `password` TEXT, `privateKey` TEXT, `passphrase` TEXT, `createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`name`))",
        "CREATE TABLE IF NOT EXISTS `telegram_chats` (`chatId` INTEGER NOT NULL, `conversationId` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `lastMessageAtMs` INTEGER NOT NULL, PRIMARY KEY(`chatId`))",
        "CREATE TABLE IF NOT EXISTS `workflows` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `enabled` INTEGER NOT NULL DEFAULT 1, `definitionJson` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `lastRunAtMs` INTEGER, `lastRunStatus` TEXT, `lastRunError` TEXT, `runsTodayCount` INTEGER NOT NULL DEFAULT 0, `runsTodayDate` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `workflow_runs` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workflowId` TEXT NOT NULL, `firedAtMs` INTEGER NOT NULL, `status` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `errorMessage` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_workflow_runs_workflowId_firedAtMs` ON `workflow_runs` (`workflowId`, `firedAtMs`)",
        "CREATE TABLE IF NOT EXISTS `agent_runs` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `domain_id` TEXT NOT NULL, `parent_run_id` TEXT, `status` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, `started_at_ms` INTEGER, `finished_at_ms` INTEGER, `last_error` TEXT, `metadata_json` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `idx_runs_status` ON `agent_runs` (`status`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_kind_dom` ON `agent_runs` (`kind`, `domain_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_parent` ON `agent_runs` (`parent_run_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_updated_at` ON `agent_runs` (`updated_at_ms`)",
    )

    /**
     * Call after a restore has written the database file, and only when the restore actually
     * included the database. Safe to call when the file is a genuine agent backup (every
     * statement is idempotent) or when the file does not exist (no-op).
     *
     * Returns a failed [Result] when reconciliation fails: the restored database is then
     * not known-good and the restore must be reported as failed, not as success.
     */
    fun reconcile(context: Context): Result<Unit> {
        return reconcileDatabaseFile(context.getDatabasePath(DB_NAME))
    }

    /**
     * 恢复/导入前把当前库另存一份（只保留最近一份），出问题时能人工回退。
     *
     * ⚠ **必须在恢复写库之前调用**：`reconcile()` 跑在 zip 覆盖**之后**，在那里备份只会备份到
     * 刚恢复的新库。恢复流程是「先 close appDatabase → 覆盖 rikka_hub.db/-wal/-shm → reconcile」，
     * 所以调用点放在 close 之后、覆盖之前。
     */
    fun backupBeforeRestore(context: Context) {
        runCatching {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) {
                AppLog.i(TAG, "backupBeforeRestore: no existing db, nothing to keep")
                return
            }
            val backup = File(dbFile.parentFile, "${dbFile.name}.pre-restore-backup")
            dbFile.copyTo(backup, overwrite = true)
            AppLog.i(TAG, "backupBeforeRestore: kept pre-restore copy at ${backup.name} (${backup.length()} bytes)")
        }.onFailure { AppLog.w(TAG, "backupBeforeRestore: failed to keep pre-restore copy", it) }
    }

    /**
     * 数据库打不开时（迁移失败、文件损坏…）的自救：把坏库改名存档，用恢复前留存的
     * `.pre-restore-backup` 换回，然后**主动结束进程**。
     *
     * 为什么结束进程：调用方（Application 启动探测）此刻已经处于"打开失败"状态，Room 的
     * openHelper 不会自己重试；重启后才能读到换回来的库 —— 用户再点一次图标即可，总好过
     * 永远进不去。
     *
     * 没有任何可回退的东西时什么都不做（保持原有行为，交给 CrashHandler 记录）。
     */
    fun recoverFromUnopenableDatabase(context: Context) {
        runCatching {
            val dbFile = context.getDatabasePath(DB_NAME)
            val backup = File(dbFile.parentFile, "${dbFile.name}.pre-restore-backup")
            if (!backup.exists()) {
                AppLog.w(TAG, "recoverFromUnopenableDatabase: no pre-restore copy to fall back to")
                return
            }
            val damaged = File(dbFile.parentFile, "${dbFile.name}.unopenable")
            if (damaged.exists()) damaged.delete()
            if (!dbFile.renameTo(damaged)) {
                AppLog.w(TAG, "recoverFromUnopenableDatabase: cannot move the unopenable db aside")
                return
            }
            backup.copyTo(dbFile, overwrite = true)
            File(dbFile.parentFile, "$DB_NAME-wal").delete()
            File(dbFile.parentFile, "$DB_NAME-shm").delete()
            AppLog.w(TAG, "recoverFromUnopenableDatabase: restored the pre-restore copy; damaged db kept as ${damaged.name}")
            android.os.Process.killProcess(android.os.Process.myPid())
        }.onFailure { AppLog.w(TAG, "recoverFromUnopenableDatabase: failed", it) }
    }

    /**
     * Testable core of [reconcile]: operate on the raw db file at [dbFile] directly, so a test
     * can exercise it against a temp file instead of the app's live `rikka_hub` database.
     */
    internal fun reconcileDatabaseFile(dbFile: File): Result<Unit> {
        if (!dbFile.exists()) {
            AppLog.i(TAG, "reconcile: no database file at ${dbFile.absolutePath}, skipping")
            return Result.success(Unit)
        }
        return try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                val version = db.version // PRAGMA user_version
                if (version > EXPECTED_VERSION) {
                    AppLog.w(TAG, "reconcile: backup db version $version is newer than $EXPECTED_VERSION; leaving untouched")
                    return Result.success(Unit)
                }

                // A 2.4.x file is stamped below v27 but already carries every column this app's
                // 24 -> 27 auto-migrations add; letting Room replay them crashes with
                // "duplicate column name" (issues #10, #11). Detect it by those very columns,
                // and make sure it carries nothing newer (see POST_BASELINE_SENTINELS).
                val legacyBaseline = version < LEGACY_BASELINE_VERSION &&
                    LEGACY_BASELINE_SENTINELS.all { (table, column) -> hasColumn(db, table, column) }
                val hasPostBaselineColumns = legacyBaseline &&
                    POST_BASELINE_SENTINELS.any { (table, column) -> hasColumn(db, table, column) }
                if (hasPostBaselineColumns) {
                    AppLog.w(TAG, "reconcile: db stamped $version carries columns newer than v$LEGACY_BASELINE_VERSION; not re-stamping")
                }

                db.beginTransaction()
                try {
                    APP_SPECIFIC_DDL.forEach(db::execSQL)

                    when {
                        version == EXPECTED_VERSION -> stampIdentity(db, EXPECTED_IDENTITY_HASH)
                        legacyBaseline && !hasPostBaselineColumns -> {
                            // Shared tables equal this app's v27 and every app-specific table now
                            // exists in its v27 shape: make the file a genuine v27 file, so Room
                            // runs the ordinary 27 -> current migrations instead of the colliding
                            // 24 -> 27 replays. PRAGMA user_version is transactional
                            // (SQLiteOpenHelper sets it the same way inside its own upgrade
                            // transaction), so this commits or rolls back atomically with the DDL
                            // and identity row.
                            stampIdentity(db, LEGACY_BASELINE_IDENTITY_HASH)
                            db.version = LEGACY_BASELINE_VERSION
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                AppLog.i(TAG, "reconcile: reconciled imported db (version=$version, legacyBaseline=$legacyBaseline)")
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            // Never let reconciliation break the restore by throwing, but do not swallow
            // the failure either: the caller must see it and report the restore as failed,
            // otherwise the app keeps a database that still crashes on next open.
            AppLog.e(TAG, "reconcile: failed to reconcile imported db", t)
            Result.failure(t)
        }
    }

    /** Points Room's identity row at [identityHash] (creating the metadata table if absent). */
    private fun stampIdentity(db: SQLiteDatabase, identityHash: String) {
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
            arrayOf(identityHash),
        )
    }

    /** True if [table] exists and has a column named [column]. Best-effort; false on error. */
    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        return try {
            db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                if (nameIndex >= 0) {
                    while (!found && cursor.moveToNext()) {
                        found = cursor.getString(nameIndex) == column
                    }
                }
                found
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "hasColumn: failed to inspect $table.$column", t)
            false
        }
    }
}
