package me.rerere.rikkahub.data.db.entity

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
    /** 分组：Git / AI / ECS / MCP / Notification / Other */
    val group: String = "Other",
    /** AES-GCM 密文：Base64(IV(12B) + ciphertext) */
    val valueEncrypted: String,
    /** 明文长度（脱敏展示用，不存明文） */
    val valueLength: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
