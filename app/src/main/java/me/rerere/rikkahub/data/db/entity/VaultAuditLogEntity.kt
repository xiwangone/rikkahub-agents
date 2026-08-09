package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 密钥使用审计日志（Vault Audit Log）。
 *
 * 记录每次「谁在何时调用了哪把密钥」——查看明文/导出/备份都会留痕，
 * 便于回溯异常调用（如某个助手偷偷读 key）。
 *
 * 保留策略（双上限，先到先清）：
 * - 条数上限 [VaultAuditDefaults.CAP]（FIFO 淘汰最旧）
 * - 时间上限 [VaultAuditDefaults.RETENTION_DAYS] 天（写入时清理过期行）
 */
@Entity(
    tableName = "vault_audit_log",
    indices = [
        Index(name = "idx_audit_ts", value = ["ts_ms"]),
        Index(name = "idx_audit_name", value = ["credential_name"]),
    ],
)
data class VaultAuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 凭证名，如 DEEPSEEK_API_KEY */
    val credentialName: String,
    /** 调用方：助手名 / "manual"（手动查看）/ "export" / "backup" */
    val caller: String,
    /** 动作：view / export / backup */
    val action: String,
    /** 时间戳（毫秒） */
    val tsMs: Long = System.currentTimeMillis(),
)

object VaultAuditDefaults {
    /** 保留条数上限 */
    const val CAP = 500
    /** 保留天数 */
    const val RETENTION_DAYS = 30L
}
