package me.rerere.rikkahub.data.vault

import me.rerere.rikkahub.data.datastore.ProviderCredentialCipher
import me.rerere.rikkahub.data.db.dao.VaultCredentialDao
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity

/**
 * 密钥库凭证仓库（Credential Vault Repository）。
 *
 * 职责：
 * - 增删改查凭证条目（value 以 AES-GCM 密文存 Room，复用 ProviderCredentialCipher）
 * - 导入 load-creds.sh（解析 → 逐条 upsert）
 * - 脱敏展示（明文仅内存解密，展示前 mask）
 */
class CredentialVaultRepository(
    private val dao: VaultCredentialDao,
) {

    suspend fun getAll(): List<VaultCredentialEntity> = dao.getAll()

    suspend fun getByName(name: String): VaultCredentialEntity? = dao.getByName(name)

    /** 解密单条明文（展示/使用用；调用方负责用完即弃） */
    fun decryptValue(entry: VaultCredentialEntity): String? =
        ProviderCredentialCipher.decrypt(entry.valueEncrypted)

    /** 保存（新增/更新）：加密后落库。value 传空且已有同名条目时保留原值（编辑留空=不改）。 */
    suspend fun save(
        name: String,
        value: String,
        description: String,
        group: String,
    ) {
        val now = System.currentTimeMillis()
        val existing = dao.getByName(name)
        if (existing != null) {
            val effectiveValue = value.ifBlank { decryptValue(existing) ?: "" }
            val encrypted = if (value.isBlank()) existing.valueEncrypted else ProviderCredentialCipher.encrypt(effectiveValue)
            dao.update(
                existing.copy(
                    description = description,
                    group = group,
                    valueEncrypted = encrypted,
                    valueLength = effectiveValue.length,
                    updatedAt = now,
                )
            )
        } else {
            val encrypted = ProviderCredentialCipher.encrypt(value)
            dao.upsert(
                VaultCredentialEntity(
                    name = name,
                    description = description,
                    group = group,
                    valueEncrypted = encrypted,
                    valueLength = value.length,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
    }

    /** 批量导入（解析结果 → 逐条 upsert，返回导入条数） */
    suspend fun importEntries(entries: List<CredentialImporter.ParsedEntry>): Int {
        entries.forEach { e ->
            val encrypted = ProviderCredentialCipher.encrypt(e.value)
            val now = System.currentTimeMillis()
            val existing = dao.getByName(e.name)
            if (existing != null) {
                dao.update(
                    existing.copy(
                        description = e.description.ifEmpty { existing.description },
                        group = e.group.ifEmpty { existing.group },
                        valueEncrypted = encrypted,
                        valueLength = e.value.length,
                        updatedAt = now,
                    )
                )
            } else {
                dao.upsert(
                    VaultCredentialEntity(
                        name = e.name,
                        description = e.description,
                        group = e.group,
                        valueEncrypted = encrypted,
                        valueLength = e.value.length,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }
        }
        return entries.size
    }

    suspend fun delete(entry: VaultCredentialEntity) = dao.delete(entry)

    suspend fun clearAll() = dao.clearAll()

    suspend fun count(): Int = dao.count()

    companion object {
        /** 脱敏展示：前3后3+***；长度 ≤6 全掩 */
        fun mask(value: String): String {
            if (value.isEmpty()) return "(空)"
            if (value.length <= 6) return "*".repeat(value.length)
            return value.take(3) + "***" + value.takeLast(3)
        }
    }
}
