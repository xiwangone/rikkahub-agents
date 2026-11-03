package me.rerere.rikkahub.data.db.fts

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("message_fts.rank, conversation.update_at DESC"),
    NEWEST_FIRST("conversation.update_at DESC, message_fts.rank"),
    OLDEST_FIRST("conversation.update_at ASC, message_fts.rank"),
}

private const val TAG = "MessageFtsManager"

/**
 * Schema for the message_fts FTS5 virtual table. Defined here so the table-init path in
 * DataSourceModule and the Doctor's "rebuild search index" repair path use the same DDL.
 */
const val MESSAGE_FTS_CREATE_SQL = """
    CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
        text,
        node_id UNINDEXED,
        message_id UNINDEXED,
        conversation_id UNINDEXED,
        title UNINDEXED,
        update_at UNINDEXED,
        tokenize = 'simple'
    )
"""

/**
 * 建立消息索引用到的表，每次打开数据库时调用。
 *
 * message_fts 是 FTS5 虚表，除 rowid 外的列都无法用索引定位，按 conversation_id 删除会扫描全部消息；
 * 所以另用普通表 message_fts_rowid 记录每行索引属于哪个会话，按会话增删都先经它查出 rowid。
 * title、update_at 两列搜索时直接联表从 conversationentity 读取，保留只是为了已有索引不用重建。
 */
internal fun createMessageFtsTables(db: SupportSQLiteDatabase) {
    db.execSQL(MESSAGE_FTS_CREATE_SQL.trimIndent())
    val hasRowidTable =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'message_fts_rowid'")
            .use { it.moveToFirst() }
    if (hasRowidTable) return
    db.beginTransaction()
    try {
        db.execSQL("CREATE TABLE message_fts_rowid(fts_rowid INTEGER PRIMARY KEY, conversation_id TEXT NOT NULL)")
        db.execSQL("CREATE INDEX index_message_fts_rowid_conversation_id ON message_fts_rowid(conversation_id)")
        // 旧版本建好的索引只需补上对应关系，读一遍 message_fts 的内容表，不用重新分词
        db.execSQL(
            "INSERT INTO message_fts_rowid(fts_rowid, conversation_id) SELECT rowid, conversation_id FROM message_fts",
        )
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

private class IndexedMessage(val rowid: Long, val nodeId: String, val text: String)

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    /**
     * Drop and recreate the message_fts virtual table and its rowid lookup table.
     */
    suspend fun dropAndRecreate() = withContext(Dispatchers.IO) {
        db.execSQL("DROP TABLE IF EXISTS message_fts")
        db.execSQL("DROP TABLE IF EXISTS message_fts_rowid")
        createMessageFtsTables(db)
    }

    /**
     * 增量更新会话的索引：只重写新增或内容变了的消息，删掉已不存在的消息。
     * 每次保存会话都会调用，长会话里大部分消息没变，不必整份删掉重新分词。
     */
    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val conversationId = conversation.id.toString()
            val writable = db
            val indexed = HashMap<String, IndexedMessage>()
            val stale = mutableListOf<Long>()
            writable.query(
                """
                SELECT f.rowid, f.node_id, f.message_id, f.text
                FROM message_fts_rowid AS r JOIN message_fts AS f ON f.rowid = r.fts_rowid
                WHERE r.conversation_id = ?
                """.trimIndent(),
                arrayOf(conversationId),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = IndexedMessage(cursor.getLong(0), cursor.getString(1), cursor.getString(3))
                    if (indexed.putIfAbsent(cursor.getString(2), row) != null) {
                        stale.add(row.rowid)
                    }
                }
            }
            conversation.messageNodes.forEach { node ->
                val nodeId = node.id.toString()
                node.messages.forEach { message ->
                    val text = message.extractFtsText()
                    val existing = indexed.remove(message.id.toString())
                    if (existing != null && existing.nodeId == nodeId && existing.text == text) return@forEach
                    existing?.let { stale.add(it.rowid) }
                    if (text.isNotBlank()) {
                        writable.execSQL(
                            "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                            arrayOf(
                                text,
                                nodeId,
                                message.id.toString(),
                                conversationId,
                                conversation.title,
                                conversation.updateAt.toEpochMilli().toString(),
                            ),
                        )
                        writable.execSQL(
                            "INSERT OR REPLACE INTO message_fts_rowid(fts_rowid, conversation_id) VALUES (last_insert_rowid(), ?)",
                            arrayOf(conversationId),
                        )
                    }
                }
            }
            indexed.values.forEach { stale.add(it.rowid) }
            stale.forEach { rowid ->
                writable.execSQL("DELETE FROM message_fts WHERE rowid = ?", arrayOf(rowid))
                writable.execSQL("DELETE FROM message_fts_rowid WHERE fts_rowid = ?", arrayOf(rowid))
            }
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            db.execSQL(
                "DELETE FROM message_fts WHERE rowid IN (SELECT fts_rowid FROM message_fts_rowid WHERE conversation_id = ?)",
                arrayOf(conversationId),
            )
            db.execSQL("DELETE FROM message_fts_rowid WHERE conversation_id = ?", arrayOf(conversationId))
        }
    }

    /**
     * Title is read directly from `conversationentity` via JOIN in [search], so renaming a
     * conversation no longer requires scanning `message_fts`. Kept for API compatibility.
     */
    @Suppress("UnusedParameter")
    suspend fun updateConversationTitle(conversationId: String, title: String) {
        // No-op: search() joins conversationentity for live title and update_at.
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        database.withTransaction {
            db.execSQL("DELETE FROM message_fts")
            db.execSQL("DELETE FROM message_fts_rowid")
        }
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: String? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        val assistantFilter =
            if (assistantId != null) {
                "AND conversation.assistant_id = ?"
            } else {
                ""
            }
        val cursor =
            db.query(
                """
                SELECT message_fts.node_id, message_fts.message_id, message_fts.conversation_id,
                       conversation.title, conversation.update_at,
                       simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
                FROM message_fts
                JOIN conversationentity AS conversation ON conversation.id = message_fts.conversation_id
                WHERE message_fts.text MATCH jieba_query(?)
                $assistantFilter
                ORDER BY ${sort.orderBy}
                LIMIT 50
                """.trimIndent(),
                if (assistantId != null) arrayOf(keyword, assistantId) else arrayOf(keyword),
            )
        AppLog.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    ),
                )
            }
        }
        results
    }
}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
