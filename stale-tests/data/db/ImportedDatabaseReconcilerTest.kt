package me.rerere.rikkahub.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the pure SQL-list constants behind [ImportedDatabaseReconciler].
 *
 * [ImportedDatabaseReconciler.reconcileDatabaseFile] itself opens a real
 * `android.database.sqlite.SQLiteDatabase`, which is not available in this repo's plain JVM
 * unit tests (no Robolectric), so it cannot be exercised here. What *is* pure, plain Kotlin is
 * [ImportedDatabaseReconciler.BACKFILL_INDEX_DDL] and the [ImportedDatabaseReconciler.EXPECTED_VERSION]
 * / [ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH] constants it stamps a reconciled file with.
 *
 * Issue #60: restoring an official upstream backup (db stamped at v24) and letting the
 * reconciler jump it straight to [ImportedDatabaseReconciler.EXPECTED_VERSION] skipped the
 * fork's 27->28 auto-migration entirely, so `ConversationEntity` ended up with no indices at
 * all (`Found: indices = {}`) even though Room's compiled schema for v30 expects two. This test
 * pins the fix: every index that migration would have added is present in the backfill list,
 * every statement is idempotent, and the stamped version/hash match what app/schemas/.../30.json
 * actually declares - so a future schema bump that forgets to update the reconciler shows up as
 * a failing assertion here instead of a silent restore crash.
 */
class ImportedDatabaseReconcilerTest {

    @Test
    fun `expected version matches the fork's current AppDatabase version`() {
        assertEquals(30, ImportedDatabaseReconciler.EXPECTED_VERSION)
    }

    @Test
    fun `expected identity hash matches schema 30`() {
        assertEquals("4969a8576be916e3bd22e4a9a48a272d", ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH)
    }

    @Test
    fun `backfills both ConversationEntity indices Room's 27-28 migration adds`() {
        val ddl = ImportedDatabaseReconciler.BACKFILL_INDEX_DDL
        assertTrue(ddl.any {
            it.contains("index_ConversationEntity_assistant_id_is_pinned_update_at") &&
                it.contains("ON `ConversationEntity` (`assistant_id`, `is_pinned`, `update_at`)")
        })
        assertTrue(ddl.any {
            it.contains("index_ConversationEntity_is_pinned_update_at") &&
                it.contains("ON `ConversationEntity` (`is_pinned`, `update_at`)")
        })
    }

    @Test
    fun `backfills the MemoryEntity and scheduled-job indices from the same migration`() {
        val ddl = ImportedDatabaseReconciler.BACKFILL_INDEX_DDL
        assertTrue(ddl.any { it.contains("index_MemoryEntity_assistant_id") })
        assertTrue(ddl.any { it.contains("index_scheduled_jobs_enabled") })
        assertTrue(ddl.any { it.contains("index_scheduled_job_runs_jobId_startedAtMs") })
        assertTrue(ddl.any { it.contains("index_scheduled_job_runs_jobId_outcome") })
    }

    @Test
    fun `every backfill statement is idempotent`() {
        ImportedDatabaseReconciler.BACKFILL_INDEX_DDL.forEach {
            assertTrue("not idempotent: $it", it.startsWith("CREATE INDEX IF NOT EXISTS"))
        }
    }
}
