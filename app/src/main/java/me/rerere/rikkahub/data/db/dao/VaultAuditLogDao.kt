package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.VaultAuditDefaults
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity

@Dao
interface VaultAuditLogDao {
    @Query("SELECT * FROM vault_audit_log ORDER BY tsMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<VaultAuditLogEntity>

    /**
     * 只读查询（供 `diagnostics kind=audit`）：按凭证/动作/时间窗口过滤，倒序取前 limit 条。
     * 传空字符串 = 不过滤（避免动态 SQL）。
     */
    @Query(
        """
        SELECT * FROM vault_audit_log
        WHERE (:credential = '' OR credentialName = :credential)
          AND (:action = '' OR action = :action)
          AND tsMs >= :sinceMs
        ORDER BY tsMs DESC LIMIT :limit
        """
    )
    suspend fun queryAudit(credential: String, action: String, sinceMs: Long, limit: Int): List<VaultAuditLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: VaultAuditLogEntity)

    /** 删除早于 cutoff 的记录（时间维度清理）；关键动作（[VaultAuditDefaults.PROTECTED_ACTIONS]）豁免。 */
    @Query("DELETE FROM vault_audit_log WHERE tsMs < :cutoffMs AND action NOT IN (:protectedActions)")
    suspend fun deleteOlderThan(cutoffMs: Long, protectedActions: List<String>)

    /** 只保留最新 keep 条，删除其余更旧的；关键动作豁免（长留存）。 */
    @Query("DELETE FROM vault_audit_log WHERE id NOT IN (SELECT id FROM vault_audit_log ORDER BY tsMs DESC LIMIT :keep) AND action NOT IN (:protectedActions)")
    suspend fun trimTo(keep: Int, protectedActions: List<String>)

    @Query("SELECT COUNT(*) FROM vault_audit_log")
    suspend fun count(): Int

    /**
     * 聚合目标：同凭证/调用方/动作/会话，且“最近一次发生”在 `sinceMs` 之后的那条。
     *
     * 会话列的可空比较用 IFNULL 拉平（后台任务无会话时也该与自身聚合）。
     */
    @Query(
        """
        SELECT * FROM vault_audit_log
        WHERE credentialName = :credentialName AND caller = :caller AND action = :action
          AND IFNULL(conversationId, '') = IFNULL(:conversationId, '')
          AND IFNULL(lastTsMs, tsMs) >= :sinceMs
        ORDER BY id DESC LIMIT 1
        """
    )
    suspend fun findRollupTarget(
        credentialName: String,
        caller: String,
        action: String,
        conversationId: String?,
        sinceMs: Long,
    ): VaultAuditLogEntity?

    /** 聚合行计数 +1，并把“末次发生”推到 nowMs（首次 tsMs 不动）。 */
    @Query("UPDATE vault_audit_log SET count = count + 1, lastTsMs = :nowMs WHERE id = :id")
    suspend fun bumpCount(id: Long, nowMs: Long)

    @Query("DELETE FROM vault_audit_log")
    suspend fun clearAll()
}
