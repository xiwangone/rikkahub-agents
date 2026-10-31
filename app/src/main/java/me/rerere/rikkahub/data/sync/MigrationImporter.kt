package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.data.vault.CredentialImporter
import java.io.File

/** 迁移包导入结果（供 UI **如实**报告包里准备写回什么）。 */
data class MigrationImportResult(
    val credentialsImported: Int,
    /** 恢复阶段无法逐条比对（新库尚未安装），恒为 0；保留字段兼容既有 UI。 */
    val credentialsSkipped: Int,
    /** 同上：同名覆盖需在启动期写回时才知道，恢复阶段不预判。 */
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
 * ③ 交给 `WebDavSync.restoreFromLocalFile`：会话库/设置/文件挂起，`migration/` 逻辑段也挂起；
 * ④ 下次启动安装新库、Room 打开后，由 `PendingLogicalApply` 用本机 Keystore 重新加密写回。
 *
 * 顺序有意如此：现在**不能**直接写库 —— 新库要等下次启动才安装，提前写入会被挂起的新库覆盖，
 * 这正是旧版「迁移包恢复后凭证全空」的根因。
 */
class MigrationImporter(
    private val webDavSync: WebDavSync,
    private val backupEncryptionManager: BackupEncryptionManager,
) {
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

            // ③ 只读统计（真正的写回发生在下次启动安装新库之后）
            val credentials =
                readTextEntry(plainZip, "${MigrationPackage.DIR}/${MigrationPackage.CREDENTIALS_NAME}")
                    ?.let { raw -> MigrationPackage.parseCredentials(raw) }
                    ?: emptyList()
            val providers =
                readTextEntry(plainZip, "${MigrationPackage.DIR}/${MigrationPackage.PROVIDERS_NAME}") != null

            return MigrationImportResult(
                credentialsImported = credentials.size,
                credentialsSkipped = 0,
                credentialsOverwritten = emptyList(),
                providersRestored = providers,
            )
        } finally {
            if (plainZip.absolutePath != file.absolutePath && plainZip.exists()) plainZip.delete()
        }
    }
}
