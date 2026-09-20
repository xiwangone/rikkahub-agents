package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Every conversation list query is "WHERE assistant_id = ? ORDER BY is_pinned DESC, update_at
// DESC", and the unfiltered list is the same minus the WHERE, so the two composites below cover
// both without a sort step. SQLite walks an ASC index backwards for an all-DESC ORDER BY, so no
// per-column direction is needed.
@Entity(
    indices = [
        Index(value = ["assistant_id", "is_pinned", "update_at"]),
        Index(value = ["is_pinned", "update_at"]),
    ]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id", defaultValue = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    val assistantId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("nodes")
    val nodes: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
    @ColumnInfo("suggestions", defaultValue = "[]")
    val chatSuggestions: String,
    @ColumnInfo("is_pinned", defaultValue = "0")
    val isPinned: Boolean,
    @ColumnInfo("custom_system_prompt", defaultValue = "")
    val customSystemPrompt: String = "",
    @ColumnInfo("mode_injection_ids", defaultValue = "[]")
    val modeInjectionIds: String = "[]",
    @ColumnInfo("lorebook_ids", defaultValue = "[]")
    val lorebookIds: String = "[]",
    @ColumnInfo("workspace_cwd", defaultValue = "")
    val workspaceCwd: String = "",
    @ColumnInfo("folder_id", defaultValue = "")
    val folderId: String = "",
    @ColumnInfo("chat_model_id", defaultValue = "")
    val chatModelId: String = "",
    // 会话级「工具/工作区/步数」覆盖（子代理按此收敛，见 SubAgentEngine）。
    // 【为何必须落库】子代理会话创建后走 insertConversation → initializeConversation(id) 按 id
    // 重新加载；此前这三项只存在于内存对象里，重载即丢失 → 回退成「不限制」（实测：子代理
    // 声明只读集却拿到全量 139 个工具、写工具可用）。空串/0 = 未设置。
    @ColumnInfo("tool_scope_override", defaultValue = "")
    val toolScopeOverride: String = "",
    @ColumnInfo("workspace_id_override", defaultValue = "")
    val workspaceIdOverride: String = "",
    @ColumnInfo("max_tool_steps_override", defaultValue = "0")
    val maxToolStepsOverride: Int = 0,
    @ColumnInfo("is_sub_agent_run", defaultValue = "0")
    val isSubAgentRun: Boolean = false,
)
