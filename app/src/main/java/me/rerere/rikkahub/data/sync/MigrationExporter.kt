package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import java.io.File

/** 迁移包导出结果（供 UI 报告：包里有什么、有多少解不开）。 */
data class MigrationExportResult(
    val file: File,
    val credentialCount: Int,
    /** 本机解不开、因而**没有**进入迁移包的凭证条数（不能静默，要让用户知道）。 */
    val undecryptableCredentials: Int,
    val providersIncluded: Boolean,
)

/**
 * 生成「迁移包」：常规备份包 + `migration/` 明文段 → 再用**备份口令**整包加密。
 *
 * 逻辑段由 [WebDavSync.prepareBackupFile] 统一写入（与网络备份共用同一套按域逻辑），
 * 这里只负责：
 * - 迁移范围固定为「把人带走」的全套：会话库 + 设置 + 技能 + 头像 + 四个逻辑域；
 * - 强制整包加密（包内含明文密钥，未开启加密直接拒绝）；
 * - 统一命名为 `migration_<timestamp>.zip.enc`，与云端 `listMigrationFiles` 前缀约定对齐。
 */
class MigrationExporter(
    private val webDavSync: WebDavSync,
    private val backupEncryptionManager: BackupEncryptionManager,
) {

    suspend fun export(config: WebDavConfig): MigrationExportResult = withContext(Dispatchers.IO) {
        // 迁移包内含**明文密钥**（凭证值与 provider 配置）→ 必须整包加密；
        // 未开启加密就拒绝导出，绝不能像常规备份那样“未启用则明文放行”。
        check(backupEncryptionManager.isEnabled && backupEncryptionManager.rememberedPassword != null) {
            "迁移包含明文密钥，请先在「备份与恢复 → 加密设置」开启加密并设置口令。"
        }
        val scope =
            config.copy(
                items = listOf(
                    WebDavConfig.BackupItem.DATABASE,
                    WebDavConfig.BackupItem.SETTINGS,
                    WebDavConfig.BackupItem.SKILLS,
                    WebDavConfig.BackupItem.AVATARS,
                    WebDavConfig.BackupItem.FONTS_IMAGES,
                    WebDavConfig.BackupItem.WORKSPACE_DOCS,
                    WebDavConfig.BackupItem.PROVIDERS,
                    WebDavConfig.BackupItem.VAULT_CREDENTIALS,
                    WebDavConfig.BackupItem.SSH_HOSTS,
                    WebDavConfig.BackupItem.SCHEDULED_JOBS,
                ),
            )
        val prepared = webDavSync.prepareBackupFile(scope)
        var returned: File? = null
        try {
            val encrypted = backupEncryptionManager.maybeEncrypt(prepared.file)
            val migrationFile =
                if (encrypted.name.startsWith("backup_")) {
                    val target = File(encrypted.parentFile, "migration_" + encrypted.name.removePrefix("backup_"))
                    if (target.exists()) target.delete()
                    if (encrypted.renameTo(target)) target else encrypted
                } else {
                    encrypted
                }
            returned = migrationFile
            MigrationExportResult(
                file = migrationFile,
                credentialCount = prepared.credentialCount,
                undecryptableCredentials = prepared.undecryptableCredentials,
                providersIncluded = prepared.providersIncluded,
            )
        } finally {
            // 明文中间产物含密钥：无论加密成功与否都必须清理
            if (prepared.file.exists() && prepared.file !== returned) prepared.file.delete()
        }
    }
}
