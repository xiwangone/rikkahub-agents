package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import me.rerere.rikkahub.data.vault.CredentialVaultRepository

/**
 * 迁移包的数据来源：把「本机 Keystore 加密」的凭证与 provider 配置，转成迁移包里的**明文档**
 * （随后整包再用口令加密 —— 跨机才解得开）。
 *
 * ⚠ 这些函数的产物含明文密钥：只允许写进「打包中途的临时 zip」，用完即删，
 * **不得**落进应用目录长期文件，也**不得**上传云盘。
 */

/** 单条凭证的本机记录 → 迁移包条目（纯映射，便于单测）。空白字段转 null，避免制造噪音字段。 */
internal fun toMigrationCredential(
    entity: VaultCredentialEntity,
    plaintextValue: String,
): MigrationCredential =
    MigrationCredential(
        name = entity.name,
        value = plaintextValue,
        type = entity.type,
        group = entity.grp,
        description = entity.description,
        metaJson = entity.metaJson.takeIf { it.isNotBlank() },
        publicKey = entity.publicKey.takeIf { it.isNotBlank() },
    )

/**
 * 遍历凭证库并逐条解密。
 *
 * 返回 `(可迁移条目, 解不开的条数)` —— 解不开的**不静默丢弃**：调用方要把条数报给用户，
 * 否则「换了机发现少了几条凭证」会毫无线索。
 */
internal suspend fun collectMigrationCredentials(
    vaultRepository: CredentialVaultRepository,
): Pair<List<MigrationCredential>, Int> {
    var undecryptable = 0
    val credentials =
        vaultRepository.getAll().mapNotNull { entity ->
            val plaintext = vaultRepository.decryptValue(entity)
            if (plaintext == null) {
                undecryptable += 1
                null
            } else {
                toMigrationCredential(entity, plaintext)
            }
        }
    return credentials to undecryptable
}

