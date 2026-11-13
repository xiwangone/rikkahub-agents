package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份「迁移包」的结构与编解码（**纯逻辑**，不碰文件系统与数据库）。
 *
 * 解决的问题：常规备份包里的凭证（`VaultCredentialEntity.valueEncrypted`）与 provider 配置
 * 都是**本机 Keystore 加密**的 —— 文件级口令加密只防「被人看」，换机后本机密钥不在就解不开。
 * 迁移包在**导出时**把这两块解成明文、放进 zip 的 `migration/` 段，再用同一口令加密整个包；
 * 导入时用**新设备 Keystore** 重新加密写回。
 *
 * ⚠ 红线：`migration/credentials.json` 与 `migration/providers.json` **含明文密钥**。
 * 它们只存在于「口令加密前」的中间产物里 —— 用完即删，**不得**留在应用目录、**不得**上传云盘。
 */
@Serializable
data class MigrationManifest(
    val version: Int = MigrationPackage.CURRENT_VERSION,
    val exportedAt: Long = 0,
    /** 本次导出的备份项（如 DATABASE / SETTINGS），便于导入侧核对。 */
    val items: List<String> = emptyList(),
    /** 加密方式标记：目前只有口令加密。 */
    val encryption: String = ENCRYPTION_PASSWORD,
) {
    companion object {
        const val ENCRYPTION_PASSWORD = "password"
    }
}

/** 一条凭证的**明文**形态（仅供迁移包内部使用）。 */
@Serializable
data class MigrationCredential(
    val name: String,
    val value: String,
    val type: String = "",
    val group: String = "",
    val description: String = "",
    /** 非敏感元数据（endpoint / header / username 等）的 JSON 串，原样搬运。 */
    val metaJson: String? = null,
    /** SSH / GPG 类条目的公钥行（非敏感）。 */
    val publicKey: String? = null,
)

object MigrationPackage {

    /** 迁移包内明文段所在的目录名。 */
    const val DIR = "migration"
    const val MANIFEST_NAME = "MANIFEST.json"
    const val CREDENTIALS_NAME = "credentials.json"
    const val PROVIDERS_NAME = "providers.json"
    const val SSH_HOSTS_NAME = "ssh_hosts.json"
    const val SCHEDULED_JOBS_NAME = "scheduled_jobs.json"

    /**
     * 结构版本：加字段/改语义时递增，导入侧据此拒绝不兼容的包。
     * v2：新增 ssh_hosts.json / scheduled_jobs.json 两段（可选，旧包 v1 仍可导入）。
     */
    const val CURRENT_VERSION = 2

    private val migrationJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun buildManifest(
        exportedAt: Long,
        items: List<String>,
    ): MigrationManifest =
        MigrationManifest(
            version = CURRENT_VERSION,
            exportedAt = exportedAt,
            items = items,
            encryption = MigrationManifest.ENCRYPTION_PASSWORD,
        )

    fun encodeManifest(manifest: MigrationManifest): String = migrationJson.encodeToString(manifest)

    /** 解析 MANIFEST；非法 JSON 或缺关键字段时返回 null（调用方据此判定「不是迁移包」）。 */
    fun parseManifest(raw: String): MigrationManifest? =
        runCatching { migrationJson.decodeFromString<MigrationManifest>(raw) }
            .getOrNull()
            ?.takeIf { it.version in 1..CURRENT_VERSION && it.encryption == MigrationManifest.ENCRYPTION_PASSWORD }

    fun encodeCredentials(credentials: List<MigrationCredential>): String =
        migrationJson.encodeToString(credentials)

    /** 解析凭证明文段；非法 JSON 返回 null（不静默当成空清单，避免「导入后凭证全没了」）。 */
    fun parseCredentials(raw: String): List<MigrationCredential>? =
        runCatching { migrationJson.decodeFromString<List<MigrationCredential>>(raw) }.getOrNull()

    /**
     * zip 条目名（相对路径）里是否含迁移段 —— 用于把「迁移包」与常规备份包区分开，
     * 避免用户拿旧包走新流程（反之亦然）。
     */
    fun looksLikeMigrationPackage(entryNames: Collection<String>): Boolean =
        entryNames.any { name -> name.trimStart('/') == "$DIR/$MANIFEST_NAME" }

    /** 迁移段里是否至少包含一段可应用的内容（用于拒绝只有清单的空包）。 */
    fun hasLogicalPayload(entryNames: Collection<String>): Boolean =
        entryNames.any { name ->
            val normalized = name.trimStart('/')
            normalized in setOf(
                "$DIR/$CREDENTIALS_NAME",
                "$DIR/$PROVIDERS_NAME",
                "$DIR/$SSH_HOSTS_NAME",
                "$DIR/$SCHEDULED_JOBS_NAME",
            )
        }
}

private const val ZIP_BUFFER_SIZE = 64 * 1024
private const val STALE_TEMP_MAX_AGE_MS = 2L * 60 * 60 * 1000
private const val DISK_SAFETY_MARGIN_BYTES = 16L * 1024 * 1024

/**
 * 把若干文本条目追加进一个已有 zip，**写出到 [target]**（不改动 [source]）。
 *
 * - [source] 里若已有与 [extraEntries] 同名的条目，以追加的为准（原条目被丢弃，避免 zip 内重名）；
 * - [target] 必须与 [source] 不是同一个文件（避免边读边写）。
 */
internal fun appendTextEntriesToZip(
    source: File,
    extraEntries: Map<String, String>,
    target: File,
) {
    require(source.absolutePath != target.absolutePath) { "source and target must differ" }
    ZipInputStream(BufferedInputStream(source.inputStream(), ZIP_BUFFER_SIZE)).use { zipIn ->
        ZipOutputStream(BufferedOutputStream(target.outputStream(), ZIP_BUFFER_SIZE)).use { zipOut ->
            copyZipEntriesExcept(zipIn, zipOut, extraEntries.keys)
            writeZipTextEntries(zipOut, extraEntries)
        }
    }
}

/** 复制原 zip 的条目（跳过 [skip] 里的名字，避免与追加段重名）。 */
private fun copyZipEntriesExcept(
    zipIn: ZipInputStream,
    zipOut: ZipOutputStream,
    skip: Set<String>,
) {
    var entry = zipIn.nextEntry
    while (entry != null) {
        if (!entry.isDirectory && entry.name !in skip) {
            zipOut.putNextEntry(ZipEntry(entry.name))
            zipIn.copyTo(zipOut, bufferSize = ZIP_BUFFER_SIZE)
            zipOut.closeEntry()
        }
        zipIn.closeEntry()
        entry = zipIn.nextEntry
    }
}

private fun writeZipTextEntries(
    zipOut: ZipOutputStream,
    entries: Map<String, String>,
) {
    entries.forEach { (name, content) ->
        zipOut.putNextEntry(ZipEntry(name))
        zipOut.write(content.encodeToByteArray())
        zipOut.closeEntry()
    }
}

/**
 * 列出 zip 里的条目名（不解压内容）。
 * 优先走 [ZipFile] 读取文件末尾 Central Directory（130MB+ 包 O(1) 毫秒级完成），
 * 仅在流式无索引归档时回退到 [ZipInputStream]。
 */
internal fun listZipEntryNames(zipFile: File): List<String> =
    runCatching {
        ZipFile(zipFile).use { zf ->
            val names = mutableListOf<String>()
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) names += entry.name.trimStart('/')
            }
            names
        }
    }.getOrElse {
        runCatching {
            val names = mutableListOf<String>()
            ZipInputStream(BufferedInputStream(zipFile.inputStream(), ZIP_BUFFER_SIZE)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) names += entry.name.trimStart('/')
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            names
        }.getOrDefault(emptyList())
    }

/**
 * 读取 zip 内某个文本条目的内容；不存在或读取失败返回 null。
 * 优先走 [ZipFile] Central Directory 直接定位位于包尾的 `migration/` 条目，
 * 避免 [ZipInputStream] 顺序解压跳过前序 130MB+ 数据库与附件条目。
 */
internal fun readTextEntry(zipFile: File, entryName: String): String? {
    val normalizedTarget = entryName.trimStart('/')
    return runCatching {
        ZipFile(zipFile).use { zf ->
            val entry =
                zf.getEntry(normalizedTarget)
                    ?: zf.getEntry("/$normalizedTarget")
                    ?: zf.entries().asSequence().firstOrNull {
                        !it.isDirectory && it.name.trimStart('/') == normalizedTarget
                    }
                    ?: return@use null
            zf.getInputStream(entry).use { it.readBytes().decodeToString() }
        }
    }.getOrElse {
        runCatching {
            ZipInputStream(BufferedInputStream(zipFile.inputStream(), ZIP_BUFFER_SIZE)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.trimStart('/') == normalizedTarget) {
                        return@use zipIn.readBytes().decodeToString()
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
                null
            }
        }.getOrNull()
    }
}

/**
 * 清理缓存目录中因上次进程被杀或异常中断残留的过期备份临时文件（默认超过 2 小时）。
 */
internal fun cleanupStaleBackupTemps(cacheDir: File, maxAgeMs: Long = STALE_TEMP_MAX_AGE_MS) {
    if (!cacheDir.isDirectory) return
    val cutoff = System.currentTimeMillis() - maxAgeMs
    cacheDir.listFiles().orEmpty().forEach { file ->
        if (!file.isFile || file.lastModified() >= cutoff) return@forEach
        val name = file.name
        val isBackupTemp =
            name.startsWith("backup_") ||
                name.startsWith("migration_") ||
                name.startsWith("backup-db-") ||
                name.startsWith("temp_restore_") ||
                name.startsWith("temp_migration_") ||
                name.startsWith("temp_chatbox_") ||
                name.startsWith("temp_cherry_")
        if (isBackupTemp) {
            runCatching { file.delete() }
        }
    }
}

/**
 * 写大文件前的磁盘可用空间预检（参考 Signal / Seedvault 备份预检策略）。
 * 当目标分区报告了有效 `usableSpace` (> 0) 且不足 `requiredBytes + 16 MiB` 时提前抛清晰错误。
 */
internal fun ensureSufficientDiskSpace(targetDir: File, requiredBytes: Long) {
    if (requiredBytes <= 0L) return
    val dir = if (targetDir.isDirectory) targetDir else targetDir.parentFile ?: return
    val usable = dir.usableSpace
    val needed = requiredBytes + DISK_SAFETY_MARGIN_BYTES
    if (usable in 1 until needed) {
        val usableMb = usable / (1024 * 1024)
        val neededMb = needed / (1024 * 1024)
        throw IOException("Not enough free disk space (about ${usableMb}MB available, at least ${neededMb}MB required)")
    }
}
