package me.rerere.rikkahub.data.db.migrations

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabase_AutoMigration_50_51_Impl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration test for the 50 -> 51 auto-migration (深挖-B1).
 *
 * Version 51 adds a composite UNIQUE index on `message_node (conversation_id, node_index)`:
 * at most one node per (conversation, nodeIndex). Before this, different node ids could
 * carry the same nodeIndex, and the UI — which orders by nodeIndex — showed two messages
 * at the same position. The DAO already inserts with OnConflictStrategy.REPLACE, so with
 * the unique index in place a re-insert of the same (conversation, nodeIndex) dedups
 * per-node instead of duplicating.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class Migration_50_51Test {
    private val testDbName = "migration-50-51-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun conversationValues(id: String): ContentValues = ContentValues().apply {
        put("id", id)
        put("title", "test conversation")
        put("nodes", "[]")
        put("create_at", 1700000000000L)
        put("update_at", 1700000000000L)
    }

    private fun nodeValues(id: String, conversationId: String, nodeIndex: Int): ContentValues =
        ContentValues().apply {
            put("id", id)
            put("conversation_id", conversationId)
            put("node_index", nodeIndex)
            put("messages", "[]")
            put("select_index", 0)
        }

    @Test
    fun migrate50To51_addsUniqueNodeIndex() {
        helper.createDatabase(testDbName, 50).apply { close() }

        // runMigrationsAndValidate also checks the resulting schema against 51.json.
        val db = helper.runMigrationsAndValidate(
            testDbName, 51, true, AppDatabase_AutoMigration_50_51_Impl()
        )

        val indexSql = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'index' " +
                "AND name = 'index_message_node_conversation_id_node_index'"
        ).use { cursor ->
            assertTrue("unique node index should exist after migration", cursor.moveToFirst())
            cursor.getString(0)
        }
        db.close()

        assertTrue(
            "index should be UNIQUE, got: $indexSql",
            indexSql.contains("UNIQUE", ignoreCase = true),
        )
        assertTrue(
            "index should cover (conversation_id, node_index), got: $indexSql",
            indexSql.contains("conversation_id") && indexSql.contains("node_index"),
        )
    }

    @Test
    fun migrate50To51_uniqueNodeIndexRejectsDuplicateNodeIndex() {
        helper.createDatabase(testDbName, 50).apply {
            insert("ConversationEntity", SQLiteDatabase.CONFLICT_NONE, conversationValues("conv-1"))
            insert(
                "message_node",
                SQLiteDatabase.CONFLICT_NONE,
                nodeValues("node-1", "conv-1", 0),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName, 51, true, AppDatabase_AutoMigration_50_51_Impl()
        )

        // Same (conversation_id, node_index), different node id: the unique index
        // must reject it instead of letting a second node share the position.
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL(
                "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                    "VALUES ('node-2', 'conv-1', 0, '[]', 0)"
            )
        }

        // A different nodeIndex in the same conversation still inserts fine.
        db.execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                "VALUES ('node-3', 'conv-1', 1, '[]', 0)"
        )
        val count = db.query("SELECT COUNT(*) FROM message_node").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        db.close()
        assertEquals("one pre-migration node plus the nodeIndex=1 node", 2, count)
    }
}
