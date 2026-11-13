package me.rerere.rikkahub.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.migrations.APP_DATABASE_MIGRATIONS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for issues #10 / #11: restoring a 2.4.1 backup into the
 * app crashed on the first launch after the import with
 * "duplicate column name: custom_system_prompt".
 *
 * A 2.4.1 database is stamped at user_version 24, but its ConversationEntity already
 * carries custom_system_prompt / workspace_cwd / folder_id (added in its own
 * earlier versions). This app's schema-equivalent version is 27, so an un-reconciled restore
 * makes Room replay this app's 24 -> 25 auto-migration, which re-ADDs custom_system_prompt and
 * crashes. This app's shared tables at v27 are byte-for-byte identical to the earlier build's at v24, so
 * [ImportedDatabaseReconciler] re-stamps such a file as a genuine v27 file and lets Room run the
 * ordinary 27 -> current migrations (the JVM twin of this test is
 * app/src/test/.../data/db/ImportedDatabaseReconcilerTest.kt).
 *
 * The test builds a faithful 2.4.1 file (this app's v27 shared schema from 27.json via
 * [MigrationTestHelper], the earlier version + identity, app-specific tables removed) and asserts:
 *  - without reconcile, opening it through Room reproduces the reported duplicate-column crash;
 *  - after reconcile, Room migrates it to the current schema, the seeded conversation survives,
 *    and every app-specific table exists and starts empty.
 */
@RunWith(AndroidJUnit4::class)
class ImportedDatabaseReconcilerTest {

    private val TEST_DB = "reconciler-legacy-241-test"

    // A 2.4.1 file's actual stamp: user_version 24 plus its own (foreign) identity.
    private val LEGACY_VERSION = 24
    private val LEGACY_IDENTITY = "0ea1aaebfa031c7995c45a1e35822e1a"

    private val APP_SPECIFIC_TABLES = listOf(
        "scheduled_jobs", "scheduled_job_runs", "ssh_hosts", "telegram_chats",
        "workflows", "workflow_runs", "agent_runs",
    )

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun withoutReconcile_legacy241Backup_reproducesDuplicateColumnCrash() {
        createLegacy241Backup(conversationId = "c1")

        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*APP_DATABASE_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            // Forcing the db open replays the 24 -> 25 auto-migration; this is where the
            // reported crash fired.
            room.openHelper.writableDatabase
                .query("SELECT COUNT(*) FROM ConversationEntity").use { it.moveToFirst() }
            fail("expected Room to crash replaying the 24 -> 25 migration on a 2.4.1 file")
        } catch (expected: Throwable) {
            val chain = generateSequence<Throwable>(expected) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" | ")
            assertTrue(
                "expected a duplicate-column failure on custom_system_prompt, got: $chain",
                chain.contains("custom_system_prompt") || chain.contains("duplicate column"),
            )
        } finally {
            room.close()
        }
    }

    @Test
    fun afterReconcile_legacy241Backup_opensAndKeepsData() {
        createLegacy241Backup(conversationId = "c1")

        ImportedDatabaseReconciler.reconcileDatabaseFile(context.getDatabasePath(TEST_DB))

        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*APP_DATABASE_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase // runs 27 -> current and validates now
            assertEquals(AppDatabase.VERSION, db.version)

            db.query("SELECT title FROM ConversationEntity WHERE id = 'c1'").use { c ->
                assertTrue("seeded conversation row should survive the restore", c.moveToFirst())
                assertEquals("hello", c.getString(0))
            }

            for (table in APP_SPECIFIC_TABLES) {
                db.query("SELECT COUNT(*) FROM `$table`").use { c ->
                    assertTrue("app-specific table $table should exist after reconcile", c.moveToFirst())
                    assertEquals("app-specific table $table should start empty", 0, c.getInt(0))
                }
            }
        } finally {
            room.close()
        }
    }

    /**
     * Writes a file that looks exactly like a 2.4.1 backup: start from a genuine v27 database
     * built from app/schemas/.../27.json (the shape a 2.4.1 file shares), seed a conversation,
     * then downgrade the file on disk by dropping the app-specific tables and stamping the
     * earlier user_version + identity.
     */
    private fun createLegacy241Backup(conversationId: String) {
        helper.createDatabase(TEST_DB, ImportedDatabaseReconciler.LEGACY_BASELINE_VERSION).apply {
            execSQL(
                "INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(conversationId, "hello", "[]", 1_000L, 1_000L),
            )
            close()
        }

        val dbFile = context.getDatabasePath(TEST_DB)
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            APP_SPECIFIC_TABLES.forEach { raw.execSQL("DROP TABLE IF EXISTS `$it`") }
            raw.execSQL(
                "UPDATE room_master_table SET identity_hash = ? WHERE id = 42",
                arrayOf<Any?>(LEGACY_IDENTITY),
            )
            raw.version = LEGACY_VERSION // PRAGMA user_version = 24
        }
    }
}
