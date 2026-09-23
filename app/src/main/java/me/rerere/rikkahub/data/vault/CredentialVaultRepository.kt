package me.rerere.rikkahub.data.vault

import me.rerere.rikkahub.data.datastore.ProviderCredentialCipher
import java.security.MessageDigest
import me.rerere.rikkahub.data.db.dao.VaultAuditLogDao
import me.rerere.rikkahub.data.db.dao.VaultCredentialDao
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.entity.VaultAuditDefaults
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity

/**
 * 密钥库凭证仓库（Credential Vault Repository）。
 *
 * 职责：
 * - 增删改查凭证条目（value 以 AES-GCM 密文存 Room，复用 ProviderCredentialCipher）
 * - 导入凭证文件（.vault / CSV / Bitwarden JSON / load-creds.sh，解析 → 逐条 upsert）
 * - 脱敏展示（明文仅内存解密，展示前 mask）
 * - 密钥使用审计：记录每次查看/导出/备份，双上限清理（500 条 / 30 天）
 */
/**
 * 快速入库结果（供"引用处直接粘贴明文"使用）。
 *
 * @param name 最终使用的凭证名（可能复用已有条目）
 * @param reusedExisting 是否**复用**了库内已有的同值条目（避免重复入库）
 * @param type 最终类型（显式传入或自动推断）
 */
data class QuickImportResult(
    val name: String,
    val reusedExisting: Boolean,
    val type: String,
)

/**
 * 批量导入结果。
 *
 * @param overwrittenDifferentValue 同名但**值不同**、因而被覆盖的条目名
 *   —— 导入常用于恢复/合并，静默覆盖会悄悄丢掉正在用的密钥，所以要回报出来
 * @param parsed 解析出的条目总数（= imported + skipped），供 UI 展示「已导入 N 条（共解析 M 条）」
 */
data class ImportResult(
    val imported: Int,
    val overwrittenDifferentValue: List<String> = emptyList(),
    val skipped: Int = 0,
    val parsed: Int = 0,
)

/**
 * 文件导入结果（区分成功与两类**可预期**的失败，便于调用方给出本地化提示）。
 * 加密包口令错/包被篡改属于异常，不经本类型返回。
 */
sealed interface VaultImportOutcome {
    /** 成功：已按导入策略逐条 upsert。 */
    data class Success(val result: ImportResult) : VaultImportOutcome

    /** 无法识别格式（既非 .vault / CSV / Bitwarden JSON / load-creds.sh）。 */
    data object Unrecognized : VaultImportOutcome

    /** 是 .vault 加密包但未提供口令。 */
    data object PasswordRequired : VaultImportOutcome
}

/** 清空审计的回执占位名（不是真实凭证）。 */
private const val AUDIT_CLEAR_RECEIPT = "—"

class CredentialVaultRepository(
    private val dao: VaultCredentialDao,
    private val auditDao: VaultAuditLogDao,
    private val vaultPreferences: VaultPreferences,
) {

    /** 审计写入串行化：聚合需「查目标 → 计次/插入」原子，单进程内用互斥锁就够（不动 Room 事务）。 */
    private val auditWriteMutex = Mutex()

    suspend fun getAll(): List<VaultCredentialEntity> = dao.getAll()

    /** 找出库内与给定值**完全相同**的条目名（比对指纹，不比明文）；已清洗后的值传入。 */
    suspend fun findSameValueNames(value: String): List<String> {
        val target = fingerprint(CredentialValueSanitizer.sanitize(value))
        return dao.getAll().mapNotNull { e ->
            val v = decryptValue(e) ?: return@mapNotNull null
            if (fingerprint(v) == target) e.name else null
        }
    }

    /** 全库重复分组：指纹相同的条目归为一组（只返回成员 >1 的组）。 */
    suspend fun findDuplicateGroups(): List<List<String>> {
        val byHash = LinkedHashMap<String, MutableList<String>>()
        dao.getAll().forEach { e ->
            val v = decryptValue(e) ?: return@forEach
            byHash.getOrPut(fingerprint(v)) { mutableListOf() }.add(e.name)
        }
        return byHash.values.filter { it.size > 1 }.map { it.toList() }
    }

    /**
     * 快速入库（供"引用处直接粘贴明文"使用）。
     *
     * 流程：清洗 → **查重**（同值已存在则直接复用，不重复建条目）→ 保存（类型自动推断）
     * → 返回最终凭证名。调用方据此把该处改成引用，而不是留下明文。
     */
    /** 公钥并入结果：宿主私钥条目名 + 是否靠指纹命中。 */
    data class PublicKeyMergeResult(val hostName: String, val byFingerprint: Boolean)

    /** 一条「值本身是公钥」的条目及其可并入的宿主（存量清理入口用，只读）。 */
    data class PublicKeyOnlyEntry(val entry: VaultCredentialEntity, val hostName: String?)

    /**
     * 把一段**公钥行**并入对应私钥条目（公钥不单列）。
     *
     * 匹配优先级：
     * 1. **指纹**：库里已有 `publicKey` 的条目逐个算指纹比对（精确，不怕改过名）；
     * 2. **名称启发式**：`X.PUB` / `X_PUB` / `X_PUBLIC` → `X`（够用但可能错配，故排在指纹之后）。
     *
     * 返回 null = 没找到宿主，调用方自行决定（提示或按普通条目新建）。
     */
    suspend fun mergePublicKeyLine(publicKeyLine: String, preferredName: String = ""): PublicKeyMergeResult? {
        val value = publicKeyLine.trim()
        if (value.isEmpty()) return null
        val now = System.currentTimeMillis()

        // ① 指纹匹配（最可靠）
        val target = SshKeyGenerator.fingerprint(value)
        if (target != null) {
            val host = dao.getAll().firstOrNull { e ->
                e.publicKey.isNotBlank() && SshKeyGenerator.fingerprint(e.publicKey) == target
            }
            if (host != null) {
                dao.update(host.copy(publicKey = value, updatedAt = now))
                logAccess(host.name, "manual", "public_key_merged")
                return PublicKeyMergeResult(host.name, byFingerprint = true)
            }
        }

        // ② 名称启发式
        for (candidate in SshKeyFormat.privateKeyNameCandidates(preferredName)) {
            val host = dao.getByName(candidate) ?: continue
            dao.update(host.copy(publicKey = value, updatedAt = now))
            logAccess(host.name, "manual", "public_key_merged")
            return PublicKeyMergeResult(host.name, byFingerprint = false)
        }
        return null
    }

    /**
     * 扫描「值本身是公钥」的条目（存量清理用，只读）。
     * 每条附上若能匹配到的宿主名（指纹优先，其次名称），供 UI 列候选。
     */
    suspend fun findPublicKeyOnlyEntries(): List<PublicKeyOnlyEntry> {
        val all = dao.getAll()
        return all.mapNotNull { e ->
            val v = decryptValue(e) ?: return@mapNotNull null
            if (!SshKeyFormat.isPublicKeyLine(v)) return@mapNotNull null
            val fp = SshKeyGenerator.fingerprint(v.trim())
            val host = all.firstOrNull { h ->
                h.name != e.name && h.publicKey.isNotBlank() &&
                    fp != null && SshKeyGenerator.fingerprint(h.publicKey) == fp
            }?.name ?: SshKeyFormat.privateKeyNameCandidates(e.name)
                .firstOrNull { cand -> all.any { it.name == cand } }
            PublicKeyOnlyEntry(e, host)
        }
    }

    suspend fun quickImport(
        rawValue: String,
        preferredName: String,
        description: String = "",
        group: String = "Other",
    ): QuickImportResult {
        val sanitized = CredentialValueSanitizer.sanitize(rawValue)
        require(sanitized.isNotBlank()) { "凭证值为空或只含不可见字符" }

        // 公钥不单列：粘贴的是公钥行 → 先并入对应私钥条目（指纹 → 名称），命中即复用、不新建
        if (SshKeyFormat.isPublicKeyLine(sanitized)) {
            mergePublicKeyLine(sanitized, preferredName)?.let { merged ->
                return QuickImportResult(
                    name = merged.hostName,
                    reusedExisting = true,
                    type = CredentialType.SSH_KEY,
                )
            }
        }

        // 查重：同值已在库里就直接复用，顺带避免用户重复粘贴造成多份副本
        val sameName = findSameValueNames(sanitized).firstOrNull()
        if (sameName != null) {
            val existing = dao.getByName(sameName)
            return QuickImportResult(
                name = sameName,
                reusedExisting = true,
                type = existing?.type.orEmpty(),
            )
        }

        val name = normalizeName(preferredName)
        save(name = name, value = sanitized, description = description, group = group)
        return QuickImportResult(
            name = name,
            reusedExisting = false,
            type = dao.getByName(name)?.type.orEmpty(),
        )
    }

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
        /** 非敏感元数据（明文 JSON，白名单见 CredentialMeta）；编辑时留空 = 保留原值。 */
        metaJson: String = "",
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
                    metaJson = metaJson.ifEmpty { existing.metaJson },
                    updatedAt = now,
                )
            )
        } else {
            upsertEntry(
                name = name,
                value = value,
                description = description,
                group = group,
                publicKey = publicKey,
                type = type,
                metaJson = metaJson,
            )
        }
        logAccess(name, "repository", if (existing != null) "save_update" else "save_create")
        // provider 可能以 `$$名字` 引用本条目：值变化要反映到解析缓存（增量，避免整库解密）
        runCatching { VaultProviderKeyRefs.updateOne(this, name) }
    }

    /** 批量导入（解析结果 → 逐条 upsert，返回导入条数）。 */
    suspend fun importEntries(entries: List<CredentialImporter.ParsedEntry>): ImportResult {
        var imported = 0
        var skipped = 0
        val overwritten = mutableListOf<String>()
        entries.forEach { e ->
            val existing = dao.getByName(e.name)
            // 命名规范：新名称必须合规才导入（存量脏名已存在则放行，不阻断旧数据回导）
            if (existing == null && !validateCredentialName(e.name)) { skipped++; return@forEach }
            // 只含不可见字符的值视为无效：跳过该条（与命名不合规同策略，不中断整批导入）
            if (e.value.isNotBlank() && CredentialValueSanitizer.sanitize(e.value).isEmpty()) { skipped++; return@forEach }
            // 导入留空 = 保留原值（与 save 语义一致：避免重导清空已存密钥）
            val keepValue = existing != null && e.value.isBlank()
            // 导入公钥留空 = 保留原公钥（防重导清空已存公钥）
            val keepPub = existing != null && e.publicKey.isBlank()
            // 同名且值不同 → 记为"被覆盖"（用指纹比对，不比较明文）
            if (!keepValue && existing != null && e.value.isNotBlank()) {
                val oldValue = decryptValue(existing)
                val newValue = CredentialValueSanitizer.sanitize(e.value)
                if (oldValue != null && fingerprint(oldValue) != fingerprint(newValue)) {
                    overwritten += e.name
                }
            }
            if (keepValue) {
                // 只更新元数据
                dao.update(
                    existing!!.copy(
                        description = e.description.ifEmpty { existing.description },
                        grp = if (e.group.isBlank()) existing.grp else e.group,
                        publicKey = if (keepPub) existing.publicKey else e.publicKey,
                        metaJson = e.metaJson.ifEmpty { existing.metaJson },
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            } else {
                upsertEntry(
                    name = e.name,
                    value = e.value,
                    description = e.description,
                    group = e.group,
                    publicKey = e.publicKey,
                    keepPub = keepPub,
                    type = e.type,
                    metaJson = e.metaJson,
                )
            }
            imported++
        }
        // 批量导入涉及多条：保留全量刷新（一次性，避免逐条解密带来的重复开销）
        runCatching { VaultProviderKeyRefs.refresh(this) }
        return ImportResult(
            imported = imported,
            overwrittenDifferentValue = overwritten,
            skipped = skipped,
            parsed = entries.size,
        )
    }

    /**
     * 从文件内容导入：按格式自动识别 → 解析 → 逐条 upsert。
     *
     * - 语义与 [importEntries] 完全一致（即与既有 load-creds.sh 导入同一策略：同名条目值留空保留原值/
     *   值不同则覆盖并回报、名称不合规或值非法则跳过）；
     * - 支持的四种格式与导出侧**一一对称**，识别规则见 [CredentialImporter.detectFormat]；
     * - [password] 仅 .vault 加密包需要；口令错/包被篡改时抛异常（由调用方提示）。
     */
    suspend fun importFromContent(
        content: String,
        fileName: String? = null,
        password: String = "",
    ): VaultImportOutcome {
        val format = CredentialImporter.detectFormat(fileName, content) ?: return VaultImportOutcome.Unrecognized
        if (format == CredentialImporter.Format.VAULT && password.isBlank()) return VaultImportOutcome.PasswordRequired
        val parsed = CredentialImporter.parseAsEntries(content, format, password)
        return VaultImportOutcome.Success(importEntries(parsed))
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
        metaJson: String = "",
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
                    // 导入留空 = 保留原元数据（与 publicKey 同款语义，防重导清空）
                    metaJson = metaJson.ifEmpty { existing.metaJson },
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
                    metaJson = metaJson,
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
        // 删除后同步移除缓存，避免继续解析到已删条目
        runCatching { VaultProviderKeyRefs.removeOne(entry.name) }
    }

    suspend fun clearAll() = dao.clearAll()

    suspend fun count(): Int = dao.count()

    // ── 密钥使用审计 ──────────────────────────────────────────────

    /**
     * 记录一次密钥调用（查看/导出/备份）。
     * 写入后执行双上限清理：超 30 天先删，仍超 500 条则 trim。
     */
    suspend fun logAccess(credentialName: String, caller: String, action: String) {
        // 归属维度：生成链路经协程上下文透传（AuditContext），零调用方变更；后台任务无会话则为空
        val ctx = coroutineContext[AuditContext]
        val now = System.currentTimeMillis()
        // 聚合：**机械取用**（provider 取 key 等，见 [VaultAuditDefaults.ROLLUP_ACTIONS]）在窗口内重复
        // 只计次、不插新行 —— 否则每次请求一条，会把审计表刷满并挤掉事件性记录（导出/注入/删除…）。
        // 事件性动作不聚合：必须能回答“哪一次、什么时候”。
        val rollupMinutes =
            if ("$caller/$action" in VaultAuditDefaults.ROLLUP_ACTIONS) vaultPreferences.auditRollupMinutes.first() else 0
        auditWriteMutex.withLock {
            val target = if (rollupMinutes > 0) {
                auditDao.findRollupTarget(
                    credentialName = credentialName,
                    caller = caller,
                    action = action,
                    conversationId = ctx?.conversationId,
                    sinceMs = now - rollupMinutes * 60_000L,
                )
            } else {
                null
            }
            if (target != null) {
                auditDao.bumpCount(target.id, now)
            } else {
                auditDao.insert(
                    VaultAuditLogEntity(
                        credentialName = credentialName,
                        caller = caller,
                        action = action,
                        conversationId = ctx?.conversationId,
                        modelId = ctx?.modelId,
                        assistantId = ctx?.assistantId,
                        source = ctx?.source,
                        tsMs = now,
                    )
                )
            }
        }
        val retentionDays = vaultPreferences.auditRetentionDays.first()
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
        auditDao.deleteOlderThan(cutoff, VaultAuditDefaults.PROTECTED_ACTIONS)
        val cap = vaultPreferences.auditCap.first()
        if (auditDao.count() > cap) {
            auditDao.trimTo(cap, VaultAuditDefaults.PROTECTED_ACTIONS)
        }
    }

    /** 最近审计记录（默认 100 条）。 */
    suspend fun recentAudit(limit: Int = 100): List<VaultAuditLogEntity> =
        auditDao.getRecent(limit)

    /**
     * 只读查询（`diagnostics kind=audit` 用）：按凭证/动作/时间窗口过滤。
     *
     * 只回**元数据行**（凭证名/caller/action/次数/时间/归属），**永不回凭证明文或密文**。
     */
    suspend fun queryAudit(
        credential: String = "",
        action: String = "",
        sinceMs: Long = 0L,
        limit: Int = 50,
    ): List<VaultAuditLogEntity> =
        auditDao.queryAudit(credential.trim(), action.trim(), sinceMs, limit.coerceIn(1, 200))

    /**
     * 清空审计记录，并留下一条**回执**。
     *
     * 清空这个动作本身必须留痕：否则“谁在什么时候抹掉了记录”无从查起，
     * 审计就成了可一键销毁的形式。回执以 [AUDIT_CLEAR_RECEIPT] 作占位名，
     * 不与真实凭证混同。
     */
    suspend fun clearAudit() {
        auditDao.clearAll()
        auditDao.insert(
            VaultAuditLogEntity(
                credentialName = AUDIT_CLEAR_RECEIPT,
                caller = "manual",
                action = "audit_cleared",
            )
        )
    }

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

        /**
         * 值指纹：明文取 SHA-256 后截断为 16 位十六进制。
         *
         * 用途是**不见值比对**——回答"两条是否相同 / 值是否变过"，而不暴露明文。
         * 高熵密钥无需加盐；此指纹仅用于本地比对，不要写进配置或日志。
         */
        fun fingerprint(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)

        /**
         * 把任意输入规范化成合规凭证名（`^[A-Z][A-Z0-9_]*$`）。
         *
         * 用于"粘贴即入库"：用户往往直接粘一段描述或小写名，这里统一转大写蛇形，
         * 非法字符折成下划线；首字符不是字母时补前缀，保证结果始终合法。
         */
        fun normalizeName(raw: String): String {
            val folded = raw.trim().uppercase().replace(Regex("[^A-Z0-9_]+"), "_").trim('_')
            val prefixed = if (folded.isEmpty() || !folded[0].isLetter()) "CRED_$folded" else folded
            return prefixed.take(64).trim('_').ifEmpty { "CRED_UNNAMED" }
        }

        /** 校验凭证名是否合规。空名/小写/连字符/空格/中文均不合规。 */
        fun validateCredentialName(name: String): Boolean =
            name.isNotBlank() && NAME_REGEX.matches(name)
    }
}
