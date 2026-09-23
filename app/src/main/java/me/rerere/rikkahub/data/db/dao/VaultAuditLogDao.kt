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

    @Query("DELETE FROM vault_audit_log")
    suspend fun clearAll()
}
