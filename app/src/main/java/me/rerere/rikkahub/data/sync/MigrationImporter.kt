package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.data.vault.CredentialImporter
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import java.io.File

/** 迁移包导入结果（供 UI **如实**报告覆盖了什么、跳过了什么）。 */
data class MigrationImportResult(
    val credentialsImported: Int,
    val credentialsSkipped: Int,
    /** 同名但值不同（用指纹比对的，不经手明文）→ 已被包内值覆盖。 */
    val credentialsOverwritten: List<String>,
    val providersRestored: Boolean,
)

/** 迁移条目 → 凭证库导入条目（纯映射，便于单测）。 */
internal fun MigrationCredential.toParsedEntry(): CredentialImporter.ParsedEntry =
    CredentialImporter.ParsedEntry(
        name = name,
        value = value,
        description = description,
        group = group,
        publicKey = publicKey.orEmpty(),
        type = type,
        metaJson = metaJson.orEmpty(),
    )

/**
 * 导入迁移包（全量覆盖）：
 * ① 校验清单（不是迁移包 / 版本不兼容 → 直接失败，不半途改数据）；
 * ② **先建回滚点**（调用方实现：再导一次同机备份包）；
 * ③ 覆盖恢复会话库与设置；
 * ④ 凭证明文 → 走既有 `importEntries`（同名 upsert）写回，由**本机 Keystore** 重新加密；
 * ⑤ provider 配置写回（写入路径自带重新加密）。
 *
 * 顺序有意如此：**任何一步失败都不该留下"改了一半"的状态**，而回滚点必须在覆盖动作之前就绪。
 */
class MigrationImporter(
    private val webDavSync: WebDavSync,
    private val backupEncryptionManager: BackupEncryptionManager,
    private val vaultRepository: CredentialVaultRepository,
    // 领域前缀：避免与其它文件里的同名局部变量撞名（结构自检按名判跨文件引用会误报）
    private val migrationSettingsStore: SettingsStore,
) {
    private val migrationJson = Json { ignoreUnknownKeys = true }

    /** 只读 zip 条目名即可判断"是不是迁移包" —— 供 UI 在动手前给出准确提示。 */
    fun looksLikeMigrationPackage(file: File): Boolean =
        MigrationPackage.looksLikeMigrationPackage(listZipEntryNames(file))

    suspend fun import(
        config: WebDavConfig,
        file: File,
        createRollbackPoint: suspend () -> Unit,
    ): MigrationImportResult {
        val plainZip = backupEncryptionManager.maybeDecrypt(file)
        try {
            val manifest =
                MigrationPackage.parseManifest(
                    readTextEntry(plainZip, "${MigrationPackage.DIR}/${MigrationPackage.MANIFEST_NAME}").orEmpty(),
                ) ?: error("不是迁移包，或清单版本与本机不兼容。")

            // ① 回滚点：必须在覆盖之前完成（由调用方决定怎么做）
            createRollbackPoint()

            // ② 覆盖恢复（范围以清单为准；清单里出现本机不认识的项就忽略）
            val items =
                manifest.items.mapNotNull { name ->
                    runCatching { WebDavConfig.BackupItem.valueOf(name) }.getOrNull()
                }
            val scope =
                config.copy(
                    items =
                        items.ifEmpty {
                            listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.SETTINGS)
                        },
                )
            webDavSync.restoreFromLocalFile(plainZip, scope)

            // ③ 凭证：明文条目写回库（同名按 upsert 语义覆盖）
            val credentials =
                readTextEntry(plainZip, "${MigrationPackage.DIR}/${MigrationPackage.CREDENTIALS_NAME}")
                    ?.let { raw -> MigrationPackage.parseCredentials(raw) }
                    ?: emptyList()
            val outcome = vaultRepository.importEntries(credentials.map { entry -> entry.toParsedEntry() })

            // ④ provider 配置写回（写入路径会用本机 Keystore 重新加密）
            val providers =
                readTextEntry(plainZip, "${MigrationPackage.DIR}/${MigrationPackage.PROVIDERS_NAME}")
                    ?.let { raw ->
                        runCatching { migrationJson.decodeFromString<List<ProviderSetting>>(raw) }.getOrNull()
                    }
            if (providers != null) {
                migrationSettingsStore.update { current -> current.copy(providers = providers) }
            }

            return MigrationImportResult(
                credentialsImported = outcome.imported,
                credentialsSkipped = outcome.skipped,
                credentialsOverwritten = outcome.overwrittenDifferentValue,
                providersRestored = providers != null,
            )
        } finally {
            if (plainZip.absolutePath != file.absolutePath && plainZip.exists()) plainZip.delete()
        }
    }
}
