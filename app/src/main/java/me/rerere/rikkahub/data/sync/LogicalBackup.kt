package me.rerere.rikkahub.data.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 按域逻辑备份的域名清单。
 *
 * 用**字符串名**而不是 [me.rerere.rikkahub.data.datastore.WebDavConfig.BackupItem] /
 * [me.rerere.rikkahub.data.sync.s3.S3Config.BackupItem]：WebDAV 与 S3 各有独立枚举，
 * 逻辑层只认名字，两边各映射一次即可，不会出现「一个通道支持、另一个忘了」。
 */
object LogicalDomains {
    const val PROVIDERS = "PROVIDERS"
    const val VAULT_CREDENTIALS = "VAULT_CREDENTIALS"
    const val SSH_HOSTS = "SSH_HOSTS"
    const val SCHEDULED_JOBS = "SCHEDULED_JOBS"

    val ALL: Set<String> = setOf(PROVIDERS, VAULT_CREDENTIALS, SSH_HOSTS, SCHEDULED_JOBS)

    fun isLogical(name: String): Boolean = name in ALL

    /** 选择里是否含逻辑域 —— 这些域在包里是**明文**，因此必须整包口令加密。 */
    fun anySelected(names: Collection<String>): Boolean = names.any { isLogical(it) }
}

/** 一次逻辑域导出的产物：zip 文本条目 + 供 UI 报告的统计。 */
data class LogicalExport(
    val entries: Map<String, String> = emptyMap(),
    val credentialCount: Int = 0,
    val undecryptableCredentials: Int = 0,
    val providersIncluded: Boolean = false,
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/** 常规备份打包结果（附逻辑域统计，供 UI 如实报告）。 */
data class PreparedBackup(
    val file: File,
    val credentialCount: Int = 0,
    val undecryptableCredentials: Int = 0,
    val providersIncluded: Boolean = false,
)

/** 打包统计（不含临时文件，上传/落盘后仍可回报给 UI）。 */
data class BackupStats(
    val credentialCount: Int = 0,
    val undecryptableCredentials: Int = 0,
    val providersIncluded: Boolean = false,
)

fun PreparedBackup.toStats(): BackupStats =
    BackupStats(
        credentialCount = credentialCount,
        undecryptableCredentials = undecryptableCredentials,
        providersIncluded = providersIncluded,
    )

/**
 * 逻辑域导出：把「本机 Keystore 加密」的内容解成明文 JSON，写进备份包的 `migration/` 段，
 * 随后由调用方整包口令加密。
 *
 * 与原始 DATABASE 项的区别：DATABASE 拷的是 sqlite 文件，里面的 vault 密文/SSH 密文只在本机
 * 可解；逻辑域则先解密再打包，换机后由**新设备**重新加密 —— 这是「网络备份按迁移包逻辑」的核心。
 *
 * ⚠ 产物含明文密钥：只能写进即将被口令加密的 zip，不得单独落盘。
 */
class LogicalBackup(
    private val json: Json,
    private val settingsStore: SettingsStore,
    private val vaultRepository: CredentialVaultRepository,
    private val sshHostRepository: SshHostRepository,
    private val scheduledJobRepository: ScheduledJobRepository,
) {
    suspend fun collect(itemNames: Collection<String>): LogicalExport {
        val wanted = itemNames.toSet()
        var credentialCount = 0
        var undecryptable = 0
        val entries =
            buildMap {
                if (LogicalDomains.PROVIDERS in wanted) {
                    put(
                        "${MigrationPackage.DIR}/${MigrationPackage.PROVIDERS_NAME}",
                        json.encodeToString(settingsStore.settingsFlow.value.providers),
                    )
                }
                if (LogicalDomains.VAULT_CREDENTIALS in wanted) {
                    val (credentials, failed) = collectMigrationCredentials(vaultRepository)
                    credentialCount = credentials.size
                    undecryptable = failed
                    put(
                        "${MigrationPackage.DIR}/${MigrationPackage.CREDENTIALS_NAME}",
                        MigrationPackage.encodeCredentials(credentials),
                    )
                }
                if (LogicalDomains.SSH_HOSTS in wanted) {
                    put(
                        "${MigrationPackage.DIR}/${MigrationPackage.SSH_HOSTS_NAME}",
                        json.encodeToString(sshHostRepository.getAll()),
                    )
                }
                if (LogicalDomains.SCHEDULED_JOBS in wanted) {
                    put(
                        "${MigrationPackage.DIR}/${MigrationPackage.SCHEDULED_JOBS_NAME}",
                        json.encodeToString(scheduledJobRepository.getAll()),
                    )
                }
            }
        return LogicalExport(
            entries = entries,
            credentialCount = credentialCount,
            undecryptableCredentials = undecryptable,
            providersIncluded = LogicalDomains.PROVIDERS in wanted,
        )
    }

    /** 把选中的逻辑域直接写进正在构建的 zip（含 MANIFEST）。 */
    suspend fun writeTo(zipOut: ZipOutputStream, itemNames: Collection<String>): LogicalExport {
        val export = collect(itemNames)
        if (export.isEmpty) return export
        writeTextEntry(
            zipOut,
            "${MigrationPackage.DIR}/${MigrationPackage.MANIFEST_NAME}",
            MigrationPackage.encodeManifest(
                MigrationPackage.buildManifest(
                    exportedAt = System.currentTimeMillis(),
                    items = itemNames.toList(),
                ),
            ),
        )
        export.entries.forEach { (name, content) -> writeTextEntry(zipOut, name, content) }
        return export
    }

    private fun writeTextEntry(zipOut: ZipOutputStream, name: String, content: String) {
        zipOut.putNextEntry(ZipEntry(name))
        zipOut.write(content.encodeToByteArray())
        zipOut.closeEntry()
    }
}
