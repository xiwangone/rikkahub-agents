package me.rerere.rikkahub.data.vault

import me.rerere.rikkahub.data.datastore.ProviderCredentialCipher
import me.rerere.rikkahub.data.db.dao.VaultAuditLogDao
import me.rerere.rikkahub.data.db.dao.VaultCredentialDao
import me.rerere.rikkahub.data.db.entity.VaultAuditDefaults
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity

/**
 * 密钥库凭证仓库（Credential Vault Repository）。
 *
 * 职责：
 * - 增删改查凭证条目（value 以 AES-GCM 密文存 Room，复用 ProviderCredentialCipher）
 * - 导入 load-creds.sh（解析 → 逐条 upsert）
 * - 脱敏展示（明文仅内存解密，展示前 mask）
 * - 密钥使用审计：记录每次查看/导出/备份，双上限清理（500 条 / 30 天）
 */
class CredentialVaultRepository(
    private val dao: VaultCredentialDao,
    private val auditDao: VaultAuditLogDao,
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
        publicKey: String = "",
        type: String = "",
    ) {
        // 命名规范校验（大写蛇形）。存量脏名改到此名时同样拦截；导入路径见 importEntries
        require(validateCredentialName(name)) {
            "凭证名不合规范：$name（须大写蛇形如 GITHUB_TOKEN；禁止小写/连字符/空格）"
        }
        // 只含不可见字符的值等于"写入一个空密钥"：明确拒绝，而不是静默存空
        require(value.isBlank() || CredentialValueSanitizer.sanitize(value).isNotEmpty()) {
            "凭证值只含不可见字符，已拒绝保存：$name"
        }
        val existing = dao.getByName(name)
        if (existing != null && value.isBlank()) {
            // 编辑留空 = 保留原值，仅更新描述/分组
            val now = System.currentTimeMillis()
            dao.update(
                existing.copy(
                    description = description,
                    grp = group,
                    publicKey = publicKey,
                    updatedAt = now,
                )
            )
        } else {
            upsertEntry(name, value, description, group, publicKey, type = type)
        }
        logAccess(name, "repository", if (existing != null) "save_update" else "save_create")
        // provider 可能以 `$$名字` 引用本条目：值变化要反映到解析缓存，否则仍在用旧值
        runCatching { VaultProviderKeyRefs.refresh(this) }
    }

    /** 批量导入（解析结果 → 逐条 upsert，返回导入条数）。 */
    suspend fun importEntries(entries: List<CredentialImporter.ParsedEntry>): Int {
        var imported = 0
        entries.forEach { e ->
            val existing = dao.getByName(e.name)
            // 命名规范：新名称必须合规才导入（存量脏名已存在则放行，不阻断旧数据回导）
            if (existing == null && !validateCredentialName(e.name)) return@forEach
            // 只含不可见字符的值视为无效：跳过该条（与命名不合规同策略，不中断整批导入）
            if (e.value.isNotBlank() && CredentialValueSanitizer.sanitize(e.value).isEmpty()) return@forEach
            // 导入留空 = 保留原值（与 save 语义一致：避免重导清空已存密钥）
            val keepValue = existing != null && e.value.isBlank()
            // 导入公钥留空 = 保留原公钥（防重导清空已存公钥）
            val keepPub = existing != null && e.publicKey.isBlank()
            if (keepValue) {
                // 只更新元数据
                dao.update(
                    existing!!.copy(
                        description = e.description.ifEmpty { existing.description },
                        grp = if (e.group.isBlank()) existing.grp else e.group,
                        publicKey = if (keepPub) existing.publicKey else e.publicKey,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            } else {
                upsertEntry(e.name, e.value, e.description, e.group, e.publicKey, keepPub = keepPub, type = e.type)
            }
            imported++
        }
        // 批量导入后同样刷新（provider 引用可能指向新导入的条目）
        runCatching { VaultProviderKeyRefs.refresh(this) }
        return imported
    }

    /** 新增/覆盖写入：加密后 upsert（同名单覆盖，描述/分组用传入值）。 */
    private suspend fun upsertEntry(
        name: String,
        value: String,
        description: String,
        group: String,
        publicKey: String = "",
        keepPub: Boolean = false,
        type: String = "",
    ) {
        val now = System.currentTimeMillis()
        // 落库前统一清洗（剔除不可见控制符）；长度字段以清洗后的值为准，避免展示与实际不一致
        val cleanValue = CredentialValueSanitizer.sanitize(value)
        val encrypted = ProviderCredentialCipher.encrypt(cleanValue)
        val existing = dao.getByName(name)
        if (existing != null) {
            val finalPub = if (keepPub) existing.publicKey else publicKey.ifEmpty { existing.publicKey }
            dao.update(
                existing.copy(
                    description = description.ifEmpty { existing.description },
                    grp = group.ifEmpty { existing.grp },
                    publicKey = finalPub,
                    valueEncrypted = encrypted,
                    valueLength = cleanValue.length,
                    type = resolveType(type, existing.type, name, cleanValue, finalPub),
                    updatedAt = now,
                )
            )
        } else {
            dao.upsert(
                VaultCredentialEntity(
                    name = name,
                    description = description,
                    grp = group.ifEmpty { "Other" },
                    publicKey = publicKey,
                    valueEncrypted = encrypted,
                    valueLength = cleanValue.length,
                    type = resolveType(type, "", name, cleanValue, publicKey),
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
    }

    /**
     * 类型解析：显式传入优先 → 否则沿用库内已有值 → 再否则按名称与结构推断。
     * 历史数据 type 为空，首次保存/导入时自动补全，无需用户手工分类。
     */
    private fun resolveType(
        requested: String,
        existing: String,
        name: String,
        value: String,
        publicKey: String,
    ): String = requested.ifBlank { existing.ifBlank { CredentialType.infer(name, value, publicKey) } }

    /**
     * 回填缺失的类型（历史数据一次性补全，**幂等**）。
     *
     * 为什么需要：数据库迁移只新增列并默认空，不会给存量条目分类；而推断只发生在
     * 写入路径，因此存量条目的类型会长期为空、使用方读不到。这里统一按名称与结构补全。
     *
     * 有意**不改 updatedAt**：避免污染"最后修改"语义，也避免多余触发掩码规则刷新。
     */
    suspend fun backfillMissingTypes(): Int {
        var filled = 0
        dao.getAll().forEach { e ->
            if (e.type.isNotBlank()) return@forEach
            val value = decryptValue(e) ?: return@forEach
            dao.update(e.copy(type = CredentialType.infer(e.name, value, e.publicKey)))
            filled++
        }
        return filled
    }

    suspend fun delete(entry: VaultCredentialEntity) {
        logAccess(entry.name, "repository", "delete")
        dao.delete(entry)
        // provider 可能以 `$$名字` 引用本条目：值变化要反映到解析缓存，否则仍在用旧值
        runCatching { VaultProviderKeyRefs.refresh(this) }
    }

    suspend fun clearAll() = dao.clearAll()

    suspend fun count(): Int = dao.count()

    // ── 密钥使用审计 ──────────────────────────────────────────────

    /**
     * 记录一次密钥调用（查看/导出/备份）。
     * 写入后执行双上限清理：超 30 天先删，仍超 500 条则 trim。
     */
    suspend fun logAccess(credentialName: String, caller: String, action: String) {
        auditDao.insert(
            VaultAuditLogEntity(
                credentialName = credentialName,
                caller = caller,
                action = action,
            )
        )
        val cutoff = System.currentTimeMillis() - VaultAuditDefaults.RETENTION_DAYS * 24 * 60 * 60 * 1000
        auditDao.deleteOlderThan(cutoff)
        if (auditDao.count() > VaultAuditDefaults.CAP) {
            auditDao.trimTo(VaultAuditDefaults.CAP)
        }
    }

    /** 最近审计记录（默认 100 条）。 */
    suspend fun recentAudit(limit: Int = 100): List<VaultAuditLogEntity> =
        auditDao.getRecent(limit)

    suspend fun clearAudit() = auditDao.clearAll()

    companion object {
        /** 脱敏展示：前3后3+***；长度 ≤6 全掩 */
        fun mask(value: String): String {
            if (value.isEmpty()) return "(空)"
            if (value.length <= 6) return "*".repeat(value.length)
            return value.take(3) + "***" + value.takeLast(3)
        }

        /**
         * 凭证命名规范（2026-09-03 约定）：大写蛇形。
         * 规则：^[A-Z][A-Z0-9_]*$ —— 首字符大写字母，仅大写字母/数字/下划线。
         * 语义后缀（建议非强制）：_TOKEN / _API_KEY / _KEY / _PUB / _PWD / _PASS / _PATH / _ACCOUNT。
         */
        private val NAME_REGEX = Regex("^[A-Z][A-Z0-9_]*$")

        /** 校验凭证名是否合规。空名/小写/连字符/空格/中文均不合规。 */
        fun validateCredentialName(name: String): Boolean =
            name.isNotBlank() && NAME_REGEX.matches(name)
    }
}
