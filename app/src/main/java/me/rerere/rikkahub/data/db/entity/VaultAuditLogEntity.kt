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
        Index(name = "idx_audit_ts", value = ["tsMs"]),
        Index(name = "idx_audit_name", value = ["credentialName"]),
    ],
)
data class VaultAuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 凭证名，如 DEEPSEEK_API_KEY */
    val credentialName: String,
    /** 调用方标识：工具层（ai-tool）/ 用途层（pgp-sign、ssh…）/ manual / export / backup */
    val caller: String,
    /** 动作：CredentialPurpose.action（local_use / http_exec / env_inject…）或 view / save_create / audit_cleared 等 */
    val action: String,
    /** 归属会话 id（无会话的后台任务为空；只记 id 不记内容） */
    val conversationId: String? = null,
    /** 归属模型配置 id */
    val modelId: String? = null,
    /** 归属助手 id */
    val assistantId: String? = null,
    /** 来源标记：ai-tool / provider / backup / web-bridge 等 */
    val source: String? = null,
    /** 时间戳（毫秒） */
    val tsMs: Long = System.currentTimeMillis(),
)

object VaultAuditDefaults {
    /** 保留条数上限 */
    const val CAP = 500
    /** 保留天数 */
    const val RETENTION_DAYS = 30L
    /** 关键动作（导出 / 写回 / 清空回执）：不受滚动上限限制，长期留存 */
    val PROTECTED_ACTIONS: List<String> = listOf("export", "export_env", "fill_back", "audit_cleared")
}
