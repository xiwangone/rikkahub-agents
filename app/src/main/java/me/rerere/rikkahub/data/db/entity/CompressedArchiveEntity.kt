package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * T10: 压缩历史归档——压缩前的原始消息 JSON 存档（可追溯，UI 回看后续可选）。
 *
 * 每轮压缩写一条记录；archiveJson 为 messagesToCompress 序列化结果。
 * 索引 conversationId + compressedAtMs 便于按会话查询。
 */
@Entity(
    tableName = "compressed_archives",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["conversationId", "compressedAtMs"]),
    ],
)
data class CompressedArchiveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val compressedAtMs: Long,
    val archiveJson: String,
)
