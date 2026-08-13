package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    /** 记忆分层（2026-08-13）：core = 常驻；conditional = 按需检索。默认 core 保持既有行为。 */
    @ColumnInfo("tier", defaultValue = "core")
    val tier: String = "core",
)
