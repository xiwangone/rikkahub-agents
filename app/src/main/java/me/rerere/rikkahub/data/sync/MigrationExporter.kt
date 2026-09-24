package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import java.io.File

/**
 * 组装迁移段（顶层纯函数，便于单测）：清单 + 凭证明文 + provider 明文。
 *
 * provider 段只在确实拿到明文时加入 —— 宁可少一段并在结果里标明，也不要塞一段空 JSON
 * 让导入侧误以为「这台机器没有 provider 配置」。
 */
internal fun migrationEntriesOf(
    exportedAt: Long,
    items: List<String>,
    credentials: List<MigrationCredential>,
    providersJson: String?,
): Map<String, String> =
    buildMap {
        put(
            "${MigrationPackage.DIR}/${MigrationPackage.MANIFEST_NAME}",
            MigrationPackage.encodeManifest(
                MigrationPackage.buildManifest(exportedAt = exportedAt, items = items),
            ),
        )
        put(
            "${MigrationPackage.DIR}/${MigrationPackage.CREDENTIALS_NAME}",
            MigrationPackage.encodeCredentials(credentials),
        )
        providersJson?.takeIf { it.isNotBlank() }?.let { json ->
            put("${MigrationPackage.DIR}/${MigrationPackage.PROVIDERS_NAME}", json)
        }
    }

/** 迁移包导出结果（供 UI 报告：包里有什么、有多少解不开）。 */
data class MigrationExportResult(
    val file: File,
    val credentialCount: Int,
    /** 本机解不开、因而**没有**进入迁移包的凭证条数（不能静默，要让用户知道）。 */
    val undecryptableCredentials: Int,
    val providersIncluded: Boolean,
)

/**
 * 生成「迁移包」：常规备份包（会话库 + 设置）+ `migration/` 明文段 → 再用**备份口令**整包加密。
 *
 * 为什么是「常规包 + 追加」而不是另写一套打包：常规备份已经过充分验证（勾选项、目录结构、
 * 旧配置兼容都在 `WebDavSync` 里），迁移包只多一件事 —— 把两处**本机 Keystore 加密**的内容
 * 换成跨机可解的形式。
 *
 * 红线：中间产物的 `migration/` 段含**明文密钥** → 全部写在缓存目录的临时文件里，成功后即删；
 * 不落应用长期目录、不上传云盘。
 */
class MigrationExporter(
    private val webDavSync: WebDavSync,
    private val backupEncryptionManager: BackupEncryptionManager,
    private val vaultRepository: CredentialVaultRepository,
) {

    /**
     * 导出迁移包。范围**固定**为会话库 + 设置（迁移的语义就是"把人带走"，不跟随页面的勾选项）。
     *
     * @param providersJson 解密后的 provider 配置 JSON（调用方从当前设置序列化；null = 不带这段）
     */
    suspend fun export(
        config: WebDavConfig,
        providersJson: String?,
    ): MigrationExportResult {
        // 迁移包内含**明文密钥**（凭证值与 provider 配置）→ 必须整包加密；
        // 未开启加密就拒绝导出，绝不能像常规备份那样“未启用则明文放行”。
        check(backupEncryptionManager.isEnabled && backupEncryptionManager.rememberedPassword != null) {
            "迁移包含明文密钥，请先在「备份与恢复 → 加密设置」开启加密并设置口令。"
        }
        val scope =
            config.copy(
                items = listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.SETTINGS),
            )
        val plainZip = webDavSync.prepareBackupFile(scope)
        try {
            val (credentials, undecryptable) = collectMigrationCredentials(vaultRepository)
            val entries =
                migrationEntriesOf(
                    exportedAt = System.currentTimeMillis(),
                    items = scope.items.map { it.name },
                    credentials = credentials,
                    providersJson = providersJson,
                )
            val withMigration = File(plainZip.parentFile, "migration_${plainZip.name}")
            appendTextEntriesToZip(plainZip, entries, withMigration)
            val encrypted = backupEncryptionManager.maybeEncrypt(withMigration)
            if (encrypted.absolutePath != withMigration.absolutePath && withMigration.exists()) {
                withMigration.delete()
            }
            return MigrationExportResult(
                file = encrypted,
                credentialCount = credentials.size,
                undecryptableCredentials = undecryptable,
                providersIncluded = providersJson?.isNotBlank() == true,
            )
        } finally {
            if (plainZip.exists()) plainZip.delete()
        }
    }
}
