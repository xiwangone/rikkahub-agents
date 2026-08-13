package me.rerere.rikkahub.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.agentrun.AgentRun
import me.rerere.rikkahub.data.agentrun.AgentRunDao
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.ScheduledJobDao
import me.rerere.rikkahub.data.db.dao.ScheduledJobRunDao
import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.db.dao.TelegramChatDao
import me.rerere.rikkahub.data.db.dao.VaultAuditLogDao
import me.rerere.rikkahub.data.db.dao.CompressedArchiveDao
import me.rerere.rikkahub.data.db.dao.VaultCredentialDao
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.db.entity.TelegramChatEntity
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.db.entity.CompressedArchiveEntity
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.migrations.Migration_16_17
import me.rerere.rikkahub.data.db.migrations.Migration_20_21
import me.rerere.rikkahub.data.db.migrations.Migration_21_22
import me.rerere.rikkahub.data.db.migrations.Migration_22_23
import me.rerere.rikkahub.data.db.migrations.Migration_8_9
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.workflow.db.WorkflowDao
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.db.WorkflowRunDao
import me.rerere.rikkahub.workflow.db.WorkflowRunEntity

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        ScheduledJobEntity::class,
        ScheduledJobRunEntity::class,
        SshHostEntity::class,
        TelegramChatEntity::class,
        WorkflowEntity::class,
        WorkflowRunEntity::class,
        AgentRun::class,
        WorkspaceEntity::class,
        FolderEntity::class,
        VaultCredentialEntity::class,
        VaultAuditLogEntity::class,
        CompressedArchiveEntity::class,
    ],
    version = 32,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21, spec = Migration_20_21::class),
        AutoMigration(from = 21, to = 22, spec = Migration_21_22::class),
        AutoMigration(from = 22, to = 23, spec = Migration_22_23::class),
        // v25: upstream 2.2.6 added conversation-level custom_system_prompt / mode_injection_ids
        // / lorebook_ids columns (all carry defaultValue, so a plain auto-migration suffices).
        AutoMigration(from = 24, to = 25),
        // v26: the 2.3.1 merge brings upstream's workspaces table (WorkspaceEntity). Existing
        // fork users never had it, so Room auto-creates the table on this step.
        AutoMigration(from = 25, to = 26),
        // v27: upstream 2.4.x added conversation folders (FolderEntity -> conversation_folder
        // table) plus a folder_id column on ConversationEntity (defaultValue ""). Both are pure
        // additions; upstream numbered it as their v24, folded into the fork's version space here.
        AutoMigration(from = 26, to = 27),
        // v28+v29+v30: 密钥库凭证表（VaultCredentialEntity -> vault_credentials）、
        // 密钥使用审计表（VaultAuditLogEntity -> vault_audit_log）与压缩历史归档
        // （CompressedArchiveEntity -> compressed_archives）。v28 已随 Release 2.45.5
        // 发布过（Vault MVP），真实设备存在 v28 数据库；v29 新增 vault_audit_log
        // （28→29 由手写 Migration_28_29 处理）；v30 新增 compressed_archives
        // （29→30 由手写 Migration_29_30 处理）。此处 27→32 一步自动迁移服务于仍
        // 停留在 v27 的用户（纯新增表/加列，Room 对比 27/32 schema 推断即可），三条路径
        // 并存，Room 按设备当前版本自动选择。
        // 注意：27→31 改为 27→32——version=32 后 31 不再是最终版本，AutoMigration 终点
        // 非最终版需要 31.json（从未导出），故一步到 32（tier 列纯新增可自动推断）。
        AutoMigration(from = 27, to = 32),
        // v32: 记忆分层——memoryentity 加 tier 列（手写 Migration_31_32 覆盖 v31→v32 路径）
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun scheduledJobDao(): ScheduledJobDao

    abstract fun scheduledJobRunDao(): ScheduledJobRunDao

    abstract fun sshHostDao(): SshHostDao

    abstract fun telegramChatDao(): TelegramChatDao

    abstract fun workflowDao(): WorkflowDao

    abstract fun workflowRunDao(): WorkflowRunDao

    abstract fun agentRunDao(): AgentRunDao

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO

    abstract fun vaultCredentialDao(): VaultCredentialDao

    abstract fun vaultAuditLogDao(): VaultAuditLogDao

    abstract fun compressedArchiveDao(): CompressedArchiveDao
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
