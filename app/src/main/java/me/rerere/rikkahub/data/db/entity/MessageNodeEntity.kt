package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_node",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversation_id"),
        // 深挖-B1：同会话同 nodeIndex 至多一行。原先只有 conversation_id 索引，
        // 不同 id 的节点可以带着相同 nodeIndex 重复入库，UI 按 nodeIndex 排序后
        // 同一位置出现两条消息；DAO 的 REPLACE 插入在唯一索引建成后按节点去重。
        Index(value = ["conversation_id", "node_index"], unique = true)
    ]
)
data class MessageNodeEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("node_index")
    val nodeIndex: Int,
    @ColumnInfo("messages")
    val messages: String,  // JSON serialized List<UIMessage>
    @ColumnInfo("select_index")
    val selectIndex: Int
)
