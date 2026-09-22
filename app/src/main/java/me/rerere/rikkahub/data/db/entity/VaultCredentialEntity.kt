package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 密钥库凭证条目（Credential Vault）。
 *
 * MVP 阶段：value 以 AES-GCM 密文存储（复用 ProviderCredentialCipher 的
 * AndroidKeyStore 基建，Base64(IV + ciphertext) 格式），明文仅在 UI 展示时
 * 内存解密，不落盘、不进备份。
 *
 * 分组约定：Git / AI / ECS / MCP / Notification / Other（导入时按注释自动归类）。
 */
@Entity(tableName = "vault_credentials")
data class VaultCredentialEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 变量名，如 DEEPSEEK_API_KEY（唯一） */
    val name: String,
    /** 简单描述，如「DeepSeek 官方 API Key」 */
    val description: String = "",
    /** SSH 公钥（可选，明文存储；仅 SSH 私钥条目使用，非密钥类条目留空）。公钥本身公开可明文。 */
    @ColumnInfo(defaultValue = "")
    val publicKey: String = "",
    /** 分组：Git / AI / ECS / MCP / Notification / Other */
    val grp: String = "Other",
    /**
     * 凭据类型（与通用凭据交换格式的类型名对齐：ssh-key / api-key / basic-auth /
     * totp / custom-fields；空 = 未分类）。写入时由 `CredentialType.infer` 尽量补全，
     * 使用方据此选对方式（SSH 握手 / 请求头注入 / 表单填充），而不是靠名字猜。
     */
    @ColumnInfo(defaultValue = "")
    val type: String = "",
    /**
     * 非敏感元数据（**明文** JSON）：endpoint / path / header 名 / 账号名 / 算法参数等。
     *
     * 两条硬约束（2026-09-22 定）：
     * - **只放非敏感字段**：秘密一律只进 [valueEncrypted]；
     * - 键必须在 [CredentialMeta.ALLOWED_KEYS] 白名单内 —— 写入侧由此强制，不靠自觉。
     *
     * 空串 = 无元数据（历史数据默认）。
     */
    @ColumnInfo(defaultValue = "")
    val metaJson: String = "",
    /** AES-GCM 密文：Base64(IV(12B) + ciphertext) */
    val valueEncrypted: String,
    /** 明文长度（脱敏展示用，不存明文） */
    val valueLength: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
